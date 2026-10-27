package ai.nanosearch.launcher.clocks

import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Test

/** Prevents fixture drift: undo identifier renames, then require the exact public 0.8.1 bytes. */
class CarbonBridgeBaselineProvenanceTest {
    @Test fun frozenRenderersAreExactIdentifierOnlyCopiesOfPublic081() {
        val sources = mapOf(
            "CarbonDialArt" to "fd235ae07c1b52e7e35199b02cd1da3f93848ee4c6b874e81e0c09446c01d124",
            "CarbonBridgeArt" to "b2ffbf5ef55462db46050f77c8c30cacc41a14dd984047b1730703d0f383fb67",
        )
        val identifiers = listOf("CarbonDialArt", "CarbonDialGeometry", "CarbonBridgeArt", "CarbonBridgeGeometry")
        for ((name, sha256) in sources) {
            var original = File("src/test/java/ai/nanosearch/launcher/clocks/Baseline081$name.kt").readText()
            identifiers.forEach { original = original.replace("Baseline081$it", it) }
            val actual = MessageDigest.getInstance("SHA-256").digest(original.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            assertEquals("Frozen $name must remain an exact public d515470 source copy", sha256, actual)
        }
    }
}
