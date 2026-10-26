package ai.nanosearch.launcher.clocks.packs

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.InterruptedIOException
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Runs production archive validation and the real Android bitmap decoder, never android.jar stubs. */
@RunWith(AndroidJUnit4::class)
class FacePackArchiveAndroidTest {
    private val root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "pack-archive-${UUID.randomUUID()}").canonicalFile.apply { mkdir() }
    @After fun clean() { root.deleteRecursively() }

    private fun pack(png: ByteArray): Pair<File, ValidatedFacePack> {
        val hash = MessageDigest.getInstance("SHA-256").digest(png).joinToString("") { "%02x".format(it) }
        val manifest = PackTestFixtures.manifest
            .replace("\"requires\":[\"analog-v1\"]", "\"requires\":[\"analog-v1\",\"png-v1\"]")
            .replace("\"font\":\"system-sans\"", "\"font\":\"system-sans\",\"textureAsset\":\"texture\"")
            .replace("\"assets\":[]", "\"assets\":[{\"key\":\"texture\",\"path\":\"assets/texture.png\",\"sha256\":\"$hash\"}]")
        val source = File(root, "source-${UUID.randomUUID()}.zip").apply { writeBytes(PackTestFixtures.zip(listOf(
            PackTestFixtures.Entry("manifest.json", manifest.toByteArray()),
            PackTestFixtures.Entry("LICENSE.txt", "Original fixture, CC0-1.0".toByteArray()),
            PackTestFixtures.Entry("assets/texture.png", png),
        ))) }
        return source to FacePackArchive.validateAndExtract(source, File(root, "payload-${UUID.randomUUID()}"), 14)
    }

    @Test fun platformGeneratedPngPassesBoundsFullDecodeAndReadOnlyRecovery() {
        val (source, checked) = pack(PackTestFixtures.png(128, 96))
        assertEquals(128L * 96 * 4, checked.decodedBytes)
        FacePackArchive.validateAndroidImages(checked)
        val recovered = FacePackArchive.validateInstalled(source, checked.directory, 14, checked.digest)
        FacePackArchive.validateAndroidImages(recovered)
        assertEquals(checked.digest, recovered.digest)
        assertEquals(checked.payloadBytes, recovered.payloadBytes)
    }
    @Test fun nativeDecodeRejectsAnAssetChangedAfterPortableValidation() {
        val (_, checked) = pack(PackTestFixtures.png())
        File(checked.directory, "assets/texture.png").writeBytes(PackTestFixtures.png(33, 32))
        try { FacePackArchive.validateAndroidImages(checked); fail("Decoded changed asset") }
        catch (e: FacePackException) { assertTrue(e.message!!.contains("changed")) }
    }
    @Test fun animatedPngCannotReachNativeDecoderOrBecomeVisible() {
        try { pack(PackTestFixtures.apng(PackTestFixtures.png())); fail("Accepted APNG") }
        catch (e: FacePackException) { assertTrue(e.message!!.contains("unsupported")) }
        assertFalse(root.listFiles()!!.any { it.name.startsWith("payload-") })
    }
    @Test fun maximumSizePlatformBitmapStaysWithinDeclaredAllocationBudget() {
        val (_, checked) = pack(PackTestFixtures.png(1024, 1024))
        FacePackArchive.validateAndroidImages(checked)
        assertEquals(4L * 1024 * 1024, checked.decodedBytes)
    }
    private fun rawEntries() = listOf(
        PackTestFixtures.Entry("manifest.json", PackTestFixtures.manifest.toByteArray()),
        PackTestFixtures.Entry("LICENSE.txt", "Original fixture, CC0-1.0".toByteArray()),
    )
    private fun rejectedArchive(bytes: ByteArray): FacePackException {
        val source = File(root, "bad-${UUID.randomUUID()}.zip").apply { writeBytes(bytes) }
        val destination = File(root, "payload-${UUID.randomUUID()}")
        try { FacePackArchive.validateAndExtract(source, destination, 14); fail("Accepted unsafe archive") }
        catch (e: FacePackException) { assertFalse(destination.exists()); return e }
        throw AssertionError("Unreachable")
    }
    @Test fun originalBundledFixturePassesProductionValidationAndActualDecoder() {
        val source = File(root, "orbit-blue.zip").apply {
            writeBytes(InstrumentationRegistry.getInstrumentation().context.assets.open("clock-packs/orbit-blue.zip").use { it.readBytes() })
        }
        val pack = FacePackArchive.validateAndExtract(source, File(root, "orbit-blue"), 14)
        assertEquals("f12204deb3d998843c77048be0f7e5803307246d49bba047aa017e8ac1e0363f", pack.digest)
        assertEquals("orbit-blue", pack.spec.packId)
        assertEquals(16384L, pack.decodedBytes)
        FacePackArchive.validateAndroidImages(pack)
    }
    @Test fun androidZipMetadataRejectsLinksTraversalAliasesAndLocalMismatch() {
        for (path in listOf("../manifest.json", "/manifest.json", "assets/../manifest.json", "Manifest.json", "assets/a\\b.png"))
            rejectedArchive(PackTestFixtures.zip(listOf(PackTestFixtures.Entry(path, byteArrayOf(1)), rawEntries().last())))
        for (mode in listOf(0xa1ff, 0x21ff, 0x11ff))
            rejectedArchive(PackTestFixtures.zip(rawEntries().map { it.copy(unixMode = mode) }))
        rejectedArchive(PackTestFixtures.zip(rawEntries().map { it.copy(localName = "LICENSE.txt") }))
        rejectedArchive(PackTestFixtures.zip(rawEntries() + rawEntries().first()))
    }
    @Test fun androidZipRejectsEncryptionCrcTruncationAndExpansionBombs() {
        rejectedArchive(PackTestFixtures.zip(rawEntries().map { it.copy(flags = 1) }))
        rejectedArchive(PackTestFixtures.zip(rawEntries().map { it.copy(crc = it.crc xor 1) }))
        rejectedArchive(PackTestFixtures.zip(rawEntries(), disk = 1))
        val valid = PackTestFixtures.zip(rawEntries())
        rejectedArchive(valid.copyOf(valid.size - 1))
        rejectedArchive(valid + byteArrayOf(0))
        rejectedArchive(PackTestFixtures.zip(rawEntries() + PackTestFixtures.Entry("assets/bomb.png", ByteArray(300_000), method = 8)))
        rejectedArchive(PackTestFixtures.zip(listOf(rawEntries().first(), PackTestFixtures.Entry("LICENSE.txt", ByteArray(FacePackLimits.MAX_LICENSE_BYTES + 1)))))
    }
    @Test fun unsupportedVersionIsTypedIncompatibleNotGenericInvalid() {
        val entries = rawEntries()
        val incompatible = entries.first().bytes.toString(Charsets.UTF_8).replace("\"minAppVersionCode\":14", "\"minAppVersionCode\":15")
        assertTrue(rejectedArchive(PackTestFixtures.zip(listOf(PackTestFixtures.Entry("manifest.json", incompatible.toByteArray()), entries.last()))).incompatible)
        assertFalse(rejectedArchive(PackTestFixtures.zip(entries).copyOf(20)).incompatible)
    }
    @Test fun malformedOrOversizedPngNeverCreatesPublishedPayload() {
        for (png in listOf(PackTestFixtures.png(1025, 1), PackTestFixtures.png().dropLast(1).toByteArray(), byteArrayOf(0x7f, 69, 76, 70))) {
            try { pack(png); fail("Accepted invalid PNG") } catch (_: FacePackException) { }
        }
        assertFalse(root.listFiles()!!.any { it.name.startsWith("payload-") })
    }
    @Test fun cancellationDuringExtractionRemovesOnlyItsOwnStaging() {
        val source = File(root, "source.zip").apply { writeBytes(PackTestFixtures.zip(rawEntries())) }
        val destination = File(root, "cancelled-payload")
        val keep = File(root, "keep.txt").apply { writeText("keep") }
        try {
            FacePackArchive.validateAndExtract(source, destination, 14) { destination.exists() }
            fail("Ignored extraction cancellation")
        } catch (_: InterruptedIOException) { }
        assertFalse(destination.exists()); assertEquals("keep", keep.readText())
    }
    @Test fun changedInstalledManifestAndUnexpectedPayloadAreRejected() {
        val (source, checked) = pack(PackTestFixtures.png())
        val manifest = File(checked.directory, "manifest.json")
        val original = manifest.readBytes()
        manifest.appendText(" ")
        try { FacePackArchive.validateInstalled(source, checked.directory, 14, checked.digest); fail("Accepted changed manifest") }
        catch (_: FacePackException) { }
        manifest.writeBytes(original)
        File(checked.directory, "unexpected.bin").writeText("not declared")
        try { FacePackArchive.validateInstalled(source, checked.directory, 14, checked.digest); fail("Accepted extra payload") }
        catch (_: FacePackException) { }
    }
    @Test fun nativeDecodeRejectsAnAssetParentReplacedWithSymlink() {
        val (_, checked) = pack(PackTestFixtures.png())
        val assets = File(checked.directory, "assets").toPath()
        val moved = File(root, "moved-assets").toPath()
        Files.move(assets, moved)
        Files.createSymbolicLink(assets, moved)
        try {
            try { FacePackArchive.validateAndroidImages(checked); fail("Followed an asset directory symlink") }
            catch (e: FacePackException) { assertTrue(e.message!!.contains("links")) }
        } finally { Files.delete(assets); Files.move(moved, assets) }
    }

}
