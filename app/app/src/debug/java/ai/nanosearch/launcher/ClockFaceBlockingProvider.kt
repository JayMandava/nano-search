package ai.nanosearch.launcher

import android.content.ContentProvider
import android.content.ClipDescription
import android.content.ContentValues
import android.content.Context
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.net.Uri
import android.os.CancellationSignal
import android.os.Bundle
import java.io.FileNotFoundException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Debug-only, non-exported provider: deterministic cancellation while opening, never real user data. */
class ClockFaceBlockingProvider : ContentProvider() {
    override fun onCreate() = true
    override fun getType(uri: Uri) = "application/zip"
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    // ContentResolver.openAssetFileDescriptor(contentUri, "r", signal) dispatches through
    // openTypedAssetFile("*/*", ..., signal). The framework's default typed implementation
    // falls back to the TWO-argument openAssetFile and drops the signal, bypassing our override.
    override fun openTypedAssetFile(uri: Uri, mimeTypeFilter: String, opts: Bundle?, signal: CancellationSignal?): AssetFileDescriptor {
        if (!ClipDescription.compareMimeTypes("application/zip", mimeTypeFilter)) throw FileNotFoundException("Unsupported test MIME type")
        typedRequests.incrementAndGet()
        return openAssetFile(uri, "r", signal)
    }

    override fun openAssetFile(uri: Uri, mode: String, signal: CancellationSignal?): AssetFileDescriptor {
        if (uri.lastPathSegment != "blocked-open" || mode != "r") throw FileNotFoundException("Test-only source")
        val localGate = gate
        val localCancelled = cancellationObserved
        signal?.setOnCancelListener { localCancelled.countDown(); localGate.countDown() }
        if (signal != null) cancellableRequests.incrementAndGet()
        requests.incrementAndGet()
        opened.countDown()
        try {
            if (!localGate.await(90, TimeUnit.SECONDS)) throw FileNotFoundException("Test source timed out")
            signal?.throwIfCanceled()
            throw FileNotFoundException("Test source released")
        } finally { signal?.setOnCancelListener(null) }
    }

    companion object {
        val requests = AtomicInteger()
        val typedRequests = AtomicInteger()
        val cancellableRequests = AtomicInteger()
        @Volatile var opened = CountDownLatch(1); private set
        @Volatile var cancellationObserved = CountDownLatch(1); private set
        @Volatile private var gate = CountDownLatch(1)
        fun uri(context: Context): Uri = Uri.parse("content://${context.packageName}.clockfaces.test/blocked-open")
        @Synchronized fun reset() {
            gate.countDown()
            gate = CountDownLatch(1); opened = CountDownLatch(1); cancellationObserved = CountDownLatch(1)
            requests.set(0); typedRequests.set(0); cancellableRequests.set(0)
        }
        fun release() { gate.countDown() }
    }
}
