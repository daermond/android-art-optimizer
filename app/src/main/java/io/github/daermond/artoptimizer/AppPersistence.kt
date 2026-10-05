package io.github.daermond.artoptimizer

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class AppPersistence(context: Context) {
    private val preferences = context.getSharedPreferences("art-optimizer", Context.MODE_PRIVATE)

    var paired: Boolean
        get() = preferences.getBoolean("paired", false)
        set(value) { check(preferences.edit().putBoolean("paired", value).commit()) }

    var consoleWarningAccepted: Boolean
        get() = preferences.getBoolean("console_warning", false)
        set(value) { preferences.edit().putBoolean("console_warning", value).apply() }

    fun packages(): List<PackageId> = readArray("packages")
        .mapNotNull { PackageId.parse(it) }
        .distinct()

    fun savePackages(ids: List<PackageId>) = writeArray("packages", ids.distinct().map { it.value })

    fun profile(): DeviceProfile? = runCatching {
        val raw = preferences.getString("device_profile", null) ?: return null
        val objectValue = JSONObject(raw)
        DeviceProfile(
            objectValue.getString("manufacturer"),
            objectValue.getString("model"),
            objectValue.getString("release"),
            objectValue.getString("sdk"),
            objectValue.optString("stableId").takeIf { it.isNotBlank() },
        )
    }.getOrNull()

    fun saveProfile(profile: DeviceProfile?) {
        if (profile == null) {
            check(preferences.edit().remove("device_profile").commit())
            return
        }
        val data = JSONObject()
            .put("manufacturer", profile.manufacturer)
            .put("model", profile.model)
            .put("release", profile.release)
            .put("sdk", profile.sdk)
            .put("stableId", profile.stableId ?: "")
        check(preferences.edit().putString("device_profile", data.toString()).commit())
    }

    fun records(): Map<PackageId, OptimizationRecord> = runCatching {
        val raw = preferences.getString("records", "[]") ?: "[]"
        val array = JSONArray(raw)
        buildMap {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                val id = PackageId.parse(item.getString("packageId")) ?: continue
                val version = item.optLong("version", -1).takeIf { it >= 0 }
                val level = runCatching { ValidationLevel.valueOf(item.getString("validation")) }
                    .getOrDefault(ValidationLevel.COMMAND)
                val result = item.optString("result")
                val time = item.optLong("time")
                put(id, OptimizationRecord(id, version, "speed", time, result, level,
                    item.optLong("successTime", -1).takeIf { it >= 0 }
                        ?: time.takeIf { result == "Success" }))
            }
        }
    }.getOrDefault(emptyMap())

    fun saveRecords(records: Map<PackageId, OptimizationRecord>) {
        val array = JSONArray()
        records.values.forEach { record ->
            array.put(JSONObject()
                .put("packageId", record.packageId.value)
                .put("version", record.optimizedVersionCode ?: -1L)
                .put("time", record.timestampMillis)
                .put("successTime", record.lastSuccessfulAtMillis ?: -1L)
                .put("result", record.result)
                .put("validation", record.validationLevel.name))
        }
        preferences.edit().putString("records", array.toString()).apply()
    }

    fun seenVersions(): Map<PackageId, InstalledPackage> = runCatching {
        val array = JSONArray(preferences.getString("seen_versions", "[]") ?: "[]")
        buildMap {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                val id = PackageId.parse(item.getString("packageId")) ?: continue
                put(id, InstalledPackage(id, item.optLong("version", -1).takeIf { it >= 0 },
                    item.optString("name").takeIf(String::isNotBlank)))
            }
        }
    }.getOrDefault(emptyMap())

    fun saveSeenVersions(versions: Map<PackageId, InstalledPackage>) {
        val array = JSONArray()
        versions.values.forEach { version ->
            array.put(JSONObject().put("packageId", version.id.value)
                .put("version", version.versionCode ?: -1L)
                .put("name", version.versionName ?: ""))
        }
        preferences.edit().putString("seen_versions", array.toString()).apply()
    }

    fun history(): List<String> = readArray("console_history").take(20)

    fun addHistory(command: String) {
        writeArray("console_history", ConsoleHistory.add(history(), command))
    }

    fun clearHistory() = preferences.edit().remove("console_history").apply()

    private fun readArray(key: String): List<String> = runCatching {
        val array = JSONArray(preferences.getString(key, "[]") ?: "[]")
        List(array.length()) { array.getString(it) }
    }.getOrDefault(emptyList())

    private fun writeArray(key: String, items: List<String>) {
        val array = JSONArray()
        items.forEach(array::put)
        preferences.edit().putString(key, array.toString()).apply()
    }
}
