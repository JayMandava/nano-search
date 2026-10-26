package ai.nanosearch.launcher.clocks.packs

import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import ai.nanosearch.launcher.clocks.packs.ArchiveTestFixtures.Entry

class FacePackArchiveTest {
    private val root = Files.createTempDirectory("nano-pack-archive-").toFile().canonicalFile
    private var serial = 0
    @After fun clean() { root.deleteRecursively() }
    private fun input(bytes: ByteArray) = File(root, "input-${serial++}.zip").apply { writeBytes(bytes) }
    private fun accept(bytes: ByteArray, appVersion: Int = 14): ValidatedFacePack = FacePackArchive.validateAndExtract(input(bytes), File(root, "stage-${serial++}"), appVersion)
    private fun reject(bytes: ByteArray, message: String? = null) {
        val destination = File(root, "rejected-${serial++}")
        val sentinel = File(root, "sentinel").apply { writeText("unchanged") }
        try { FacePackArchive.validateAndExtract(input(bytes), destination, 14); fail("Accepted malformed fixture") }
        catch (e: FacePackException) { if (message != null) assertTrue("Expected '$message', got '${e.message}'", e.message!!.contains(message, ignoreCase = true)) }
        assertFalse("Rejected archive must not leave staging", destination.exists())
        assertEquals("unchanged", sentinel.readText())
    }

    @Test fun storedDeflatedAndDescriptorArchivesRoundTripWithExactDigest() {
        for (method in listOf(0, 8)) for (descriptor in listOf(false, true)) for (signed in listOf(false, true)) {
            val bytes = ArchiveTestFixtures.zip(ArchiveTestFixtures.entries().map { it.copy(method = method, descriptor = descriptor, signedDescriptor = signed) })
            val pack = accept(bytes)
            assertEquals("test-original", pack.spec.packId); assertEquals(ArchiveTestFixtures.sha256(bytes), pack.digest)
            assertEquals(ArchiveTestFixtures.license.toString(Charsets.UTF_8), pack.licenseText)
            assertEquals(0L, pack.decodedBytes)
            assertEquals(pack.directory.walkTopDown().filter { it.isFile }.sumOf { it.length() }, pack.payloadBytes)
        }
    }
    @Test fun normalJdkZipOutputStreamArchiveWithAssetDirectoryIsSupported() {
        val source = File(root, "jdk.zip")
        ZipOutputStream(source.outputStream()).use { zip ->
            (ArchiveTestFixtures.entries() + Entry("assets/", byteArrayOf())).forEach { entry ->
                zip.putNextEntry(ZipEntry(entry.name)); zip.write(entry.bytes); zip.closeEntry()
            }
        }
        val pack = FacePackArchive.validateAndExtract(source, File(root, "jdk-payload"), 14)
        assertTrue(File(pack.directory, "assets").isDirectory)
    }
    @Test fun originalStaticPngIsHashCheckedFullyValidatedAndCounted() {
        val png = ArchiveTestFixtures.png(32, 16, beforeData = ArchiveTestFixtures.chunk("sBIT", byteArrayOf(8, 8, 8, 8)))
        val pack = accept(ArchiveTestFixtures.zip(ArchiveTestFixtures.pngEntries(png)))
        assertEquals(32L * 16 * 4, pack.decodedBytes)
        assertArrayEquals(png, File(pack.directory, "assets/texture.png").readBytes())
        assertEquals(ArchiveTestFixtures.sha256(png), pack.spec.assets.single().sha256)
    }
    @Test fun traversalAliasesUnexpectedTypesAndUnsafeNamesNeverCreateFiles() {
        for (name in listOf("../sentinel", "/manifest.json", "C:\\evil", "\\\\server\\file", "assets/../manifest.json", "assets/%2e%2e.png", "assets/a\\b.png", "assets//a.png", "assets/./a.png", "assets/Α.png", "assets/a\u0000.png", "font.ttf", "assets/a.svg", "code.dex", "assets/A.png")) {
            reject(ArchiveTestFixtures.zip(ArchiveTestFixtures.entries() + Entry(name, byteArrayOf(1))), if (name.any { it.code !in 0x20..0x7e }) "ASCII" else "path")
        }
    }
    @Test fun duplicateAndCaseCollidingEntriesAreRejected() {
        reject(ArchiveTestFixtures.zip(ArchiveTestFixtures.entries() + ArchiveTestFixtures.entries().first()), "Duplicate")
        reject(ArchiveTestFixtures.zip(ArchiveTestFixtures.entries() + Entry("Manifest.json", byteArrayOf(1))))
        reject(ArchiveTestFixtures.zip(ArchiveTestFixtures.entries() + Entry("assets", byteArrayOf(1))))
    }
    @Test fun unixLinksHardlinkMetadataSpecialFilesAndUnknownPlatformsAreRejected() {
        for (mode in listOf(0xa1ff, 0x21ff, 0x11ff, 0x61ff, 0x41ff)) {
            reject(ArchiveTestFixtures.zip(ArchiveTestFixtures.entries().mapIndexed { i, e -> if (i == 0) e.copy(unixMode = mode) else e }), "links")
        }
        for (id in listOf(0x000d, 0x756e, 0x7075, 0x0001, 0xffff)) {
            reject(ArchiveTestFixtures.zip(ArchiveTestFixtures.entries().mapIndexed { i, e -> if (i == 0) e.copy(centralExtra = ArchiveTestFixtures.extra(id)) else e }), "extra")
        }
        reject(ArchiveTestFixtures.zip(ArchiveTestFixtures.entries().map { it.copy(platform = 10) }), "Unsupported")
        reject(ArchiveTestFixtures.zip(ArchiveTestFixtures.entries().map { it.copy(platform = 0, unixMode = 0xa1ff) }), "Ambiguous")
    }
    @Test fun malformedLocalNamesFlagsSizesAndCrcAreRejected() {
        val first = ArchiveTestFixtures.entries().first(); val second = ArchiveTestFixtures.entries().last()
        for (bad in listOf(first.copy(localName = "LICENSE.txt"), first.copy(localFlags = 8), first.copy(localSize = first.bytes.size + 1L))) {
            reject(ArchiveTestFixtures.zip(listOf(bad, second)), "local")
        }
        reject(ArchiveTestFixtures.zip(listOf(first.copy(crc = first.crc xor 1), second)), "CRC")
    }
    @Test fun localCentralCompressionDescriptorAndTrailingDataMustAgree() {
        val normal = ArchiveTestFixtures.zip(ArchiveTestFixtures.entries())
        val central = ArchiveTestFixtures.centralOffsets(normal).first()
        reject(normal.copyOf().apply { ArchiveTestFixtures.set16(this, central + 10, 8) }, "local")
        val descriptor = ArchiveTestFixtures.zip(ArchiveTestFixtures.entries().map { it.copy(method = 8, descriptor = true) })
        val firstCentral = ArchiveTestFixtures.centralOffsets(descriptor).first()
        val firstEnd = 30 + "manifest.json".length + ArchiveTestFixtures.read32(descriptor, firstCentral + 20).toInt()
        reject(descriptor.copyOf().apply { this[firstEnd + 4] = (this[firstEnd + 4].toInt() xor 1).toByte() }, "descriptor")
        reject(ArchiveTestFixtures.zip(ArchiveTestFixtures.entries().map { it.copy(method = 8, compressedSuffix = byteArrayOf(1)) }), "Trailing")
        reject(normal + byteArrayOf(0), "comments")
    }
    @Test fun overlappingLocalRecordsAreDetectedBeforeManifestParsing() {
        val license = ArchiveTestFixtures.zip(listOf(Entry("LICENSE.txt", ArchiveTestFixtures.license)))
        val nestedLocal = license.copyOfRange(0, ArchiveTestFixtures.centralOffsets(license).first())
        val prefix = PackSpecFixtures.manifest.toByteArray()
        val bytes = ArchiveTestFixtures.zip(listOf(Entry("manifest.json", prefix + nestedLocal), Entry("LICENSE.txt", ArchiveTestFixtures.license)))
        val secondCentral = ArchiveTestFixtures.centralOffsets(bytes)[1]
        ArchiveTestFixtures.set32(bytes, secondCentral + 42, (30 + "manifest.json".length + prefix.size).toLong())
        reject(bytes, "overlap")
    }
    @Test fun hiddenPaddingAndDuplicateOffsetsCannotHideUnindexedContent() {
        val original = ArchiveTestFixtures.zip(ArchiveTestFixtures.entries())
        val padded = byteArrayOf(0) + original
        for (offset in ArchiveTestFixtures.centralOffsets(original)) {
            ArchiveTestFixtures.set32(padded, offset + 1 + 42, ArchiveTestFixtures.read32(original, offset + 42) + 1)
        }
        ArchiveTestFixtures.set32(padded, padded.size - 6, ArchiveTestFixtures.read32(original, original.size - 6) + 1)
        reject(padded, "padding")
        val duplicate = original.copyOf(); ArchiveTestFixtures.set32(duplicate, ArchiveTestFixtures.centralOffsets(duplicate)[1] + 42, 0)
        reject(duplicate)
    }
    @Test fun encryptedMultidiskZip64UnsupportedMethodAndEntryCountFailClosed() {
        for (flags in listOf(1, 0x40, 0x2000, 0x10)) reject(ArchiveTestFixtures.zip(ArchiveTestFixtures.entries().map { it.copy(flags = flags) }), "flags")
        reject(ArchiveTestFixtures.zip(ArchiveTestFixtures.entries(), disk = 1), "Multidisk")
        reject(ArchiveTestFixtures.zip(ArchiveTestFixtures.entries().map { it.copy(method = 12) }), "Unsupported")
        reject(ArchiveTestFixtures.zip(ArchiveTestFixtures.entries()).apply { ArchiveTestFixtures.set16(this, size - 12, 65535) }, "count")
        reject(ArchiveTestFixtures.zip(ArchiveTestFixtures.entries() + (1..15).map { Entry("assets/a$it.png", byteArrayOf()) }), "count")
        reject(ArchiveTestFixtures.zip(ArchiveTestFixtures.entries().map { it.copy(declaredSize = 0xffffffffL) }), "bounds")
    }
    @Test fun truncatedHeadersAndCentralDirectoryAreRejected() {
        val bytes = ArchiveTestFixtures.zip(ArchiveTestFixtures.entries())
        for (length in listOf(0, 10, 22, bytes.size - 1, bytes.size - 12)) reject(bytes.copyOf(length))
        reject(bytes.copyOf().apply { ArchiveTestFixtures.set32(this, size - 6, 0xffffffffL) }, "bounds")
    }
    @Test fun sizeCapsAndActualInflationCountersPreventBombs() {
        reject(ByteArray((FacePackLimits.MAX_ZIP_BYTES + 1).toInt()), "size limit")
        reject(ArchiveTestFixtures.zip(listOf(Entry("manifest.json", ByteArray(FacePackLimits.MAX_MANIFEST_BYTES + 1)), Entry("LICENSE.txt", ArchiveTestFixtures.license))), "budget")
        reject(ArchiveTestFixtures.zip(listOf(Entry("manifest.json", PackSpecFixtures.manifest.toByteArray()), Entry("LICENSE.txt", ByteArray(FacePackLimits.MAX_LICENSE_BYTES + 1)))), "budget")
        val bomb = Entry("assets/bomb.png", ByteArray(300_000), method = 8)
        reject(ArchiveTestFixtures.zip(ArchiveTestFixtures.entries() + bomb), "budget")
        val lying = bomb.copy(declaredSize = 100, localSize = 100)
        reject(ArchiveTestFixtures.zip(ArchiveTestFixtures.entries() + lying), "budget")
    }
    @Test fun manifestAndLicenseExactByteCapsAreAcceptedButPlusOneFails() {
        val manifest = PackSpecFixtures.manifest.padEnd(FacePackLimits.MAX_MANIFEST_BYTES, ' ')
        val license = ByteArray(FacePackLimits.MAX_LICENSE_BYTES) { 'L'.code.toByte() }
        val pack = accept(ArchiveTestFixtures.zip(listOf(Entry("manifest.json", manifest.toByteArray()), Entry("LICENSE.txt", license))))
        assertEquals((manifest.length + license.size).toLong(), pack.payloadBytes)
        reject(ArchiveTestFixtures.zip(listOf(Entry("manifest.json", (manifest + " ").toByteArray()), Entry("LICENSE.txt", license))), "budget")
    }
    @Test fun declarationDigestLicenseAndApplicationCompatibilityAreRequired() {
        reject(ArchiveTestFixtures.zip(listOf(Entry("manifest.json", PackSpecFixtures.manifest.toByteArray()), Entry("assets/undeclared.png", ArchiveTestFixtures.png()))), "license")
        reject(ArchiveTestFixtures.zip(ArchiveTestFixtures.entries() + Entry("assets/undeclared.png", ArchiveTestFixtures.png())), "undeclared")
        reject(ArchiveTestFixtures.zip(ArchiveTestFixtures.pngEntries(hash = "0".repeat(64))), "checksum")
        reject(ArchiveTestFixtures.zip(ArchiveTestFixtures.entries(PackSpecFixtures.texture())), "missing")
        reject(ArchiveTestFixtures.zip(listOf(ArchiveTestFixtures.entries().first(), Entry("LICENSE.txt", byteArrayOf(0xc3.toByte(), 0x28)))), "UTF-8")
        reject(ArchiveTestFixtures.zip(listOf(ArchiveTestFixtures.entries().first(), Entry("LICENSE.txt", byteArrayOf()))), "license")
        try { accept(ArchiveTestFixtures.zip(ArchiveTestFixtures.entries()), 13); fail("Accepted incompatible app version") } catch (e: FacePackException) { assertTrue(e.message!!.contains("newer")) }
    }
    @Test fun existingDestinationAndSymlinkPathsAreNeverReusedOrDeleted() {
        val source = input(ArchiveTestFixtures.zip(ArchiveTestFixtures.entries()))
        val destination = File(root, "existing").apply { mkdir(); resolve("sentinel").writeText("keep") }
        try { FacePackArchive.validateAndExtract(source, destination, 14); fail("Reused existing staging") } catch (_: FacePackException) { }
        assertEquals("keep", destination.resolve("sentinel").readText())
        val link = root.resolve("link").toPath(); Files.createSymbolicLink(link, destination.toPath())
        try { FacePackArchive.validateAndExtract(source, link.resolve("nested").toFile(), 14); fail("Followed staging parent link") } catch (_: FacePackException) { }
        assertFalse(destination.resolve("nested").exists())
        val sourceLink = root.resolve("source-link").toPath(); Files.createSymbolicLink(sourceLink, source.toPath())
        try { FacePackArchive.validateAndExtract(sourceLink.toFile(), File(root, "new-stage"), 14); fail("Followed ZIP link") } catch (_: FacePackException) { }
    }
    @Test fun cancellationBeforeAndDuringWritesLeavesNoVisiblePartialPayload() {
        val source = input(ArchiveTestFixtures.zip(ArchiveTestFixtures.entries()))
        val first = File(root, "cancel-before")
        try { FacePackArchive.validateAndExtract(source, first, 14) { true }; fail("Cancellation ignored") } catch (_: InterruptedIOException) { }
        assertFalse(first.exists())
        val during = File(root, "cancel-after-create")
        try { FacePackArchive.validateAndExtract(source, during, 14) { during.exists() }; fail("Cancellation ignored") } catch (_: InterruptedIOException) { }
        assertFalse(during.exists())
        val large = input(ArchiveTestFixtures.zip(ArchiveTestFixtures.entries(PackSpecFixtures.manifest.padEnd(60_000, ' '))))
        val writing = File(root, "cancel-during-write")
        try { FacePackArchive.validateAndExtract(large, writing, 14) { writing.resolve("manifest.json").length() > 0 }; fail("Cancellation ignored") } catch (_: InterruptedIOException) { }
        assertFalse(writing.exists())
    }
    @Test fun installedValidationAnchorsEveryPayloadByteToRetainedArchive() {
        val bytes = ArchiveTestFixtures.zip(ArchiveTestFixtures.pngEntries())
        val source = input(bytes); val pack = FacePackArchive.validateAndExtract(source, File(root, "installed"), 14)
        val checked = FacePackArchive.validateInstalled(source, pack.directory, 14, pack.digest)
        assertEquals(pack, checked)
        try { FacePackArchive.validateInstalled(source, pack.directory, 14, "0".repeat(64)); fail("Accepted wrong ZIP digest") } catch (_: FacePackException) { }
        File(pack.directory, "LICENSE.txt").writeText("changed")
        try { FacePackArchive.validateInstalled(source, pack.directory, 14, pack.digest); fail("Accepted changed license") } catch (_: FacePackException) { }
    }
    @Test fun installedValidationRejectsMissingExtraAndLinkedPayloads() {
        for (kind in listOf("missing", "extra", "link")) {
            val source = input(ArchiveTestFixtures.zip(ArchiveTestFixtures.entries()))
            val pack = FacePackArchive.validateAndExtract(source, File(root, "installed-$kind"), 14)
            when (kind) {
                "missing" -> pack.directory.resolve("LICENSE.txt").delete()
                "extra" -> pack.directory.resolve("secret.ttf").writeText("not allowed")
                else -> { pack.directory.resolve("LICENSE.txt").delete(); Files.createSymbolicLink(pack.directory.resolve("LICENSE.txt").toPath(), source.toPath()) }
            }
            try { FacePackArchive.validateInstalled(source, pack.directory, 14, pack.digest); fail("Accepted $kind payload") } catch (_: FacePackException) { }
        }
    }
    @Test fun validPackStagingIoFailureIsNotReportedAsCorruptData() {
        val source = input(ArchiveTestFixtures.zip(ArchiveTestFixtures.entries()))
        // A single component over the filesystem limit makes createDirectory fail after admission,
        // independent of effective UID (unlike chmod-based denial tests when running as root).
        val destination = File(root, "x".repeat(300))
        try { FacePackArchive.validateAndExtract(source, destination, 14); fail("Created overlong staging path") }
        catch (e: IOException) { assertFalse("I/O failure is not data corruption", e is FacePackException) }
        assertFalse(destination.exists()); assertTrue(source.isFile)
        // Stat failure is likewise I/O, not "isRegularFile == false" corruption.
        try { FacePackArchive.validateAndExtract(destination, File(root, "not-created"), 14); fail("Read overlong source") }
        catch (e: IOException) { assertFalse(e is FacePackException) }
        try { FacePackArchive.validateAndExtract(source, File(destination, "payload"), 14); fail("Used overlong parent") }
        catch (e: IOException) { assertFalse(e is FacePackException) }
    }
    @Test fun nativeResourceBoundaryKeepsMemoryPressureDistinctAndRetainsCause() {
        val pressure = OutOfMemoryError("synthetic decoder pressure")
        try { FacePackArchive.withNativeImageResources<Unit> { throw pressure }; fail("Ignored native memory pressure") }
        catch (e: FacePackResourceException) { assertSame(pressure, e.cause) }
        val invalid = FacePackException("proven bad image")
        try { FacePackArchive.withNativeImageResources<Unit> { throw invalid }; fail("Ignored invalid image") }
        catch (e: FacePackException) { assertSame(invalid, e) }
        val io = IOException("temporary read failure")
        try { FacePackArchive.withNativeImageResources<Unit> { throw io }; fail("Ignored read failure") }
        catch (e: IOException) { assertSame(io, e) }
    }

}
