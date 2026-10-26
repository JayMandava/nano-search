package ai.nanosearch.launcher.clocks.packs

import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Test

class FacePackPngTest {
    private fun reject(bytes: ByteArray, message: String? = null) {
        try { PngCheck.inspect(bytes); fail("Accepted malformed PNG") }
        catch (e: FacePackException) { if (message != null) assertTrue("Expected $message, got ${e.message}", e.message!!.contains(message, true)) }
    }
    @Test fun maximumDimensionsDecodeWithBoundedScanlineStorage() {
        for (size in listOf(1023, 1024)) {
            val info = PngCheck.inspect(ArchiveTestFixtures.png(size, size))
            assertEquals(size.toLong() * size * 4, info.decodedBytes)
        }
        reject(ArchiveTestFixtures.png(1025, 1), "dimensions")
        reject(ArchiveTestFixtures.png(1, 1025), "dimensions")
        reject(ArchiveTestFixtures.png(0, 1), "dimensions")
    }
    @Test fun rgbGrayAlphaPaletteAndAdam7StaticFormatsAreValidated() {
        for (color in listOf(0, 2, 4, 6)) for (depth in listOf(8, 16)) assertEquals(4, PngCheck.inspect(ArchiveTestFixtures.png(color = color, depth = depth)).width)
        for (depth in listOf(1, 2, 4)) assertEquals(4, PngCheck.inspect(ArchiveTestFixtures.png(color = 0, depth = depth)).width)
        val palette = ArchiveTestFixtures.chunk("PLTE", byteArrayOf(1, 2, 3, 4, 5, 6))
        assertEquals(1, PngCheck.inspect(ArchiveTestFixtures.png(1, 1, color = 3, depth = 1, raw = byteArrayOf(0, 0), beforeData = palette)).height)
        assertEquals(1, PngCheck.inspect(ArchiveTestFixtures.png(1, 1, interlace = 1)).height)
    }
    @Test fun allFiveFiltersAreAcceptedAndInvalidFilterIsRejected() {
        for (filter in 0..4) {
            val raw = byteArrayOf(filter.toByte(), 1, 2, 3, 4)
            assertEquals(1, PngCheck.inspect(ArchiveTestFixtures.png(1, 1, raw = raw)).width)
        }
        reject(ArchiveTestFixtures.png(1, 1, raw = byteArrayOf(5, 0, 0, 0, 0)), "filter")
    }
    @Test fun animationExecutableDisguiseCompressedMetadataAndUnknownChunksFailClosed() {
        for (kind in listOf("acTL", "fcTL", "fdAT", "iCCP", "zTXt", "iTXt", "tEXt", "eXIf", "ABCD")) {
            reject(ArchiveTestFixtures.png(beforeData = ArchiveTestFixtures.chunk(kind, byteArrayOf(0))), "unsupported")
        }
        reject("<svg><script>bad</script></svg>".toByteArray())
        reject(byteArrayOf(0, 1, 0, 0) + ByteArray(128))
    }
    @Test fun crcTruncationAndTrailingCompressedOrPixelDataAreRejected() {
        val png = ArchiveTestFixtures.png()
        reject(png.copyOf().apply { this[30] = (this[30].toInt() xor 1).toByte() }, "CRC")
        reject(png.copyOf(png.size - 1))
        reject(png + byteArrayOf(0))
        reject(ArchiveTestFixtures.png(compressedSuffix = byteArrayOf(0)), "trailing")
        reject(ArchiveTestFixtures.png(1, 1, raw = byteArrayOf(0, 1, 2)), "truncated")
        reject(ArchiveTestFixtures.png(1, 1, raw = byteArrayOf(0, 1, 2, 3, 4, 5)), "trailing")
    }
    @Test fun paletteIndicesAndCriticalChunkOrderingMustBeValid() {
        val palette = ArchiveTestFixtures.chunk("PLTE", byteArrayOf(1, 2, 3))
        reject(ArchiveTestFixtures.png(1, 1, color = 3, depth = 1, raw = byteArrayOf(0, 0x80.toByte()), beforeData = palette), "index")
        reject(ArchiveTestFixtures.png(1, 1, color = 3, depth = 8, raw = byteArrayOf(0, 0)), "ordering")
        reject(ArchiveTestFixtures.png(beforeData = palette + palette), "palette")
        reject(ArchiveTestFixtures.png(color = 0, beforeData = palette), "palette")
    }
    @Test fun unsupportedColorEncodingAndOversizedEncodedFileAreRejected() {
        reject(ArchiveTestFixtures.png(color = 1), "encoding")
        reject(ArchiveTestFixtures.png(depth = 4, color = 6), "encoding")
        reject(ArchiveTestFixtures.png(interlace = 2), "interlace")
        reject(ByteArray(FacePackLimits.MAX_PNG_BYTES + 1))
    }
    @Test fun adam7ChecksEveryPassAndRejectsMissingPassPixels() {
        val pixels = ByteArrayOutputStream()
        // Enumerate the seven source-coordinate patterns rather than reusing decoder row math.
        for ((startX, startY, stepX, stepY) in listOf(
            listOf(0, 0, 8, 8), listOf(4, 0, 8, 8), listOf(0, 4, 4, 8), listOf(2, 0, 4, 4),
            listOf(0, 2, 2, 4), listOf(1, 0, 2, 2), listOf(0, 1, 1, 2))) {
            for (y in startY until 9 step stepY) {
                pixels.write(0)
                for (x in startX until 9 step stepX) pixels.write(byteArrayOf(x.toByte(), y.toByte(), 0x7f, 0xff.toByte()))
            }
        }
        val bytes = pixels.toByteArray()
        assertEquals(9, PngCheck.inspect(ArchiveTestFixtures.png(9, 9, interlace = 1, raw = bytes)).width)
        reject(ArchiveTestFixtures.png(9, 9, interlace = 1, raw = bytes.copyOf(bytes.size - 1)), "truncated")
    }
    @Test fun filtersAreReconstructedBeforeCheckingPaletteIndices() {
        val palette = ArchiveTestFixtures.chunk("PLTE", byteArrayOf(1, 2, 3, 4, 5, 6))
        val rows = listOf(
            byteArrayOf(0, 0, 1, 0, 0, 1, 0, 1),
            byteArrayOf(1, 0, 1, -1, 1, 1, -1, 1),
            byteArrayOf(2, 0, 1, 0, 2, 1, -1, 1),
            byteArrayOf(3, 0, 1, 0, 3, 1, -1, 1),
            byteArrayOf(4, 0, 1, -1, 4, 1, -1, 1),
        )
        for (row in rows) {
            assertEquals(3, PngCheck.inspect(ArchiveTestFixtures.png(3, 2, color = 3, depth = 8, raw = row, beforeData = palette)).width)
            reject(ArchiveTestFixtures.png(3, 2, color = 3, depth = 8, raw = row.copyOf().apply { this[1] = 2 }, beforeData = palette), "index")
        }
    }

}
