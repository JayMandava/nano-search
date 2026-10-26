package ai.nanosearch.launcher.clocks.packs

import okhttp3.Authenticator
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.UnknownHostException
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Production URL policy is intentionally not configurable by UI, an import, or a downloaded file. */
internal object FacePackUrlPolicy {
    private val rawPath = Regex("/JayMandava/nano-search/[0-9a-f]{40}/[A-Za-z0-9._/-]+\\.zip")
    private val releasePath = Regex("/JayMandava/nano-search/releases/download/[A-Za-z0-9._-]+/[A-Za-z0-9._-]+\\.zip")

    fun validate(value: String, initial: Boolean): URI {
        val u = try { URI(value) } catch (e: Exception) { unsupported() }
        if (value.length > 4096 || u.scheme != "https" || u.rawUserInfo != null || u.rawFragment != null ||
            u.port !in listOf(-1, 443) || u.host == null || u.host != u.host.lowercase() ||
            u.rawAuthority?.contains('@') == true || u.rawPath.contains('%') || u.rawPath.contains('\\') ||
            u.rawPath.split('/').any { it == "." || it == ".." } || (u.rawQuery?.length ?: 0) > 2048) unsupported()
        when (u.host) {
            "github.com" -> if (!releasePath.matches(u.rawPath) || u.rawQuery != null) unsupported()
            "raw.githubusercontent.com" -> if (!rawPath.matches(u.rawPath) || u.rawQuery != null || !initial) unsupported()
            "release-assets.githubusercontent.com" -> {
                if (initial || !u.rawPath.startsWith("/github-production-release-asset/") || u.rawPath.length <= 33) unsupported()
                if (!Regex("/[A-Za-z0-9._/-]+").matches(u.rawPath)) unsupported()
            }
            else -> unsupported()
        }
        return u
    }

    private fun unsupported(): Nothing = throw FacePackOperationException(FacePackFailure.UNSUPPORTED_SOURCE, "Download source is not supported")
}

/** A narrow transport boundary permits deterministic tests without weakening the production URL policy. */
internal interface FacePackHttpTransport {
    fun open(uri: URI, remainingMillis: Long, onCancelReady: (() -> Unit) -> Unit): FacePackHttpCall
}
internal interface FacePackHttpCall : AutoCloseable {
    val status: Int
    fun header(name: String): String?
    val body: InputStream
    fun cancel()
}

internal class OkHttpFacePackTransport : FacePackHttpTransport {
    private val client = OkHttpClient.Builder()
        .cookieJar(CookieJar.NO_COOKIES)
        .authenticator(Authenticator.NONE)
        .proxyAuthenticator(Authenticator.NONE)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(120, TimeUnit.SECONDS)
        .build()

    override fun open(uri: URI, remainingMillis: Long, onCancelReady: (() -> Unit) -> Unit): FacePackHttpCall {
        val request = Request.Builder().url(uri.toASCIIString())
            .header("Accept-Encoding", "identity")
            .header("Accept", "application/zip, application/octet-stream")
            .header("User-Agent", "Nano-Face-Packs/1")
            .get().build()
        val call = client.newCall(request)
        call.timeout().timeout(remainingMillis.coerceAtLeast(1), TimeUnit.MILLISECONDS)
        onCancelReady { call.cancel() }
        val response = call.execute()
        return object : FacePackHttpCall {
            override val status = response.code
            override fun header(name: String) = response.header(name)
            override val body: InputStream get() = response.body?.byteStream()
                ?: throw FacePackOperationException(FacePackFailure.SOURCE_UNAVAILABLE, "Download source returned no data")
            override fun cancel() = call.cancel()
            override fun close() = response.close()
        }
    }
}

class FacePackDownloader internal constructor(
    private val trustedCatalog: List<FacePackCatalogEntry> = FacePackCatalog.entries,
    private val transport: FacePackHttpTransport = OkHttpFacePackTransport(),
    private val nowNanos: () -> Long = System::nanoTime,
    private val totalTimeoutMillis: Long = 120_000,
) {
    /** destination must be a generated, non-existing private staging path. Returns the verified ZIP hash. */
    fun download(
        entry: FacePackCatalogEntry,
        destination: File,
        cancelled: () -> Boolean = { false },
        progress: (FacePackProgress) -> Unit = {},
    ): String {
        if (entry !in trustedCatalog || !Regex("[a-z][a-z0-9-]{0,63}").matches(entry.id) ||
            !Regex("[a-z][a-z0-9-]{0,63}").matches(entry.packId) || entry.version < 1 ||
            entry.bytes !in 1..FacePackLimits.MAX_ZIP_BYTES || !Regex("[a-f0-9]{64}").matches(entry.sha256)) {
            throw FacePackOperationException(FacePackFailure.UNSUPPORTED_SOURCE, "This face is not in the app's trusted catalog")
        }
        var current = FacePackUrlPolicy.validate(entry.url, true)
        val deadline = nowNanos() + TimeUnit.MILLISECONDS.toNanos(totalTimeoutMillis)
        val active = AtomicReference<FacePackHttpCall?>()
        val cancelActive = AtomicReference<(() -> Unit)?>()
        val requestThread = Thread.currentThread()
        val timedOut = AtomicBoolean(false)
        val wasCancelled = AtomicBoolean(false)
        val watcher = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "face-download-cancel").apply { isDaemon = true } }
        val watch = watcher.scheduleWithFixedDelay({
            if (cancelled() || requestThread.isInterrupted) { wasCancelled.set(true); cancelActive.get()?.invoke() }
            if (nowNanos() >= deadline) { timedOut.set(true); cancelActive.get()?.invoke() }
        }, 0, 50, TimeUnit.MILLISECONDS)
        var success = false
        var created = false
        try {
            checkTransfer(cancelled, deadline)
            if (!destination.createNewFile()) throw FacePackOperationException(FacePackFailure.STORAGE, "Could not create private download staging")
            created = true
            val seen = hashSetOf<String>()
            var redirects = 0
            while (true) {
                checkTransfer(cancelled, deadline)
                if (!seen.add(current.toASCIIString())) throw FacePackOperationException(FacePackFailure.UNSUPPORTED_SOURCE, "Download redirect loop")
                val remaining = TimeUnit.NANOSECONDS.toMillis(deadline - nowNanos()).coerceAtLeast(1)
                transport.open(current, remaining) { cancel ->
                    cancelActive.set(cancel)
                    if (wasCancelled.get() || timedOut.get()) cancel()
                }.use { response ->
                    active.set(response)
                    checkTransfer(cancelled, deadline)
                    if (response.status in setOf(301, 302, 303, 307, 308)) {
                        if (++redirects > 3) throw FacePackOperationException(FacePackFailure.UNSUPPORTED_SOURCE, "Too many download redirects")
                        val next = response.header("Location") ?: throw FacePackOperationException(FacePackFailure.SOURCE_UNAVAILABLE, "Download redirect is missing its destination")
                        val resolved = try { current.resolve(next).toString() } catch (e: IllegalArgumentException) {
                            throw FacePackOperationException(FacePackFailure.UNSUPPORTED_SOURCE, "Download redirect is not supported", e)
                        }
                        current = FacePackUrlPolicy.validate(resolved, false)
                    } else {
                        if (response.status != 200) throw FacePackOperationException(FacePackFailure.SOURCE_UNAVAILABLE,
                            if (response.status == 429) "Download source is rate-limited; try again later" else "Download source is unavailable")
                        val encoding = response.header("Content-Encoding")
                        if (encoding != null && !encoding.equals("identity", true)) throw FacePackOperationException(FacePackFailure.VERIFICATION, "Compressed HTTP responses are not supported")
                        val type = response.header("Content-Type")?.substringBefore(';')?.trim()?.lowercase()
                        if (type != null && type !in setOf("application/zip", "application/octet-stream", "application/x-zip-compressed")) {
                            throw FacePackOperationException(FacePackFailure.VERIFICATION, "Download source did not return a ZIP pack")
                        }
                        response.header("Content-Length")?.let { raw ->
                            if (!Regex("[0-9]{1,12}").matches(raw) || raw.toLongOrNull() != entry.bytes) {
                                throw FacePackOperationException(FacePackFailure.VERIFICATION, "Download length does not match the catalog")
                            }
                        }
                        val hash = MessageDigest.getInstance("SHA-256")
                        var count = 0L
                        FileOutputStream(destination, false).use { output ->
                            response.body.use { input ->
                                val buffer = ByteArray(8192)
                                while (true) {
                                    checkTransfer(cancelled, deadline)
                                    val n = input.read(buffer)
                                    if (n < 0) break
                                    if (n == 0) continue
                                    count += n
                                    if (count > entry.bytes || count > FacePackLimits.MAX_ZIP_BYTES) throw FacePackOperationException(FacePackFailure.VERIFICATION, "Download exceeds its verified size")
                                    hash.update(buffer, 0, n); output.write(buffer, 0, n)
                                    runCatching { progress(FacePackProgress(FacePackStage.DOWNLOADING, count, entry.bytes)) }
                                }
                            }
                            output.fd.sync()
                        }
                        checkTransfer(cancelled, deadline)
                        val digest = hash.digest().joinToString("") { "%02x".format(it) }
                        if (count != entry.bytes || digest != entry.sha256) throw FacePackOperationException(FacePackFailure.VERIFICATION, "Download verification failed")
                        success = true
                        return digest
                    }
                    active.set(null)
                    cancelActive.set(null)
                }
            }
        } catch (e: FacePackOperationException) { throw e }
        catch (e: IOException) {
            when {
                wasCancelled.get() || cancelled() || Thread.currentThread().isInterrupted -> throw FacePackOperationException(FacePackFailure.CANCELLED, "Download cancelled", e)
                timedOut.get() || nowNanos() >= deadline || e is java.net.SocketTimeoutException -> throw FacePackOperationException(FacePackFailure.TIMEOUT, "Download timed out", e)
                facePackStorageFailure(e) -> throw FacePackOperationException(FacePackFailure.STORAGE, "Not enough space to save this face", e)
                e is UnknownHostException -> throw FacePackOperationException(FacePackFailure.OFFLINE, "Could not reach the download source", e)
                else -> throw FacePackOperationException(FacePackFailure.SOURCE_UNAVAILABLE, "Download could not complete", e)
            }
        } finally {
            runCatching { active.getAndSet(null)?.close() }
            watch.cancel(true); watcher.shutdownNow()
            if (created && !success) destination.delete()
        }
    }

    private fun checkTransfer(cancelled: () -> Boolean, deadline: Long) {
        if (cancelled() || Thread.currentThread().isInterrupted) throw FacePackOperationException(FacePackFailure.CANCELLED, "Download cancelled")
        if (nowNanos() >= deadline) throw FacePackOperationException(FacePackFailure.TIMEOUT, "Download timed out")
    }
}
