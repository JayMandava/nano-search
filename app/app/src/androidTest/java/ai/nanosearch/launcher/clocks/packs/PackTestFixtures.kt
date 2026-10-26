package ai.nanosearch.launcher.clocks.packs

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater

/** Byte-level fixtures deliberately independent of the production ZIP parser. */
internal object PackTestFixtures {
    // Original authoring fixture: no supplied artwork, font, brand, or external dependency.
    val manifest = """{"format":"nano.clock-face-pack","schemaVersion":1,"packId":"test-original","packVersion":1,"name":"Original test dial","author":"Nano test fixtures","license":"CC0-1.0","minAppVersionCode":14,"requires":["analog-v1"],"template":"analog-v1","dial":{"backgroundColor":"#FF101820","ticks":"minute","tickColor":"#FFE8EDF0","numerals":"quarters","numeralColor":"#FFE8EDF0","font":"system-sans"},"hands":{"hour":{"shape":"needle","color":"#FFFFFFFF","length":0.24,"width":0.024,"tail":0.02},"minute":{"shape":"bar","color":"#FFFFFFFF","length":0.39,"width":0.014,"tail":0.03}},"assets":[]}"""
    fun manifestWith(extra: String): String = manifest.dropLast(1) + "," + extra + "}"
    data class Entry(
        val name: String,
        val bytes: ByteArray,
        val localName: String = name,
        val flags: Int = 0,
        val method: Int = 0,
        val unixMode: Int = 0x81a4,
        val crc: Long = CRC32().apply { update(bytes) }.value,
        val declaredSize: Long = bytes.size.toLong(),
        val localFlags: Int = flags,
    )

    fun zip(entries: List<Entry>, disk: Int = 0): ByteArray {
        val out = ByteArrayOutputStream()
        val central = ByteArrayOutputStream()
        fun ByteArrayOutputStream.u16(n: Int) { write(n and 255); write(n ushr 8 and 255) }
        fun ByteArrayOutputStream.u32(n: Long) { repeat(4) { write((n ushr (8 * it)).toInt() and 255) } }
        entries.forEach { entry ->
            val offset = out.size()
            val name = entry.name.toByteArray(Charsets.UTF_8)
            val localName = entry.localName.toByteArray(Charsets.UTF_8)
            val compressed = if (entry.method == 8) {
                val d = Deflater(9, true)
                try {
                    d.setInput(entry.bytes); d.finish()
                    val compressedOut = ByteArrayOutputStream()
                    val buffer = ByteArray(4096)
                    while (!d.finished()) { compressedOut.write(buffer, 0, d.deflate(buffer)) }
                    compressedOut.toByteArray()
                } finally { d.end() }
            } else entry.bytes
            out.u32(0x04034b50); out.u16(20); out.u16(entry.localFlags); out.u16(entry.method)
            out.u16(0); out.u16(0); out.u32(entry.crc); out.u32(compressed.size.toLong())
            out.u32(entry.declaredSize); out.u16(localName.size); out.u16(0); out.write(localName); out.write(compressed)
            central.u32(0x02014b50); central.u16(0x0314); central.u16(20); central.u16(entry.flags)
            central.u16(entry.method); central.u16(0); central.u16(0); central.u32(entry.crc)
            central.u32(compressed.size.toLong()); central.u32(entry.declaredSize); central.u16(name.size)
            central.u16(0); central.u16(0); central.u16(0); central.u16(0)
            central.u32(entry.unixMode.toLong() shl 16); central.u32(offset.toLong()); central.write(name)
        }
        val offset = out.size()
        out.write(central.toByteArray()); out.u32(0x06054b50); out.u16(disk); out.u16(disk)
        out.u16(entries.size); out.u16(entries.size); out.u32(central.size().toLong()); out.u32(offset.toLong()); out.u16(0)
        return out.toByteArray()
    }

    fun png(width: Int = 32, height: Int = 32): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        return try {
            bitmap.eraseColor(0xff345678.toInt())
            ByteArrayOutputStream().also { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }.toByteArray()
        } finally { bitmap.recycle() }
    }

    fun apng(png: ByteArray): ByteArray {
        val type = "acTL".toByteArray(Charsets.US_ASCII)
        val data = byteArrayOf(0, 0, 0, 1, 0, 0, 0, 0)
        val crc = CRC32().apply { update(type); update(data) }.value
        val chunk = ByteArrayOutputStream().apply {
            write(byteArrayOf(0, 0, 0, 8)); write(type); write(data)
            repeat(4) { write((crc ushr (24 - 8 * it)).toInt() and 255) }
        }.toByteArray()
        return png.copyOfRange(0, 33) + chunk + png.copyOfRange(33, png.size)
    }
}
