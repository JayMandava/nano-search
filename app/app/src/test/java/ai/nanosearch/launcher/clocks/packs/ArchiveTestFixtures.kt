package ai.nanosearch.launcher.clocks.packs

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.Deflater

/** Independent byte-level fixtures; no production ZIP parser or Android bitmap API is used. */
internal object ArchiveTestFixtures {
    data class Entry(
        val name: String, val bytes: ByteArray, val localName: String = name,
        val flags: Int = 0, val localFlags: Int = flags, val method: Int = 0,
        val unixMode: Int = if (name.endsWith('/')) 0x41ed else 0x81a4,
        val platform: Int = 3, val crc: Long = crc(bytes), val declaredSize: Long = bytes.size.toLong(),
        val localSize: Long = declaredSize, val localExtra: ByteArray = byteArrayOf(), val centralExtra: ByteArray = byteArrayOf(),
        val descriptor: Boolean = false, val signedDescriptor: Boolean = true,
        val compressedSuffix: ByteArray = byteArrayOf(),
    )
    val license = "Original synthetic fixture. CC0-1.0. No third-party artwork.".toByteArray()
    fun entries(manifest: String = PackSpecFixtures.manifest) = listOf(Entry("manifest.json", manifest.toByteArray()), Entry("LICENSE.txt", license))
    fun pngEntries(png: ByteArray = png(), hash: String = sha256(png)) = entries(PackSpecFixtures.texture(hash = hash)) + Entry("assets/texture.png", png)
    fun crc(bytes: ByteArray) = CRC32().apply { update(bytes) }.value
    fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    fun zip(entries: List<Entry>, disk: Int = 0): ByteArray {
        val out = ByteArrayOutputStream(); val central = ByteArrayOutputStream()
        entries.forEach { e ->
            val offset = out.size(); val name = e.name.toByteArray(); val localName = e.localName.toByteArray()
            val compressed = (if (e.method == 8) deflate(e.bytes, true) else e.bytes) + e.compressedSuffix
            val flags = e.flags or if (e.descriptor) 8 else 0; val localFlags = e.localFlags or if (e.descriptor) 8 else 0
            out.u32(0x04034b50); out.u16(20); out.u16(localFlags); out.u16(e.method); out.u16(0); out.u16(0)
            out.u32(if (e.descriptor) 0 else e.crc); out.u32(if (e.descriptor) 0 else compressed.size.toLong()); out.u32(if (e.descriptor) 0 else e.localSize)
            out.u16(localName.size); out.u16(e.localExtra.size); out.write(localName); out.write(e.localExtra); out.write(compressed)
            if (e.descriptor) { if (e.signedDescriptor) out.u32(0x08074b50); out.u32(e.crc); out.u32(compressed.size.toLong()); out.u32(e.declaredSize) }
            central.u32(0x02014b50); central.u16((e.platform shl 8) or 20); central.u16(20); central.u16(flags); central.u16(e.method)
            central.u16(0); central.u16(0); central.u32(e.crc); central.u32(compressed.size.toLong()); central.u32(e.declaredSize)
            central.u16(name.size); central.u16(e.centralExtra.size); central.u16(0); central.u16(0); central.u16(0)
            central.u32((e.unixMode.toLong() shl 16) or if (e.name.endsWith('/')) 0x10 else 0); central.u32(offset.toLong()); central.write(name); central.write(e.centralExtra)
        }
        val centralOffset = out.size(); out.write(central.toByteArray())
        out.u32(0x06054b50); out.u16(disk); out.u16(disk); out.u16(entries.size); out.u16(entries.size)
        out.u32(central.size().toLong()); out.u32(centralOffset.toLong()); out.u16(0)
        return out.toByteArray()
    }
    fun centralOffsets(zip: ByteArray): List<Int> {
        var at = read32(zip, zip.size - 6).toInt()
        return List(read16(zip, zip.size - 12)) { val old = at; at += 46 + read16(zip, at + 28) + read16(zip, at + 30) + read16(zip, at + 32); old }
    }
    fun extra(id: Int, payload: ByteArray = byteArrayOf()) = ByteArrayOutputStream().apply { u16(id); u16(payload.size); write(payload) }.toByteArray()
    fun set16(bytes: ByteArray, offset: Int, n: Int) { repeat(2) { bytes[offset + it] = (n ushr (8 * it)).toByte() } }
    fun set32(bytes: ByteArray, offset: Int, n: Long) { repeat(4) { bytes[offset + it] = (n ushr (8 * it)).toByte() } }
    fun read16(bytes: ByteArray, offset: Int) = (bytes[offset].toInt() and 255) or ((bytes[offset + 1].toInt() and 255) shl 8)
    fun read32(bytes: ByteArray, offset: Int): Long { var n = 0L; repeat(4) { n = n or ((bytes[offset + it].toLong() and 255) shl (8 * it)) }; return n }
    fun deflate(data: ByteArray, raw: Boolean = false): ByteArray {
        val d = Deflater(6, raw)
        return try { d.setInput(data); d.finish(); val out = ByteArrayOutputStream(); val buffer = ByteArray(8192); while (!d.finished()) out.write(buffer, 0, d.deflate(buffer)); out.toByteArray() }
        finally { d.end() }
    }
    fun chunk(type: String, data: ByteArray): ByteArray = ByteArrayOutputStream().apply {
        be32(data.size.toLong()); write(type.toByteArray()); write(data)
        be32(crc(type.toByteArray() + data))
    }.toByteArray()
    fun png(width: Int = 4, height: Int = 4, depth: Int = 8, color: Int = 6, interlace: Int = 0,
            raw: ByteArray? = null, beforeData: ByteArray = byteArrayOf(), compressedSuffix: ByteArray = byteArrayOf()): ByteArray {
        val header = ByteArrayOutputStream().apply { be32(width.toLong()); be32(height.toLong()); write(depth); write(color); write(0); write(0); write(interlace) }.toByteArray()
        val channels = when (color) { 0, 3 -> 1; 2 -> 3; 4 -> 2; else -> 4 }
        val row = (width * channels * depth + 7) / 8
        val pixels = raw ?: ByteArray((row + 1) * height) { i -> if (i % (row + 1) == 0) 0 else 0x7f }
        return byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10) + chunk("IHDR", header) + beforeData + chunk("IDAT", deflate(pixels) + compressedSuffix) + chunk("IEND", byteArrayOf())
    }
    private fun ByteArrayOutputStream.u16(n: Int) { write(n and 255); write(n ushr 8 and 255) }
    private fun ByteArrayOutputStream.u32(n: Long) { repeat(4) { write((n ushr (8 * it)).toInt() and 255) } }
    private fun ByteArrayOutputStream.be32(n: Long) { repeat(4) { write((n ushr (24 - 8 * it)).toInt() and 255) } }
}
