package io.github.daermond.artoptimizer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class PackageIdParserTest {
    @Test
    fun validPackageIdsAreAccepted() {
        assertNotNull(PackageId.parse("com.nuvio.tv"))
        assertNotNull(PackageId.parse("app.smarttube"))
        assertNotNull(PackageId.parse("io.github.example_app.fork2"))
    }

    @Test
    fun shellLikeInputIsRejected() {
        assertNull(PackageId.parse("com.nuvio.tv; rm -rf /"))
        assertNull(PackageId.parse("$(id)"))
        assertNull(PackageId.parse("com.nuvio.tv && id"))
    }

    @Test
    fun csvParsingTrimsAndDeduplicates() {
        val result = PackageIdParser.parseCsv(" com.nuvio.tv,app.smarttube, com.nuvio.tv ")
        assertEquals(listOf("com.nuvio.tv", "app.smarttube"), result.valid.map { it.value })
        assertEquals(emptyList<String>(), result.invalid)
    }

    @Test
    fun csvParsingReportsInvalidTokens() {
        val result = PackageIdParser.parseCsv("app.smarttube, bad package, com.good.app")
        assertEquals(listOf("app.smarttube", "com.good.app"), result.valid.map { it.value })
        assertEquals(listOf("bad package"), result.invalid)
    }
}
