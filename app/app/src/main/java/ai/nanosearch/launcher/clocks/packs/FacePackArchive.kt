package ai.nanosearch.launcher.clocks.packs

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import org.apache.commons.compress.archivers.zip.ZipFile
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.CodingErrorAction
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.NoSuchFileException
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/** Immutable validated payload. Android callers must also call [FacePackArchive.validateAndroidImages]
 * off the main thread before publishing a revision; that checks the actual platform decoder. */
data class ValidatedFacePack(
    val spec: FacePackSpec,
    val directory: File,
    val digest: String,
    val payloadBytes: Long,
    val decodedBytes: Long,
    val licenseText: String,
)

/** Bounded data-only ZIP validation. No archive path or permissions are ever passed to an extractor.
 * ZIP bytes are snapshotted once, so metadata checks and inflation use identical immutable input. */
object FacePackArchive {
    fun validateAndExtract(
        zip: File,
        destination: File,
        appVersionCode: Int,
        cancelled: () -> Boolean = { false },
    ): ValidatedFacePack {
        val checked = inspect(zip, appVersionCode, cancelled)
        val root = destination.toPath().toAbsolutePath().normalize()
        checkPack(!Files.exists(root, NOFOLLOW_LINKS), "Pack staging destination already exists")
        val parent = root.parent ?: throw FacePackException("Invalid pack staging destination")
        requireDirectoryChain(parent)
        var created = false
        try {
            checkCancelled(cancelled)
            Files.createDirectory(root) // Exclusive: an existing directory/link is never reused.
            created = true
            if (checked.files.keys.any { it.startsWith("assets/") }) Files.createDirectory(root.resolve("assets"))
            for ((name, bytes) in checked.files) {
                checkCancelled(cancelled)
                if (name.endsWith('/')) continue
                val target = root.resolve(name).normalize()
                checkPack(target.startsWith(root) && target != root, "Invalid payload destination")
                Files.newByteChannel(target, setOf(CREATE_NEW, WRITE, NOFOLLOW_LINKS)).use { channel ->
                    var offset = 0
                    while (offset < bytes.size) {
                        checkCancelled(cancelled)
                        val end = minOf(offset + 8192, bytes.size)
                        val buffer = ByteBuffer.wrap(bytes, offset, end - offset)
                        while (buffer.hasRemaining()) { checkCancelled(cancelled); channel.write(buffer) }
                        offset = end
                    }
                    (channel as? FileChannel)?.force(true)
                }
            }
            checkCancelled(cancelled)
            return checked.result(root.toFile())
        } catch (e: Exception) {
            if (created) removeOwnStaging(root)
            if (e is IOException) throw e
            throw IOException("Could not write validated pack staging", e)
        }
    }

    /** Read-only recovery validation anchored to a retained ZIP outside the payload directory.
     * The expected ZIP digest comes from the durable registry/catalog, not from mutable payload data.
     * No second extraction or temporary payload copy is created. */
    fun validateInstalled(
        zip: File,
        directory: File,
        appVersionCode: Int,
        expectedDigest: String,
        cancelled: () -> Boolean = { false },
    ): ValidatedFacePack {
        checkPack(Regex("[0-9a-f]{64}").matches(expectedDigest), "Invalid stored pack digest")
        val checked = inspect(zip, appVersionCode, cancelled)
        checkPack(checked.digest == expectedDigest, "Stored pack archive failed verification")
        val root = directory.toPath().toAbsolutePath().normalize()
        requireDirectoryChain(root)
        val expectedFiles = checked.files.filterKeys { !it.endsWith('/') }
        val seen = mutableSetOf<String>()
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                checkCancelled(cancelled)
                checkPack(!attrs.isSymbolicLink && attrs.isDirectory, "Invalid installed pack directory")
                val relative = root.relativize(dir).toString().replace(File.separatorChar, '/')
                checkPack(relative.isEmpty() || relative == "assets" && checked.files.keys.any { it.startsWith("assets/") }, "Unexpected installed directory")
                return FileVisitResult.CONTINUE
            }
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                checkCancelled(cancelled)
                checkPack(attrs.isRegularFile && !attrs.isSymbolicLink, "Installed payload contains a link or special file")
                val name = root.relativize(file).toString().replace(File.separatorChar, '/')
                val expected = expectedFiles[name] ?: throw FacePackException("Unexpected installed payload file")
                checkPack(readFile(file, expected.size.toLong(), cancelled).contentEquals(expected), "Installed payload failed verification")
                seen.add(name)
                return FileVisitResult.CONTINUE
            }
        })
        checkPack(seen == expectedFiles.keys, "Installed payload is incomplete")
        return checked.result(root.toFile())
    }

    /** Actual Android bounds/full decode, separate from portable byte-level validation so JVM tests
     * never substitute mocked android.jar methods for the real decoder. Invoke before publication. */
    fun validateAndroidImages(pack: ValidatedFacePack, cancelled: () -> Boolean = { false }) = withNativeImageResources {
        val root = pack.directory.toPath().toAbsolutePath().normalize()
        requireDirectoryChain(root)
        var allocated = 0L
        for (asset in pack.spec.assets) {
            checkCancelled(cancelled)
            val path = root.resolve(asset.path).normalize()
            checkPack(path.startsWith(root) && path != root, "Invalid decoded image path")
            requireDirectoryChain(path.parent)
            val bytes = readFile(path, FacePackLimits.MAX_PNG_BYTES.toLong(), cancelled)
            checkPack(sha256(bytes) == asset.sha256, "Image changed after validation")
            val png = PngCheck.inspect(bytes, cancelled)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            checkPack(bounds.outWidth == png.width && bounds.outHeight == png.height && bounds.outMimeType == "image/png", "Platform image bounds disagree")
            checkPack(allocated + png.decodedBytes <= FacePackLimits.MAX_DECODED_BYTES, "Decoded image budget exceeded")
            val options = BitmapFactory.Options().apply {
                inScaled = false
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
            }
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                ?: throw FacePackException("Image could not be decoded")
            try {
                checkCancelled(cancelled)
                checkPack(bitmap.width == png.width && bitmap.height == png.height && bitmap.config == Bitmap.Config.ARGB_8888, "Unexpected decoded image format")
                val actual = bitmap.allocationByteCount.toLong()
                checkPack(actual >= png.decodedBytes && actual <= FacePackLimits.MAX_DECODED_BYTES - allocated, "Unexpected decoded image allocation")
                allocated += actual
            } finally { bitmap.recycle() }
        }
    }

    /** Keep resource exhaustion distinct from proven malformed image data, including on startup. */
    internal inline fun <T> withNativeImageResources(block: () -> T): T = try {
        block()
    } catch (error: OutOfMemoryError) {
        throw FacePackResourceException("Not enough memory to validate artwork", error)
    }

    internal fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private data class Checked(
        val spec: FacePackSpec,
        val files: Map<String, ByteArray>,
        val digest: String,
        val payloadBytes: Long,
        val decodedBytes: Long,
        val licenseText: String,
    ) {
        fun result(directory: File) = ValidatedFacePack(spec, directory, digest, payloadBytes, decodedBytes, licenseText)
    }

    private fun inspect(zip: File, appVersionCode: Int, cancelled: () -> Boolean): Checked {
        checkCancelled(cancelled)
        checkPack(appVersionCode > 0, "Invalid application version")
        val bytes = readFile(zip.toPath(), FacePackLimits.MAX_ZIP_BYTES, cancelled)
        val entries = RawZip.inspect(bytes, cancelled)
        try {
            ZipFile.builder().setSeekableByteChannel(SeekableInMemoryByteChannel(bytes))
                .setUseUnicodeExtraFields(false).setIgnoreLocalFileHeader(false).get().use { archive ->
                    val parsed = archive.entries.toList()
                    checkPack(parsed.size == entries.size, "Archive entry count disagrees")
                    for ((entry, raw) in parsed.zip(entries)) {
                        checkCancelled(cancelled)
                        checkPack(entry.name == raw.name && entry.rawName.contentEquals(raw.name.toByteArray(Charsets.US_ASCII)), "Archive names disagree")
                        checkPack(entry.method == raw.method && entry.rawFlag == raw.flags && entry.size == raw.size.toLong() && entry.compressedSize == raw.compressedSize.toLong() && entry.crc == raw.crc, "Archive metadata disagrees")
                        checkPack(entry.localHeaderOffset == raw.localOffset.toLong() && entry.dataOffset == raw.dataOffset.toLong(), "Archive offsets disagree")
                        checkPack(!entry.isUnixSymlink && (entry.unixMode and 0xf000) in setOf(0, 0x8000, 0x4000), "Archive link or special file is forbidden")
                        checkPack(archive.canReadEntryData(entry), "Unsupported archive entry")
                    }
                }
        } catch (e: Exception) {
            if (e is FacePackException || e is InterruptedIOException) throw e
            throw FacePackException("Invalid ZIP metadata", e)
        }
        val files = linkedMapOf<String, ByteArray>()
        var payload = 0L
        var compressed = 0L
        for (entry in entries) {
            checkCancelled(cancelled)
            val data = inflateEntry(bytes, entry, cancelled)
            payload += data.size
            compressed += entry.compressedSize
            checkPack(payload <= FacePackLimits.MAX_EXPANDED_BYTES && payload <= maxOf(1024L, compressed) * FacePackLimits.MAX_RATIO, "Archive expansion budget exceeded")
            files[entry.name] = data
        }
        val manifest = files["manifest.json"] ?: throw FacePackException("Pack manifest is missing")
        val spec = FacePackSpec.parse(manifest)
        checkPack(spec.minAppVersionCode <= appVersionCode, "Requires a newer Nano version", incompatible = true)
        val licenseBytes = files["LICENSE.txt"] ?: throw FacePackException("Pack license is missing")
        val license = utf8(licenseBytes)
        checkPack(license.isNotBlank() && license.none { it.isISOControl() && it !in "\n\r\t" }, "Invalid license text")
        val allowed = setOf("manifest.json", "LICENSE.txt", "assets/") + spec.assets.map { it.path }
        checkPack(files.keys.all { it in allowed }, "Archive contains undeclared files")
        var decoded = 0L
        for (asset in spec.assets) {
            checkCancelled(cancelled)
            val data = files[asset.path] ?: throw FacePackException("Declared asset is missing")
            checkPack(sha256(data) == asset.sha256, "Asset checksum mismatch")
            decoded += PngCheck.inspect(data, cancelled).decodedBytes
            checkPack(decoded <= FacePackLimits.MAX_DECODED_BYTES, "Decoded image budget exceeded")
        }
        return Checked(spec, files, sha256(bytes), payload, decoded, license)
    }

    private fun inflateEntry(zip: ByteArray, entry: RawZip.Entry, cancelled: () -> Boolean): ByteArray {
        val cap = when (entry.name) {
            "manifest.json" -> FacePackLimits.MAX_MANIFEST_BYTES
            "LICENSE.txt" -> FacePackLimits.MAX_LICENSE_BYTES
            "assets/" -> 0
            else -> FacePackLimits.MAX_PNG_BYTES
        }
        checkPack(entry.size <= cap && entry.size.toLong() <= maxOf(1024L, entry.compressedSize.toLong()) * FacePackLimits.MAX_RATIO, "Entry expansion budget exceeded")
        val out = ByteArrayOutputStream(minOf(entry.size, 8192))
        val crc = CRC32()
        fun append(buffer: ByteArray, count: Int) {
            checkCancelled(cancelled)
            val next = out.size().toLong() + count
            checkPack(next <= cap && next <= entry.size && next <= maxOf(1024L, entry.compressedSize.toLong()) * FacePackLimits.MAX_RATIO, "Entry expansion budget exceeded")
            out.write(buffer, 0, count); crc.update(buffer, 0, count)
        }
        if (entry.method == 0) {
            checkPack(entry.compressedSize == entry.size, "Stored entry sizes disagree")
            var offset = entry.dataOffset
            while (offset < entry.dataOffset + entry.size) {
                val end = minOf(offset + 8192, entry.dataOffset + entry.size)
                append(zip.copyOfRange(offset, end), end - offset); offset = end
            }
        } else {
            val inflater = Inflater(true)
            try {
                inflater.setInput(zip, entry.dataOffset, entry.compressedSize)
                val buffer = ByteArray(8192)
                while (!inflater.finished()) {
                    checkCancelled(cancelled)
                    val n = inflater.inflate(buffer)
                    if (n > 0) append(buffer, n)
                    else checkPack(inflater.finished(), "Truncated or invalid DEFLATE stream")
                }
                checkPack(inflater.remaining == 0 && inflater.bytesRead == entry.compressedSize.toLong(), "Trailing compressed entry data")
            } catch (e: DataFormatException) { throw FacePackException("Invalid DEFLATE data", e) }
            finally { inflater.end() }
        }
        checkPack(out.size() == entry.size && crc.value == entry.crc, "Entry size or CRC mismatch")
        return out.toByteArray()
    }

    private fun utf8(bytes: ByteArray): String = try {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    } catch (e: Exception) { throw FacePackException("Text must be UTF-8", e) }

    private fun readFile(path: Path, limit: Long, cancelled: () -> Boolean): ByteArray {
        checkCancelled(cancelled)
        val attributes = readAttributes(path, "Expected a regular pack file")
        checkPack(attributes.isRegularFile && !attributes.isSymbolicLink, "Expected a regular pack file")
        checkPack(attributes.size() <= limit, "File exceeds pack size limit")
        val out = ByteArrayOutputStream()
        try {
            Files.newByteChannel(path, setOf(READ, NOFOLLOW_LINKS)).use { channel ->
                val buffer = ByteBuffer.allocate(8192)
                while (true) {
                    checkCancelled(cancelled)
                    buffer.clear()
                    val n = channel.read(buffer)
                    if (n < 0) break
                    checkPack(out.size().toLong() + n <= limit, "File exceeds pack size limit")
                    out.write(buffer.array(), 0, n)
                }
            }
        } catch (e: IOException) {
            // Preserve ordinary I/O failures: a transient read error is not proof of corruption.
            throw e
        }
        return out.toByteArray()
    }

    private fun readAttributes(path: Path, missing: String): BasicFileAttributes = try {
        Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
    } catch (e: NoSuchFileException) {
        throw FacePackException(missing, e)
    } // Permission/device/stat I/O failures remain IOException, not false evidence of corruption.

    private fun requireDirectoryChain(path: Path) {
        var current: Path? = path
        while (current != null) {
            val attributes = readAttributes(current, "Pack directory is missing")
            checkPack(attributes.isDirectory && !attributes.isSymbolicLink, "Pack directory must not contain links")
            current = current.parent
        }
    }

    private fun removeOwnStaging(root: Path) {
        // Never follows symbolic links and is called only after our exclusive create succeeded.
        runCatching {
            Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult { Files.deleteIfExists(file); return FileVisitResult.CONTINUE }
                override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult { Files.deleteIfExists(dir); return FileVisitResult.CONTINUE }
            })
        }
    }
}

/** Deliberately narrow, ordinary ZIP profile: STORED/DEFLATED, ASCII allowlisted names,
 * optional data descriptors, and optional extended timestamps. No ZIP64/links/aliases/padding. */
private object RawZip {
    data class Entry(val name: String, val flags: Int, val method: Int, val crc: Long, val compressedSize: Int, val size: Int, val localOffset: Int, val dataOffset: Int, val endOffset: Int)

    fun inspect(bytes: ByteArray, cancelled: () -> Boolean): List<Entry> {
        checkPack(bytes.size >= 22, "Truncated ZIP")
        val eocd = bytes.size - 22
        checkPack(u32(bytes, eocd) == 0x06054b50L && u16(bytes, eocd + 20) == 0, "ZIP comments, trailing data or missing directory are unsupported")
        checkPack(u16(bytes, eocd + 4) == 0 && u16(bytes, eocd + 6) == 0, "Multidisk ZIP is unsupported")
        val count = u16(bytes, eocd + 10)
        checkPack(count in 2..FacePackLimits.MAX_ENTRIES && u16(bytes, eocd + 8) == count, "Invalid ZIP entry count")
        val centralSize = boundedInt(u32(bytes, eocd + 12), bytes.size)
        val central = boundedInt(u32(bytes, eocd + 16), bytes.size)
        checkPack(central.toLong() + centralSize == eocd.toLong(), "ZIP directory bounds disagree")
        var at = central
        val seen = mutableSetOf<String>()
        val entries = ArrayList<Entry>(count)
        repeat(count) {
            checkCancelled(cancelled)
            range(bytes, at, 46)
            checkPack(u32(bytes, at) == 0x02014b50L, "Invalid ZIP directory entry")
            val platform = u16(bytes, at + 4) ushr 8
            val version = u16(bytes, at + 6)
            val flags = u16(bytes, at + 8)
            val method = u16(bytes, at + 10)
            val crc = u32(bytes, at + 16)
            val compressed = boundedInt(u32(bytes, at + 20), bytes.size)
            val size = boundedInt(u32(bytes, at + 24), FacePackLimits.MAX_EXPANDED_BYTES.toInt())
            val nameLength = u16(bytes, at + 28)
            val extraLength = u16(bytes, at + 30)
            val commentLength = u16(bytes, at + 32)
            val attributes = u32(bytes, at + 38)
            val offset = boundedInt(u32(bytes, at + 42), central)
            checkPack(platform in setOf(0, 3) && version in 10..20 && method in setOf(0, 8), "Unsupported ZIP entry format")
            checkPack(flags and 0xF7F1 == 0 && (method == 8 || flags and 6 == 0), "Encrypted or unsupported ZIP flags")
            checkPack(u16(bytes, at + 34) == 0 && commentLength == 0 && nameLength in 1..128, "Unsupported ZIP entry metadata")
            range(bytes, at + 46, nameLength + extraLength + commentLength)
            val rawName = bytes.copyOfRange(at + 46, at + 46 + nameLength)
            checkPack(rawName.all { (it.toInt() and 255) in 0x20..0x7e }, "ZIP names must be ASCII")
            val name = rawName.toString(Charsets.US_ASCII)
            checkPack(name == "manifest.json" || name == "LICENSE.txt" || name == "assets/" || Regex("assets/[a-z][a-z0-9-]{0,31}\\.png").matches(name), "Unexpected ZIP path")
            checkPack(seen.add(name.lowercase(Locale.ROOT)), "Duplicate ZIP entry")
            val directory = name == "assets/"
            checkPack(platform != 0 || attributes ushr 16 == 0L, "Ambiguous ZIP file attributes")
            val mode = if (platform == 3) (attributes ushr 16).toInt() and 0xf000 else 0
            checkPack(mode == 0 || mode == if (directory) 0x4000 else 0x8000, "ZIP links and special files are forbidden")
            checkPack(attributes and 0x10L == 0L || directory, "ZIP file attributes disagree")
            checkPack(attributes and 0xffc8L == 0L, "Unsupported DOS file attributes")
            checkPack(!directory || size == 0 && crc == 0L, "Directory contains data")
            extras(bytes, at + 46 + nameLength, extraLength)
            range(bytes, offset, 30)
            checkPack(u32(bytes, offset) == 0x04034b50L && u16(bytes, offset + 4) == version && u16(bytes, offset + 6) == flags && u16(bytes, offset + 8) == method, "ZIP local header disagrees")
            checkPack(u16(bytes, offset + 10) == u16(bytes, at + 12) && u16(bytes, offset + 12) == u16(bytes, at + 14), "ZIP timestamps disagree")
            val localNameLength = u16(bytes, offset + 26)
            val localExtraLength = u16(bytes, offset + 28)
            range(bytes, offset + 30, localNameLength + localExtraLength)
            checkPack(localNameLength == nameLength && bytes.copyOfRange(offset + 30, offset + 30 + localNameLength).contentEquals(rawName), "ZIP local name disagrees")
            extras(bytes, offset + 30 + localNameLength, localExtraLength)
            val localCrc = u32(bytes, offset + 14)
            val localCompressed = u32(bytes, offset + 18)
            val localSize = u32(bytes, offset + 22)
            val descriptor = flags and 8 != 0
            checkPack(if (descriptor) (localCrc == 0L || localCrc == crc) && (localCompressed == 0L || localCompressed == compressed.toLong()) && (localSize == 0L || localSize == size.toLong())
                else localCrc == crc && localCompressed == compressed.toLong() && localSize == size.toLong(), "ZIP local sizes or CRC disagree")
            val dataOffset = offset.toLong() + 30 + localNameLength + localExtraLength
            var end = dataOffset + compressed
            checkPack(dataOffset <= central && end <= central, "ZIP payload overlaps directory")
            if (descriptor) {
                val start = end.toInt()
                val signature = u32(bytes, start) == 0x08074b50L
                val fields = start + if (signature) 4 else 0
                checkPack(u32(bytes, fields) == crc && u32(bytes, fields + 4) == compressed.toLong() && u32(bytes, fields + 8) == size.toLong(), "ZIP data descriptor disagrees")
                end = fields.toLong() + 12
                checkPack(end <= central, "ZIP descriptor overlaps directory")
            }
            entries += Entry(name, flags, method, crc, compressed, size, offset, dataOffset.toInt(), end.toInt())
            at += 46 + nameLength + extraLength + commentLength
            checkPack(at <= eocd, "ZIP directory overflow")
        }
        checkPack(at == eocd, "Unexpected ZIP directory records")
        var next = 0
        entries.sortedBy { it.localOffset }.forEach { entry ->
            checkPack(entry.localOffset == next, "ZIP entries overlap or contain hidden padding")
            next = entry.endOffset
        }
        checkPack(next == central, "ZIP payload layout is incomplete")
        return entries
    }

    private fun extras(bytes: ByteArray, start: Int, length: Int) {
        var at = start
        val end = start + length
        val seen = mutableSetOf<Int>()
        while (at < end) {
            checkPack(end - at >= 4, "Truncated ZIP extra field")
            val id = u16(bytes, at); val size = u16(bytes, at + 2)
            checkPack(at.toLong() + 4 + size <= end && seen.add(id), "Invalid ZIP extra field")
            // Only fixed timestamp data is accepted; all link/Unicode-alias/ZIP64/unknown extras fail closed.
            checkPack(id == 0x5455 && size >= 1, "Unsupported ZIP extra field")
            val flags = bytes[at + 4].toInt() and 255
            checkPack(flags and 0xf8 == 0 && size == 1 + Integer.bitCount(flags) * 4, "Invalid ZIP timestamp field")
            at += 4 + size
        }
        checkPack(at == end, "Invalid ZIP extra-field size")
    }
    private fun boundedInt(n: Long, maximum: Int): Int { checkPack(n in 0..maximum.toLong(), "ZIP numeric bounds exceeded"); return n.toInt() }
    private fun range(bytes: ByteArray, offset: Int, length: Int) { checkPack(offset >= 0 && length >= 0 && offset.toLong() + length <= bytes.size, "Truncated ZIP record") }
    private fun u16(bytes: ByteArray, offset: Int): Int { range(bytes, offset, 2); return (bytes[offset].toInt() and 255) or ((bytes[offset + 1].toInt() and 255) shl 8) }
    private fun u32(bytes: ByteArray, offset: Int): Long { range(bytes, offset, 4); var n = 0L; repeat(4) { n = n or ((bytes[offset + it].toLong() and 255) shl (8 * it)) }; return n }
}

/** Portable static-PNG validation: CRCs, chunk ordering, complete zlib consumption, all scanlines
 * and filters (including Adam7), and palette indices. Compressed ancillary metadata is forbidden. */
internal object PngCheck {
    data class Info(val width: Int, val height: Int) { val decodedBytes: Long get() = width.toLong() * height * 4 }
    fun inspect(bytes: ByteArray, cancelled: () -> Boolean = { false }): Info {
        val signature = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)
        checkPack(bytes.size <= FacePackLimits.MAX_PNG_BYTES && bytes.size >= 45 && bytes.copyOfRange(0, 8).contentEquals(signature), "Asset is not a supported PNG")
        var at = 8; var chunks = 0; var width = 0; var height = 0; var depth = 0; var color = -1; var interlace = -1
        var palette = 0; var idat = false; var idatClosed = false; var ended = false
        val once = mutableSetOf<String>()
        val compressed = ByteArrayOutputStream()
        while (at < bytes.size) {
            checkCancelled(cancelled)
            checkPack(++chunks <= 1024 && at.toLong() + 12 <= bytes.size, "PNG chunk complexity or bounds exceeded")
            val lengthLong = be32(bytes, at)
            checkPack(lengthLong <= bytes.size - at - 12L, "Truncated PNG chunk")
            val length = lengthLong.toInt()
            val typeBytes = bytes.copyOfRange(at + 4, at + 8)
            checkPack(typeBytes.all { it.toInt() in 'A'.code..'Z'.code || it.toInt() in 'a'.code..'z'.code } && typeBytes[2].toInt() and 32 == 0, "Invalid PNG chunk type")
            val type = typeBytes.toString(Charsets.US_ASCII)
            val data = at + 8
            val crc = CRC32().apply { update(bytes, at + 4, length + 4) }.value
            checkPack(crc == be32(bytes, data + length), "PNG chunk CRC mismatch")
            checkPack(chunks != 1 || type == "IHDR", "PNG header must be first")
            if (idat && type != "IDAT") idatClosed = true
            when (type) {
                "IHDR" -> {
                    checkPack(chunks == 1 && length == 13 && once.add(type), "Invalid PNG header")
                    val w = be32(bytes, data); val h = be32(bytes, data + 4)
                    checkPack(w in 1..FacePackLimits.MAX_IMAGE_SIDE.toLong() && h in 1..FacePackLimits.MAX_IMAGE_SIDE.toLong() && w * h <= FacePackLimits.MAX_IMAGE_PIXELS, "PNG dimensions exceed limits")
                    width = w.toInt(); height = h.toInt(); depth = bytes[data + 8].toInt() and 255; color = bytes[data + 9].toInt() and 255
                    val allowed = when (color) { 0 -> setOf(1, 2, 4, 8, 16); 2, 4, 6 -> setOf(8, 16); 3 -> setOf(1, 2, 4, 8); else -> emptySet() }
                    checkPack(depth in allowed && bytes[data + 10] == 0.toByte() && bytes[data + 11] == 0.toByte(), "Unsupported PNG encoding")
                    interlace = bytes[data + 12].toInt() and 255
                    checkPack(interlace in 0..1, "Unsupported PNG interlace")
                }
                "PLTE" -> {
                    checkPack(!idat && once.add(type) && color in setOf(2, 3, 6) && length in 3..768 && length % 3 == 0, "Invalid PNG palette")
                    palette = length / 3
                    checkPack(color != 3 || palette <= 1 shl depth, "PNG palette exceeds bit depth")
                }
                "tRNS" -> {
                    checkPack(!idat && once.add(type), "Invalid PNG transparency ordering")
                    checkPack(when (color) { 0 -> length == 2; 2 -> length == 6; 3 -> palette > 0 && length in 1..palette; else -> false }, "Invalid PNG transparency")
                    if (color == 0 || color == 2) for (i in 0 until length step 2) {
                        val sample = ((bytes[data + i].toInt() and 255) shl 8) or (bytes[data + i + 1].toInt() and 255)
                        checkPack(depth == 16 || sample < 1 shl depth, "PNG transparency exceeds bit depth")
                    }
                }
                "sBIT" -> {
                    // Static uncompressed metadata emitted by Android/Skia's PNG encoder.
                    // PNG requires one value per source channel (indexed palettes use RGB/8-bit),
                    // strictly before PLTE/IDAT. This does not admit profiles, text, or animation.
                    val expected = when (color) { 0 -> 1; 2, 3 -> 3; 4 -> 2; 6 -> 4; else -> 0 }
                    val maximum = if (color == 3) 8 else depth
                    checkPack(!idat && "PLTE" !in once && once.add(type) && length == expected,
                        "Invalid PNG significant-bits ordering or length")
                    for (i in 0 until length) checkPack((bytes[data + i].toInt() and 255) in 1..maximum,
                        "PNG significant bits exceed sample depth")
                }
                "IDAT" -> {
                    checkPack(!idatClosed && (color != 3 || palette > 0), "Invalid PNG image-data ordering")
                    idat = true; compressed.write(bytes, data, length)
                }
                "IEND" -> { checkPack(idat && length == 0 && at + 12 == bytes.size, "Invalid PNG end"); ended = true }
                "sRGB", "gAMA", "cHRM", "pHYs", "tIME" -> {
                    val expected = when (type) { "sRGB" -> 1; "gAMA" -> 4; "cHRM" -> 32; "pHYs" -> 9; else -> 7 }
                    checkPack(length == expected && once.add(type) && (!idat || type == "tIME"), "Invalid PNG metadata")
                    if (type == "sRGB") checkPack((bytes[data].toInt() and 255) <= 3, "Invalid PNG color intent")
                    if (type == "gAMA") checkPack(be32(bytes, data) > 0, "Invalid PNG gamma")
                    if (type == "pHYs") checkPack((bytes[data + 8].toInt() and 255) <= 1, "Invalid PNG physical-unit metadata")
                }
                else -> throw FacePackException("Animated or unsupported PNG metadata: $type")
            }
            at += length + 12
        }
        checkPack(ended && idat && compressed.size() > 0, "Incomplete PNG")
        validateScanlines(compressed.toByteArray(), width, height, depth, color, interlace, palette, cancelled)
        return Info(width, height)
    }

    private fun validateScanlines(compressed: ByteArray, width: Int, height: Int, depth: Int, color: Int, interlace: Int, palette: Int, cancelled: () -> Boolean) {
        val channels = when (color) { 0, 3 -> 1; 2 -> 3; 4 -> 2; else -> 4 }
        val bitsPerPixel = channels * depth
        val pixelBytes = maxOf(1, (bitsPerPixel + 7) / 8)
        val inflater = Inflater()
        try {
            inflater.setInput(compressed)
            fun fill(out: ByteArray) {
                var offset = 0
                while (offset < out.size) {
                    checkCancelled(cancelled)
                    val n = inflater.inflate(out, offset, out.size - offset)
                    checkPack(n > 0, "PNG image stream is truncated or invalid")
                    offset += n
                }
            }
            val passes = if (interlace == 0) arrayOf(intArrayOf(0, 0, 1, 1)) else arrayOf(
                intArrayOf(0, 0, 8, 8), intArrayOf(4, 0, 8, 8), intArrayOf(0, 4, 4, 8), intArrayOf(2, 0, 4, 4),
                intArrayOf(0, 2, 2, 4), intArrayOf(1, 0, 2, 2), intArrayOf(0, 1, 1, 2))
            val filter = ByteArray(1)
            for (pass in passes) {
                val w = maxOf(0, (width - pass[0] + pass[2] - 1) / pass[2])
                val h = maxOf(0, (height - pass[1] + pass[3] - 1) / pass[3])
                if (w == 0 || h == 0) continue
                val rowBytes = (w * bitsPerPixel + 7) / 8
                var previous = ByteArray(rowBytes)
                repeat(h) {
                    checkCancelled(cancelled)
                    fill(filter); val mode = filter[0].toInt() and 255
                    checkPack(mode in 0..4, "Invalid PNG scanline filter")
                    val row = ByteArray(rowBytes); fill(row)
                    for (i in row.indices) {
                        val left = if (i >= pixelBytes) row[i - pixelBytes].toInt() and 255 else 0
                        val up = previous[i].toInt() and 255
                        val upperLeft = if (i >= pixelBytes) previous[i - pixelBytes].toInt() and 255 else 0
                        val predict = when (mode) { 1 -> left; 2 -> up; 3 -> (left + up) / 2; 4 -> paeth(left, up, upperLeft); else -> 0 }
                        row[i] = ((row[i].toInt() and 255) + predict).toByte()
                    }
                    if (color == 3) for (x in 0 until w) {
                        val bit = x * depth
                        val index = ((row[bit / 8].toInt() and 255) ushr (8 - depth - bit % 8)) and ((1 shl depth) - 1)
                        checkPack(index < palette, "PNG palette index is invalid")
                    }
                    previous = row
                }
            }
            checkPack(inflater.inflate(ByteArray(1)) == 0 && inflater.finished() && inflater.remaining == 0, "PNG image stream has trailing or incomplete data")
        } catch (e: DataFormatException) { throw FacePackException("Invalid PNG image stream", e) }
        finally { inflater.end() }
    }

    private fun paeth(a: Int, b: Int, c: Int): Int { val p = a + b - c; val pa = kotlin.math.abs(p - a); val pb = kotlin.math.abs(p - b); val pc = kotlin.math.abs(p - c); return if (pa <= pb && pa <= pc) a else if (pb <= pc) b else c }
    private fun be32(bytes: ByteArray, offset: Int): Long { checkPack(offset >= 0 && offset.toLong() + 4 <= bytes.size, "Truncated PNG integer"); var n = 0L; repeat(4) { n = (n shl 8) or (bytes[offset + it].toLong() and 255) }; return n }
}
