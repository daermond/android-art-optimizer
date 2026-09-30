package io.github.daermond.artoptimizer

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class OptimizerCoreTest {
    private val target = requireNotNull(PackageId.parse("app.smarttube"))
    private val self = requireNotNull(PackageId.parse("io.github.daermond.artoptimizer"))

    @Test fun builtInCommandsAreExactAndTyped() {
        assertEquals("pm list packages -3", SafeCommandRenderer.render(SafeAdbCommand.ListThirdPartyPackages))
        assertEquals("pm path app.smarttube", SafeCommandRenderer.render(SafeAdbCommand.PackagePath(target)))
        assertEquals("cmd package compile -m speed -f app.smarttube",
            SafeCommandRenderer.render(SafeAdbCommand.CompileSpeed(target)))
        assertEquals("pm art dump app.smarttube", SafeCommandRenderer.render(SafeAdbCommand.ArtDump(target)))
        assertNull(PackageId.parse("app.smarttube; id"))
    }

    @Test fun packageOutputToleratesOemNoise() {
        assertEquals(listOf("app.smarttube", "com.example.other"),
            PackageOutputParser.thirdPartyPackages("notice\npackage:app.smarttube\npackage:bad space\npackage:com.example.other\n")
                .map { it.value })
        assertEquals(42L to "1.2.3", PackageOutputParser.version("random\n versionCode=42 minSdk=30\n versionName=1.2.3\n"))
        assertEquals(null to null, PackageOutputParser.version("unknown OEM output"))
        assertTrue(PackageOutputParser.artReportsSpeed("status=speed"))
        assertTrue(PackageOutputParser.artReportsSpeed("arm64: [status=speed] [reason=cmdline]"))
        assertFalse(PackageOutputParser.artReportsSpeed("arm64: [status=speed-profile]"))
        assertFalse(PackageOutputParser.artReportsSpeed("arm64: [status=speed]\nsecondary: [status=verify]"))
        assertFalse(PackageOutputParser.artReportsSpeed("help: use speed to compile"))
    }

    @Test fun compileCapabilityAcceptsHelpUsageDespiteNonzeroHelpExit() {
        val tvHelp = ShellResult("Package manager (package) commands:\n  compile [-m COMPILER_FILTER] [-f] PACKAGE\n", "", 255)
        assertTrue(CapabilityProbe.supportsCompile(tvHelp))
        assertFalse(CapabilityProbe.supportsCompile(ShellResult("Error: unknown compile command", "", 255)))
        assertFalse(CapabilityProbe.supportsCompile(ShellResult("", "", 0)))
        assertFalse(CapabilityProbe.supportsCompile(null))
    }

    @Test fun versionChangeRequiresSuccessfulRecord() {
        val record = OptimizationRecord(target, 4L, "speed", 1L, "Success", ValidationLevel.COMMAND)
        assertFalse(updatedSinceOptimization(4L, record))
        assertTrue(updatedSinceOptimization(5L, record))
        assertFalse(updatedSinceOptimization(null, record))
        assertTrue(updatedSinceOptimization(5L, record.copy(result = "Failed")))
        val failure = terminalRecord(record, OptimizationEvent(target, OptimizationPhase.FAILED, 1, 1,
            "compile failed", atMillis = 10L))!!
        assertEquals(4L, failure.optimizedVersionCode)
        assertEquals(1L, failure.lastSuccessfulAtMillis)
        assertEquals(10L, failure.timestampMillis)
    }

    @Test fun successfulBatchReportsRealTerminalCountAndArtValidation() = runBlocking {
        val other = requireNotNull(PackageId.parse("com.example.other"))
        val shell = FakeShell(artOutput = "compiler-filter: speed")
        val events = ArtOptimizer(shell) { 123L }.optimize(listOf(target, other), self).toList()
        assertEquals(listOf(1, 2), events.filter { it.phase == OptimizationPhase.COMPLETED }.map { it.completed })
        assertEquals(ValidationLevel.ART_STATE, events.first { it.phase == OptimizationPhase.COMPLETED }.record?.validationLevel)
        assertTrue(events.any { it.phase == OptimizationPhase.COMPILING && it.message.isEmpty() })
        assertEquals(2, events.last().total)
        assertEquals(123L, events.last().record?.timestampMillis)
    }

    @Test fun unavailableArtStateDoesNotBecomeVerified() = runBlocking {
        val shell = FakeShell(artOutput = "unsupported")
        val events = ArtOptimizer(shell).optimize(listOf(target), self).toList()
        assertEquals(ValidationLevel.COMMAND, events.last().record?.validationLevel)
        assertEquals("Command validated", events.last().message)
    }

    @Test fun missingAndCompileFailureAreTerminalWithoutFalseSuccess() = runBlocking {
        val missing = ArtOptimizer(FakeShell(missing = true)).optimize(listOf(target), self).toList()
        assertEquals(OptimizationPhase.SKIPPED, missing.last().phase)
        assertEquals(1, missing.last().completed)
        val failed = ArtOptimizer(FakeShell(compileFails = true)).optimize(listOf(target), self).toList()
        assertEquals(OptimizationPhase.FAILED, failed.last().phase)
        assertNull(failed.last().record)
    }

    @Test fun optimizerNeverCompilesItself() = runBlocking {
        val shell = FakeShell()
        val events = ArtOptimizer(shell).optimize(listOf(self), self).toList()
        assertEquals(OptimizationPhase.SKIPPED, events.last().phase)
        assertTrue(shell.commands.isEmpty())
    }

    @Test fun lostConnectionStopsBatchAndNeverReplaysAnInterruptedCompile() = runBlocking {
        val other = requireNotNull(PackageId.parse("com.example.other"))
        val commands = mutableListOf<SafeAdbCommand>()
        val shell = object : SafeShell {
            override suspend fun shell(command: SafeAdbCommand): ShellResult {
                commands += command
                if (command is SafeAdbCommand.CompileSpeed) throw AdbConnectionException("TLS failure")
                return FakeShell().shell(command)
            }
        }
        val events = ArtOptimizer(shell).optimize(listOf(target, other), self).toList()
        assertEquals(OptimizationPhase.FAILED, events.last().phase)
        assertTrue(events.last().connectionLost)
        assertTrue(events.last().message.contains("result unknown"))
        assertEquals(1, events.last().completed)
        assertEquals(2, events.last().total)
        assertEquals(1, commands.count { it is SafeAdbCommand.CompileSpeed })
        assertFalse(events.any { it.packageId == other })
        assertNull(events.last().record)
    }

    @Test fun consolePrefixOnlyChangesExplicitConsoleInput() {
        assertEquals("pm list packages -3", ConsoleCommand.normalize("adb shell pm list packages -3"))
        assertEquals("pm list packages -3", ConsoleCommand.normalize("pm list packages -3"))
        assertEquals("adb push x y", ConsoleCommand.normalize("adb push x y"))
    }

    @Test fun consoleHistoryIsBoundedAndDeduplicated() {
        val commands = (1..40).fold(emptyList<String>()) { previous, number ->
            ConsoleHistory.add(previous, "echo $number")
        }
        assertEquals(20, commands.size)
        assertEquals("echo 40", commands.first())
        assertEquals("echo 21", commands.last())
        assertEquals(20, ConsoleHistory.add(commands, "echo 25").size)
        assertEquals("echo 25", ConsoleHistory.add(commands, "echo 25").first())
        assertEquals(500, ConsoleHistory.add(emptyList(), "x".repeat(1000)).first().length)
    }

    @Test fun deviceIdentityPrefersStableIdentifierAndFallsBackWithLowerConfidence() {
        val a = DeviceProfile("Acme", "TV", "13", "33", "device-A")
        val b = a.copy(stableId = "device-B")
        assertFalse(a.matches(b))
        assertTrue(a.matches(a.copy()))
        assertTrue(a.copy(stableId = null).matches(b.copy(stableId = null)))
        assertFalse(a.copy(stableId = null).matches(b.copy(stableId = null, model = "Phone")))
    }

    private class FakeShell(
        private val missing: Boolean = false,
        private val compileFails: Boolean = false,
        private val artOutput: String = "unsupported",
    ) : SafeShell {
        val commands = mutableListOf<SafeAdbCommand>()
        override suspend fun shell(command: SafeAdbCommand): ShellResult {
            commands += command
            return when (command) {
                is SafeAdbCommand.PackagePath -> if (missing) ShellResult("", "not found", 1)
                    else ShellResult("package:/data/app/base.apk", "", 0)
                is SafeAdbCommand.PackageDump -> ShellResult("versionCode=3 versionName=1.0", "", 0)
                is SafeAdbCommand.CompileSpeed -> if (compileFails) ShellResult("Failure", "", 1)
                    else ShellResult("Success", "", 0)
                is SafeAdbCommand.ArtDump -> ShellResult(artOutput, "", if (artOutput == "unsupported") 1 else 0)
                else -> ShellResult("", "", 0)
            }
        }
    }
}
