package io.github.daermond.artoptimizer

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.CancellationException

/** Only these commands can be produced by the built-in workflow. */
sealed interface SafeAdbCommand {
    data object ListThirdPartyPackages : SafeAdbCommand
    data object Probe : SafeAdbCommand
    data object DeviceId : SafeAdbCommand
    data object Manufacturer : SafeAdbCommand
    data object Model : SafeAdbCommand
    data object AndroidRelease : SafeAdbCommand
    data object Sdk : SafeAdbCommand
    data object ShellUid : SafeAdbCommand
    data object CompileHelp : SafeAdbCommand
    data object ArtHelp : SafeAdbCommand
    data class PackagePath(val packageId: PackageId) : SafeAdbCommand
    data class PackageDump(val packageId: PackageId) : SafeAdbCommand
    data class ForceStop(val packageId: PackageId) : SafeAdbCommand
    data class CompileSpeed(val packageId: PackageId) : SafeAdbCommand
    data class ArtDump(val packageId: PackageId) : SafeAdbCommand
}

object SafeCommandRenderer {
    fun render(command: SafeAdbCommand): String = when (command) {
        SafeAdbCommand.ListThirdPartyPackages -> "pm list packages -3"
        SafeAdbCommand.Probe -> "echo art-optimizer-ready"
        SafeAdbCommand.DeviceId -> "settings get secure android_id"
        SafeAdbCommand.Manufacturer -> "getprop ro.product.manufacturer"
        SafeAdbCommand.Model -> "getprop ro.product.model"
        SafeAdbCommand.AndroidRelease -> "getprop ro.build.version.release"
        SafeAdbCommand.Sdk -> "getprop ro.build.version.sdk"
        SafeAdbCommand.ShellUid -> "id -u"
        SafeAdbCommand.CompileHelp -> "cmd package help"
        SafeAdbCommand.ArtHelp -> "pm art help"
        is SafeAdbCommand.PackagePath -> "pm path ${command.packageId.value}"
        is SafeAdbCommand.PackageDump -> "dumpsys package ${command.packageId.value}"
        is SafeAdbCommand.ForceStop -> "am force-stop ${command.packageId.value}"
        is SafeAdbCommand.CompileSpeed -> "cmd package compile -m speed -f ${command.packageId.value}"
        is SafeAdbCommand.ArtDump -> "pm art dump ${command.packageId.value}"
    }
}

data class ShellResult(val stdout: String, val stderr: String, val exitCode: Int?, val truncated: Boolean = false) {
    val succeeded: Boolean get() = exitCode == 0
    val output: String get() = stdout + stderr
}

interface SafeShell {
    suspend fun shell(command: SafeAdbCommand): ShellResult
}

/** Raw shell is exposed only to the explicit Advanced console use case. */
interface ConsoleShell {
    suspend fun shellRaw(command: String): ShellResult
}

data class InstalledPackage(val id: PackageId, val versionCode: Long?, val versionName: String?)

object PackageOutputParser {
    private val versionCode = Regex("\\bversionCode=(\\d+)")
    private val versionName = Regex("\\bversionName=([^\\s]+)")

    fun thirdPartyPackages(output: String): List<PackageId> = output.lineSequence()
        .map(String::trim)
        .filter { it.startsWith("package:") }
        .mapNotNull { PackageId.parse(it.removePrefix("package:")) }
        .distinct()
        .toList()

    fun version(output: String): Pair<Long?, String?> =
        versionCode.find(output)?.groupValues?.get(1)?.toLongOrNull() to
            versionName.find(output)?.groupValues?.get(1)

    fun artReportsSpeed(output: String): Boolean {
        val statuses = Regex("(?:\\[|\\b)status=([^]\\s]+)").findAll(output)
            .map { it.groupValues[1] }.toList()
        if (statuses.isNotEmpty()) return statuses.all { it == "speed" }
        return Regex("(?im)compiler.?filter\\s*[:=]\\s*speed(?:\\s|$)").containsMatchIn(output)
    }
}

enum class ValidationLevel { COMMAND, ART_STATE }
enum class OptimizationPhase { QUEUED, VALIDATING, READING, STOPPING, COMPILING, VERIFYING, COMPLETED, FAILED, SKIPPED }

data class OptimizationRecord(
    val packageId: PackageId,
    val optimizedVersionCode: Long?,
    val filter: String,
    val timestampMillis: Long,
    val result: String,
    val validationLevel: ValidationLevel,
    val lastSuccessfulAtMillis: Long? = if (result == "Success") timestampMillis else null,
)

fun updatedSinceOptimization(installedVersionCode: Long?, record: OptimizationRecord?): Boolean =
    installedVersionCode != null && record?.optimizedVersionCode != null &&
        installedVersionCode != record.optimizedVersionCode

data class OptimizationEvent(
    val packageId: PackageId,
    val phase: OptimizationPhase,
    val completed: Int,
    val total: Int,
    val message: String = "",
    val record: OptimizationRecord? = null,
    val atMillis: Long = System.currentTimeMillis(),
)

fun terminalRecord(previous: OptimizationRecord?, event: OptimizationEvent): OptimizationRecord? {
    event.record?.let { return it }
    if (event.phase != OptimizationPhase.FAILED && event.phase != OptimizationPhase.SKIPPED) return null
    return OptimizationRecord(
        event.packageId,
        previous?.optimizedVersionCode,
        "speed",
        event.atMillis,
        "${event.phase}: ${event.message}".take(240),
        previous?.validationLevel ?: ValidationLevel.COMMAND,
        previous?.lastSuccessfulAtMillis,
    )
}

class ArtOptimizer(private val shell: SafeShell, private val now: () -> Long = System::currentTimeMillis) {
    fun optimize(packages: List<PackageId>, self: PackageId): Flow<OptimizationEvent> = flow {
        var completed = 0
        val selected = packages.distinct()
        for (id in selected) {
            suspend fun event(phase: OptimizationPhase, message: String = "", record: OptimizationRecord? = null) {
                emit(OptimizationEvent(id, phase, completed, selected.size, message, record, now()))
            }
            event(OptimizationPhase.QUEUED)
            try {
                event(OptimizationPhase.VALIDATING)
                if (id == self) {
                    completed++
                    event(OptimizationPhase.SKIPPED, "The optimizer cannot optimize itself")
                    continue
                }
                val path = shell.shell(SafeAdbCommand.PackagePath(id))
                if (!path.succeeded || !path.stdout.contains("package:")) {
                    completed++
                    event(OptimizationPhase.SKIPPED, "Package is not installed")
                    continue
                }
                event(OptimizationPhase.READING)
                val before = shell.shell(SafeAdbCommand.PackageDump(id))
                val (versionCode, _) = PackageOutputParser.version(before.stdout)
                event(OptimizationPhase.STOPPING)
                val stopped = shell.shell(SafeAdbCommand.ForceStop(id))
                if (!stopped.succeeded) {
                    completed++
                    event(OptimizationPhase.FAILED, "Force-stop failed: ${stopped.output.take(200)}")
                    continue
                }
                event(OptimizationPhase.COMPILING)
                val compiled = shell.shell(SafeAdbCommand.CompileSpeed(id))
                if (!compiled.succeeded || Regex("(?i)\\bFailure\\b").containsMatchIn(compiled.output)) {
                    completed++
                    event(OptimizationPhase.FAILED, "Compilation failed: ${compiled.output.take(400)}")
                    continue
                }
                event(OptimizationPhase.VERIFYING)
                val after = shell.shell(SafeAdbCommand.PackageDump(id))
                val (afterVersion, _) = PackageOutputParser.version(after.stdout)
                if (versionCode != null && afterVersion != null && versionCode != afterVersion) {
                    completed++
                    event(OptimizationPhase.FAILED, "Target updated during compilation")
                    continue
                }
                val art = try {
                    shell.shell(SafeAdbCommand.ArtDump(id))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
                val level = if (art?.succeeded == true && PackageOutputParser.artReportsSpeed(art.output))
                    ValidationLevel.ART_STATE else ValidationLevel.COMMAND
                val record = OptimizationRecord(id, afterVersion ?: versionCode, "speed", now(), "Success", level)
                completed++
                event(OptimizationPhase.COMPLETED, if (level == ValidationLevel.ART_STATE) "ART state verified" else "Command validated", record)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                completed++
                event(OptimizationPhase.FAILED, error.message?.take(200) ?: "Operation failed")
            }
        }
    }
}

object ConsoleCommand {
    fun normalize(raw: String): String {
        val trimmed = raw.trim()
        return if (trimmed.startsWith("adb shell ", ignoreCase = true)) trimmed.substring(10).trim() else trimmed
    }
}

object ConsoleHistory {
    fun add(existing: List<String>, command: String): List<String> {
        val bounded = command.take(500)
        return (listOf(bounded) + existing.filterNot { it == bounded }).take(20)
    }
}
