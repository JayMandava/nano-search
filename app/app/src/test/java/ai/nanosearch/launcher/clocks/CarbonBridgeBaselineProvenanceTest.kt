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
    @Test fun frozenReviewRenderersAreExactIdentifierOnlyCopiesOfEd1d39c() {
        val sources = mapOf(
            "CarbonDialArt" to "fd235ae07c1b52e7e35199b02cd1da3f93848ee4c6b874e81e0c09446c01d124",
            "CarbonBridgeArt" to "041fe77b0d37212a06dffd1d9b145abd6dc403d8911c36d1893953f16470a934",
        )
        val identifiers = listOf("CarbonDialArt", "CarbonDialGeometry", "CarbonBridgeArt", "CarbonBridgeGeometry")
        for ((name, sha256) in sources) {
            var original = File("src/test/java/ai/nanosearch/launcher/clocks/BaselineReview$name.kt").readText()
            identifiers.forEach { original = original.replace("BaselineReview$it", it) }
            val actual = MessageDigest.getInstance("SHA-256").digest(original.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            assertEquals("Frozen $name must remain an exact ed1d39c source copy", sha256, actual)
        }
    }
    @Test fun frozenAlignedRenderersAreExactIdentifierOnlyCopiesOfA44fc7f() {
        val sources = mapOf(
            "CarbonDialArt" to "d007b7046c8bc9ada81ca62a8b6e54b995403f71532dea65b9d17bf54c241f7e",
            "CarbonBridgeArt" to "bef33fa0f10c4e195bd270339ed9dad4d80d39d2e5f16aea28cfc07e656cf46e",
            "CarbonRearSupportArt" to "3baa7dcdf33dfa65f54af7854d6cc7009b5b4878d69464440d36ef4df886a467",
        )
        val identifiers = listOf("CarbonDialArt", "CarbonDialGeometry", "CarbonBridgeArt", "CarbonBridgeGeometry", "CarbonRearSupportArt", "CarbonRearSupportGeometry")
        for ((name, sha256) in sources) {
            var original = File("src/test/java/ai/nanosearch/launcher/clocks/BaselineAligned$name.kt").readText()
            identifiers.forEach { original = original.replace("BaselineAligned$it", it) }
            val actual = MessageDigest.getInstance("SHA-256").digest(original.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            assertEquals("Frozen $name must remain an exact a44fc7f source copy", sha256, actual)
        }
    }
    @Test fun frozenApprovedRenderersAreExactIdentifierOnlyCopiesOf4058d21() {
        val sources=mapOf(
            "CarbonDialArt" to "d2a4e3b13c3d36156a9a81234c0b325ae44405465b3c479491b5068d4f331d6e",
            "CarbonBridgeArt" to "bef33fa0f10c4e195bd270339ed9dad4d80d39d2e5f16aea28cfc07e656cf46e",
            "CarbonRearSupportArt" to "a72ea5b0dc1023c7752bc0161bb5b96740485ec0860be9a1add44173f4d4445d",
        )
        val identifiers=listOf("CarbonDialArt","CarbonDialGeometry","CarbonBridgeArt","CarbonBridgeGeometry","CarbonRearSupportArt","CarbonRearSupportGeometry")
        for((name,sha256) in sources) {
            var original=File("src/test/java/ai/nanosearch/launcher/clocks/BaselineApproved$name.kt").readText()
            identifiers.forEach {original=original.replace("BaselineApproved$it",it)}
            val actual=MessageDigest.getInstance("SHA-256").digest(original.toByteArray(Charsets.UTF_8)).joinToString("") {"%02x".format(it)}
            assertEquals("Frozen $name must remain exact approved4058d21 source",sha256,actual)
        }
    }
}
