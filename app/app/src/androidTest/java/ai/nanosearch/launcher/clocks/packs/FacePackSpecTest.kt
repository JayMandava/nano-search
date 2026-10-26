package ai.nanosearch.launcher.clocks.packs

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FacePackSpecTest {
    private fun parse(source: String) = FacePackSpec.parse(source.toByteArray(Charsets.UTF_8))
    private fun reject(label: String, source: String) {
        try { parse(source); fail("Accepted $label") } catch (_: FacePackException) { /* expected */ }
    }

    @Test fun originalAnalogFixtureAndNativeDateGearDataParseWithoutAssets() {
        val spec = parse(PackTestFixtures.manifestWith(""""date":{"x":650,"y":460,"width":100,"height":80,"textSize":50,"color":"#ffffff","background":"#000000"},"gears":[{"x":300,"y":300,"radius":80,"degreesPerSecond":-12,"color":"#708090","teeth":16}]"""))
        assertEquals("test-original", spec.id)
        assertEquals(FacePackHandShape.SWORD, spec.hour.shape)
        assertEquals(650f, requireNotNull(spec.date).x, 0f)
        assertEquals(-12f, spec.gears.single().degreesPerSecond, 0f)
        assertTrue(spec.assetNames().isEmpty())
    }

    @Test fun schemaRejectsUnknownDuplicateUnsupportedAndWrongTypedFields() {
        val original = PackTestFixtures.manifest
        listOf(
            "unknown field" to PackTestFixtures.manifestWith("\"script\":\"alert(1)\""),
            "duplicate key" to PackTestFixtures.manifestWith("\"version\":2"),
            "nested unknown" to original.replace("\"radius\":20", "\"script\":1,\"radius\":20"),
            "schema version" to original.replace("\"schemaVersion\":1", "\"schemaVersion\":2"),
            "unsupported template" to original.replace("analog-v1", "gmt-v1"),
            "numeric coercion" to original.replace("\"length\":240", "\"length\":\"240\""),
            "fractional version" to original.replace("\"version\":1", "\"version\":1.5"),
            "null optional" to PackTestFixtures.manifestWith("\"date\":null"),
            "trailing document" to "$original {}",
        ).forEach { (label, source) -> reject(label, source) }
    }

    @Test fun numericAndStringBoundsRejectUnsafeAndNonfiniteData() {
        val original = PackTestFixtures.manifest
        listOf("NaN", "Infinity", "1e999", "-1", "491").forEach { value ->
            reject("hand length $value", original.replace("\"length\":240", "\"length\":$value"))
        }
        reject("oversize name", original.replace("Original test dial", "x".repeat(65)))
        reject("control text", original.replace("Original test dial", "bad\\u0000name"))
        reject("oversize manifest", " ".repeat(65537))
        reject("date outside dial", PackTestFixtures.manifestWith(""""date":{"x":990,"y":460,"width":100,"height":80,"textSize":50,"color":"#ffffff","background":"#000000"}"""))
        reject("gear outside dial", PackTestFixtures.manifestWith(""""gears":[{"x":20,"y":300,"radius":80,"degreesPerSecond":1,"color":"#708090","teeth":16}]"""))
    }

    @Test fun assetReferencesAreFlatPngNamesAndBounded() {
        listOf("../a.png", "/a.png", "C:a.png", "a/b.png", "a\\\\b.png", "a.svg", "a.js").forEach { name ->
            reject("asset $name", PackTestFixtures.manifestWith("\"assets\":{\"base\":\"$name\"}"))
        }
        val gears = (1..9).joinToString(",") { """{"x":300,"y":300,"radius":80,"degreesPerSecond":1,"color":"#708090","teeth":16}""" }
        reject("nine gears", PackTestFixtures.manifestWith("\"gears\":[$gears]"))
    }

    @Test fun malformedUtf8AndDeepInputAreRejected() {
        try { FacePackSpec.parse(byteArrayOf(0xc3.toByte(), 0x28)); fail("Accepted malformed UTF-8") } catch (_: FacePackException) { }
        reject("excess nesting", "[".repeat(10) + "0" + "]".repeat(10))
        reject("duplicate nested field", PackTestFixtures.manifest.replace("\"radius\":20", "\"radius\":20,\"radius\":21"))
    }
}
