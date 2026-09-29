package io.github.daermond.artoptimizer

@JvmInline
value class PackageId private constructor(val value: String) {
    companion object {
        // Conservative Android-style package/application ID validation for shell safety.
        private val pattern = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")

        fun parse(raw: String): PackageId? {
            val trimmed = raw.trim()
            return trimmed.takeIf { pattern.matches(it) }?.let(::PackageId)
        }
    }
}

data class PackageParseResult(
    val valid: List<PackageId>,
    val invalid: List<String>,
)

object PackageIdParser {
    fun parseCsv(raw: String): PackageParseResult {
        val valid = linkedMapOf<String, PackageId>()
        val invalid = mutableListOf<String>()

        raw.split(',')
            .map(String::trim)
            .filter(String::isNotEmpty)
            .forEach { token ->
                val packageId = PackageId.parse(token)
                if (packageId == null) invalid += token else valid.putIfAbsent(packageId.value, packageId)
            }

        return PackageParseResult(valid.values.toList(), invalid)
    }
}
