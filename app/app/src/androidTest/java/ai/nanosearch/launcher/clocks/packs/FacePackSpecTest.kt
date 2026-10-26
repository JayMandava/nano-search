package ai.nanosearch.launcher.clocks.packs

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Android parity smoke; the full malicious parser matrix also runs against this same code on the JVM. */
@RunWith(AndroidJUnit4::class)
class FacePackSpecTest {
    private fun parse(source: String) = FacePackSpec.parse(source.toByteArray(Charsets.UTF_8))
    private fun reject(source: String) {
        try { parse(source); fail("Unsafe manifest accepted") } catch (_: FacePackException) { }
    }

    @Test fun strictNativeAnalogDataParsesOnAndroid() {
        val spec = parse(PackTestFixtures.manifestWith(""""dateWindow":{"position":"right","textColor":"#FFFFFFFF","backgroundColor":"#FF000000"}"""))
        assertEquals("test-original", spec.packId)
        assertEquals(FacePackHandShape.NEEDLE, spec.hands.hour.shape)
        assertEquals(0.24f, spec.hands.hour.length, 0f)
        assertEquals(FacePackDatePosition.RIGHT, spec.dateWindow?.position)
        assertTrue(spec.assetNames().isEmpty())
    }

    @Test fun oldMechanicsAndUnknownExecutableFieldsAreNotAccepted() {
        for (key in listOf("gears", "script", "pivot", "palette", "class", "intent")) reject(PackTestFixtures.manifestWith("\"$key\":[]"))
        reject(PackTestFixtures.manifest.replace("analog-v1", "gmt-v1"))
        reject(PackTestFixtures.manifestWith("\"dateWindow\":null"))
    }

    @Test fun duplicateMalformedUtf8AndUnpairedSurrogatesFailClosed() {
        reject(PackTestFixtures.manifestWith("\"packVersion\":2"))
        reject(PackTestFixtures.manifest.replace("Original test dial", "\\uD800"))
        reject(PackTestFixtures.manifest + " {}")
        try { FacePackSpec.parse(byteArrayOf(0xc3.toByte(), 0x28)); fail("Malformed UTF-8 accepted") } catch (_: FacePackException) { }
    }

    @Test fun normalizedGeometryAndExactArgbAreEnforced() {
        for (value in listOf("NaN", "1e999", "-0.01", "0.480000000000000001"))
            reject(PackTestFixtures.manifest.replace("\"length\":0.24", "\"length\":$value"))
        reject(PackTestFixtures.manifest.replace("#FF101820", "#101820"))
    }
}
