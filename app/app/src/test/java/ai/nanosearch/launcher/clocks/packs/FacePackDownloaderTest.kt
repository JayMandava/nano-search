package ai.nanosearch.launcher.clocks.packs

import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.InetAddress
import java.net.URI
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class FacePackDownloaderTest {
    private val root = Files.createTempDirectory("nano-download-").toFile()
    private val data = "original test pack bytes".toByteArray()
    private val url = "https://github.com/JayMandava/nano-search/releases/download/pack-v1/original.zip"
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun entry(bytes: ByteArray = data, address: String = url) = FacePackCatalogEntry("original", "original", 1, "Original", address, bytes.size.toLong(), hash(bytes))
    @After fun clean() { root.deleteRecursively() }
    private class Response(override val status: Int = 200, val headers: Map<String, String> = emptyMap(), override val body: InputStream) : FacePackHttpCall {
        var closed = false
        override fun header(name: String) = headers.entries.firstOrNull { it.key.equals(name, true) }?.value
        override fun cancel() { body.close() }
        override fun close() { closed = true; body.close() }
    }
    private class Transport(val answer: (URI) -> FacePackHttpCall) : FacePackHttpTransport {
        val requests = mutableListOf<URI>()
        override fun open(uri: URI, remainingMillis: Long, onCancelReady: (() -> Unit) -> Unit): FacePackHttpCall {
            requests += uri
            return answer(uri).also { call -> onCancelReady { call.cancel() } }
        }
    }
    private fun response(bytes: ByteArray = data, headers: Map<String, String> = emptyMap(), status: Int = 200) = Response(status, headers, bytes.inputStream())
    private fun reject(reason: FacePackFailure, action: () -> Unit) {
        try { action(); fail("Expected rejection: $reason") } catch (e: FacePackOperationException) { assertEquals(e.message, reason, e.reason) }
    }
    @Test fun frozenCatalogIsInitiallyEmptyAndRejectsUnlistedEntryBeforeIo() {
        assertTrue(FacePackCatalog.entries.isEmpty())
        val transport = Transport { error("No connection allowed") }
        reject(FacePackFailure.UNSUPPORTED_SOURCE) { FacePackDownloader(transport = transport).download(entry(), File(root, "new.zip")) }
        assertTrue(transport.requests.isEmpty()); assertTrue(root.listFiles()!!.isEmpty())
    }
    @Test fun exactFirstPartyInitialRoutesAreAllowed() {
        assertEquals("github.com", FacePackUrlPolicy.validate(url, true).host)
        assertEquals("raw.githubusercontent.com", FacePackUrlPolicy.validate("https://raw.githubusercontent.com/JayMandava/nano-search/${"a".repeat(40)}/packs/original.zip", true).host)
        assertEquals(443, FacePackUrlPolicy.validate(url.replace("github.com/", "github.com:443/"), true).port)
    }
    @Test fun hostileOriginsAliasesAndMutableRawRefsAreRejectedBeforeTransport() {
        val invalid = listOf(url.replace("https:", "http:"), url.replace("github.com", "github.com.evil"), url.replace("github.com", "github.com@evil.com"), url.replace("github.com", "GitHub.com"), url.replace("github.com", "127.0.0.1"), url.replace("github.com", "github.com:444"), url.replace("JayMandava", "other"), "$url#fragment", "$url?token=x", url.replace("original.zip", "../original.zip"), url.replace("original.zip", "%2e%2e/original.zip"), url.replace("original.zip", "original.zip/"), "file:///tmp/a.zip", "content://provider/a.zip", "https://raw.githubusercontent.com/JayMandava/nano-search/main/packs/a.zip", "https://release-assets.githubusercontent.com/github-production-release-asset/1/a.zip")
        invalid.forEach { value -> reject(FacePackFailure.UNSUPPORTED_SOURCE) { FacePackUrlPolicy.validate(value, true) } }
    }
    @Test fun verifiedDownloadWorksWithoutContentLengthAndReportsActualBytes() {
        val accepted = entry(); val output = File(root, "a.zip"); val values = mutableListOf<FacePackProgress>()
        val transport = Transport { response() }
        assertEquals(accepted.sha256, FacePackDownloader(listOf(accepted), transport).download(accepted, output, progress = values::add))
        assertArrayEquals(data, output.readBytes()); assertEquals(data.size.toLong(), values.last().bytes)
    }
    @Test fun rejectedDownloadNeverDeletesAnExistingDestination() {
        val accepted = entry(); val output = File(root, "sentinel.zip").apply { writeText("do not delete") }
        reject(FacePackFailure.STORAGE) { FacePackDownloader(listOf(accepted), Transport { error("No connection") }).download(accepted, output) }
        assertEquals("do not delete", output.readText())
    }
    @Test fun onlyValidatedRedirectHopIsRequested() {
        val accepted = entry(); val redirect = "https://release-assets.githubusercontent.com/github-production-release-asset/123/a.zip?sig=bounded"
        val transport = Transport { if (it.host == "github.com") response(status = 302, headers = mapOf("Location" to redirect)) else response() }
        FacePackDownloader(listOf(accepted), transport).download(accepted, File(root, "a.zip"))
        assertEquals(listOf(url, redirect), transport.requests.map { it.toString() })
        for (target in listOf("https://evil.test/a.zip", "http://github.com/a.zip", "https://raw.githubusercontent.com/JayMandava/nano-search/${"a".repeat(40)}/a.zip", "http://[malformed")) {
            val bad = Transport { response(status = 302, headers = mapOf("Location" to target)) }
            reject(FacePackFailure.UNSUPPORTED_SOURCE) { FacePackDownloader(listOf(accepted), bad).download(accepted, File(root, "bad.zip")) }
            assertEquals(1, bad.requests.size); assertFalse(File(root, "bad.zip").exists())
        }
    }
    @Test fun redirectLoopsAndFourthHopFailBeforeExtraRequest() {
        val accepted = entry()
        val looping = Transport { response(status = 302, headers = mapOf("Location" to url)) }
        reject(FacePackFailure.UNSUPPORTED_SOURCE) { FacePackDownloader(listOf(accepted), looping).download(accepted, File(root, "loop")) }
        assertEquals(1, looping.requests.size)
        var n = 0
        val many = Transport { response(status = 302, headers = mapOf("Location" to "https://release-assets.githubusercontent.com/github-production-release-asset/1/${++n}.zip")) }
        reject(FacePackFailure.UNSUPPORTED_SOURCE) { FacePackDownloader(listOf(accepted), many).download(accepted, File(root, "many")) }
        assertEquals(4, many.requests.size)
    }
    @Test fun statusesHtmlGzipFalseLengthTruncationAndChangedBytesFailClosed() {
        val accepted = entry()
        val cases = listOf(
            FacePackFailure.SOURCE_UNAVAILABLE to response(status = 403),
            FacePackFailure.SOURCE_UNAVAILABLE to response(status = 429),
            FacePackFailure.VERIFICATION to response(headers = mapOf("Content-Type" to "text/html")),
            FacePackFailure.VERIFICATION to response(headers = mapOf("Content-Encoding" to "gzip")),
            FacePackFailure.VERIFICATION to response(headers = mapOf("Content-Length" to "999")),
            FacePackFailure.VERIFICATION to response(headers = mapOf("Content-Length" to "-1")),
            FacePackFailure.VERIFICATION to response(data.copyOf(data.size - 1)),
            FacePackFailure.VERIFICATION to response(data + byteArrayOf(0)),
            FacePackFailure.VERIFICATION to response(data.copyOf().apply { this[0] = 0 }),
        )
        cases.forEachIndexed { i, (reason, r) ->
            val destination = File(root, "rejected-$i")
            reject(reason) { FacePackDownloader(listOf(accepted), Transport { r }).download(accepted, destination) }
            assertFalse(destination.exists()); assertTrue(r.closed)
        }
    }
    @Test fun originalDeadlineIsNotResetAcrossRedirects() {
        val accepted = entry(); val clock = AtomicLong()
        val transport = Transport { clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(60)); response(status = 302, headers = mapOf("Location" to "https://release-assets.githubusercontent.com/github-production-release-asset/1/${clock.get()}.zip")) }
        reject(FacePackFailure.TIMEOUT) { FacePackDownloader(listOf(accepted), transport, clock::get, 100).download(accepted, File(root, "deadline")) }
        assertEquals(2, transport.requests.size); assertFalse(File(root, "deadline").exists())
    }
    @Test fun cancelAbortsBlockedHeadersAndCleansPrivateDownload() {
        val accepted = entry(); val cancel = AtomicBoolean(); val started = CountDownLatch(1); val aborted = CountDownLatch(1)
        val transport = object : FacePackHttpTransport {
            override fun open(uri: URI, remainingMillis: Long, onCancelReady: (() -> Unit) -> Unit): FacePackHttpCall {
                onCancelReady { aborted.countDown() }; started.countDown()
                if (!aborted.await(3, TimeUnit.SECONDS)) error("Cancellation did not abort transport")
                throw java.io.IOException("Canceled")
            }
        }
        val canceller = thread { assertTrue(started.await(2, TimeUnit.SECONDS)); cancel.set(true) }
        reject(FacePackFailure.CANCELLED) { FacePackDownloader(listOf(accepted), transport).download(accepted, File(root, "cancel"), cancel::get) }
        canceller.join(); assertFalse(File(root, "cancel").exists())
    }
    @Test fun explicitTestTransportCanExerciseRealLocalHttpWithoutPermittingLocalhostInProduction() {
        val fixture = listOf("src/androidTest/assets", "app/src/androidTest/assets", "app/app/src/androidTest/assets")
            .map { File(it, "clock-packs/orbit-blue.zip") }.firstOrNull { it.isFile }
            ?: error("Original reproducible sample fixture is missing")
        val original = fixture.readBytes()
        assertEquals("f12204deb3d998843c77048be0f7e5803307246d49bba047aa017e8ac1e0363f", hash(original))
        val accepted = entry(original).copy(packId = "orbit-blue")
        val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val serving = thread(isDaemon = true, name = "pack-test-http") {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (_: java.io.IOException) { break }
                socket.use {
                    it.soTimeout = 2000
                    val input = it.getInputStream().bufferedReader(Charsets.US_ASCII)
                    var line = input.readLine()
                    while (line != null && line.isNotEmpty()) line = input.readLine()
                    it.getOutputStream().use { output ->
                        output.write("HTTP/1.1 200 OK\r\nContent-Type: application/zip\r\nContent-Length: ${original.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                        output.write(original); output.flush()
                    }
                }
            }
        }
        try {
            val transport = object : FacePackHttpTransport {
                override fun open(uri: URI, remainingMillis: Long, onCancelReady: (() -> Unit) -> Unit): FacePackHttpCall {
                    assertEquals(url, uri.toString()) // Only this test redirects its validated logical URL.
                    val connection = URI("http://127.0.0.1:${server.localPort}/pack").toURL().openConnection() as HttpURLConnection
                    connection.instanceFollowRedirects = false; connection.connectTimeout = 2000; connection.readTimeout = 2000
                    onCancelReady { connection.disconnect() }
                    return object : FacePackHttpCall {
                        override val status = connection.responseCode
                        override fun header(name: String) = connection.getHeaderField(name)
                        override val body get() = connection.inputStream
                        override fun cancel() = connection.disconnect()
                        override fun close() = connection.disconnect()
                    }
                }
            }
            val output = File(root, "local.zip")
            FacePackDownloader(listOf(accepted), transport).download(accepted, output)
            assertArrayEquals(original, output.readBytes())
            val validated = FacePackArchive.validateAndExtract(output, File(root, "payload"), 14)
            assertEquals("orbit-blue", validated.spec.packId)
            val storeRoot = File(root, "store").apply { mkdir() }
            val index = object : FacePackIndexIO {
                val pointer = File(storeRoot, "registry.bin")
                override fun read() = pointer.takeIf { it.exists() }?.readBytes()
                override fun write(bytes: ByteArray) { pointer.writeBytes(bytes) }
                override fun syncDirectory(directory: File) { }
            }
            val validation = object : FacePackValidation {
                override fun extract(zip: File, destination: File, appVersionCode: Int, cancelled: () -> Boolean) = FacePackArchive.validateAndExtract(zip, destination, appVersionCode, cancelled)
                override fun installed(zip: File, payload: File, appVersionCode: Int, digest: String) = FacePackArchive.validateInstalled(zip, payload, appVersionCode, digest)
                override fun androidDecode(pack: ValidatedFacePack, cancelled: () -> Boolean) { /* Instrumentation tests exercise platform decode. */ }
            }
            val store = FacePackStore(storeRoot, 14, index, validation, FacePackDownloader(listOf(accepted), transport), listOf(accepted))
            val curated = store.downloadPack(accepted)
            val imported = store.importPack({ original.inputStream() })
            assertEquals(FacePackSource.CURATED, curated.source)
            assertEquals(FacePackSource.IMPORTED, imported.source)
            assertNotEquals(curated.id, imported.id)
            assertEquals(curated.spec.packId, imported.spec.packId)
            assertEquals(2, store.snapshot().packs.size)
            store.remove(imported.id)
            assertNotNull(store.find(curated.id))
            reject(FacePackFailure.UNSUPPORTED_SOURCE) { FacePackUrlPolicy.validate("http://127.0.0.1:${server.localPort}/pack", true) }
        } finally { server.close(); serving.join(2000) }
    }
}
