package ai.nanosearch.launcher

import ai.nanosearch.launcher.clocks.ClockFaces
import ai.nanosearch.launcher.clocks.ClockFaceRegistry
import ai.nanosearch.launcher.clocks.PackClockApply
import ai.nanosearch.launcher.clocks.packs.*
import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.InterruptedIOException
import java.util.Calendar
import java.util.TimeZone
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal data class FaceOperation(val title: String, val progress: FacePackProgress, val cancellable: Boolean, val cancelling: Boolean = false)
internal sealed interface FaceSource {
    data class Document(val uri: Uri) : FaceSource
    data class Catalog(val entry: FacePackCatalogEntry) : FaceSource
}
internal data class FaceUpdateReview(val source: FaceSource, val existingId: String, val name: String, val version: Int, val digest: String)

/** Retains the one foreground operation over rotation; no Activity, URI grant, or renderer is retained. */
class ClockFacesModel(application: Application) : AndroidViewModel(application) {
    private val context: Application = application
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "clock-faces-ui-work").apply { isDaemon = true } }
    private val closed = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)
    private val activeInput = AtomicReference<InputStream?>(null)
    private val providerSignal = AtomicReference<CancellationSignal?>(null)
    private val store = ClockFaceRegistry.ensure(context)
    internal var snapshot by mutableStateOf(store.snapshot()); private set
    internal var operation by mutableStateOf<FaceOperation?>(null); private set
    internal var updateReview by mutableStateOf<FaceUpdateReview?>(null); private set
    private val notices = ClockNotices()
    internal val notice get() = notices.text
    internal val noticeRevision get() = notices.revision
    internal var selectedId by mutableStateOf<String?>(null)
    private val observation = store.observe { post { snapshot = store.snapshot() } }
    private val previewMutex = Mutex()
    // At most 8 MiB, including the detail image. Eviction drops references, never recycles a visible bitmap.
    private val previews = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    internal var previewTimeZoneKey by mutableStateOf(TimeZone.getDefault().id); private set
    internal fun refreshPreviewTimeZone() { previewTimeZoneKey = TimeZone.getDefault().id }
    private fun previewEpoch() = Calendar.getInstance().apply {
        set(2026, Calendar.OCTOBER, 15, 10, 10, 30); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun post(block: () -> Unit) { if (!closed.get()) main.post { if (!closed.get()) block() } }
    internal fun message(value: String) { notices.show(value) }
    internal fun dismissNotice() { notices.clear() }
    internal fun expireNotice(revision: Long) { notices.expire(revision) }
    internal fun dismissUpdate() { updateReview = null }

    internal suspend fun preview(id: String, generation: Long, size: Int): Bitmap? = withContext(Dispatchers.Default) {
        previewMutex.withLock {
            val pack = store.find(id)
            if (ClockFaces.isPackId(id) && pack == null) return@withLock null
            val key = "$id:${pack?.digest.orEmpty()}:$generation:$size:${TimeZone.getDefault().id}"
            previews.get(key) ?: runCatching { ClockFaces.preview(context, id, size, previewEpoch()) }.getOrNull()?.also { previews.put(key, it) }
        }
    }

    internal fun importDocument(uri: Uri) = install(FaceSource.Document(uri))
    internal fun download(entry: FacePackCatalogEntry) = install(FaceSource.Catalog(entry))
    internal fun confirmUpdate() {
        val review = updateReview ?: return
        updateReview = null
        install(review.source, review)
    }

    private fun install(source: FaceSource, review: FaceUpdateReview? = null) {
        val previousId = review?.existingId
        start(if (review == null) "Install clock face" else "Update clock face", cancellable = true, source = source, previousId = previousId) {
            val progress: (FacePackProgress) -> Unit = { value -> post { operation = operation?.copy(progress = value) } }
            val installed = when (source) {
                is FaceSource.Document -> store.importPack(
                    openInput = {
                        if (cancelled.get()) throw InterruptedIOException("Cancelled")
                        val signal = CancellationSignal()
                        providerSignal.set(signal)
                        if (cancelled.get()) signal.cancel()
                        val descriptor = context.contentResolver.openAssetFileDescriptor(source.uri, "r", signal)
                            ?: throw FacePackOperationException(FacePackFailure.SOURCE_UNAVAILABLE, "The selected document is unavailable")
                        val stream = try { descriptor.createInputStream() } catch (failure: Exception) { descriptor.close(); throw failure }
                        activeInput.set(stream)
                        if (cancelled.get()) { stream.close(); throw InterruptedIOException("Cancelled") }
                        stream
                    }, allowUpdate = review != null, expectedUpdateDigest = review?.digest,
                    cancelled = cancelled::get, progress = progress,
                    abortInput = { providerSignal.get()?.cancel() },
                )
                is FaceSource.Catalog -> store.downloadPack(source.entry, allowUpdate = review != null, cancelled = cancelled::get, progress = progress)
            }
            post { selectedId = installed.id }
            if (cancelled.get()) "${installed.spec.name} finished installing before cancellation took effect. Existing Home and Lock Clock choices were kept."
            else if (review != null) "${installed.spec.name} updated. Existing placements keep their choices and use the updated face."
            else "${installed.spec.name} installed. Home and Lock Clock choices were not changed."
        }
    }

    internal fun remove(id: String) = start("Remove clock face", cancellable = false) {
        store.remove(id) { PackClockApply.resetRemovedBindings(context, id) }
        post { if (selectedId == id) selectedId = null }
        "Face removed. Its Home and Lock Clock selections now fall back to Steel Dial."
    }
    internal fun retryCollection() = start("Retry clock face collection", cancellable = false) {
        store.initialize()
        if (store.snapshot().error != null) throw FacePackOperationException(FacePackFailure.REGISTRY, "The collection is still unavailable")
        "Clock face collection checked."
    }
    internal fun retry(id: String) = start("Check installed face", cancellable = false) {
        val pack = store.retry(id)
        "${pack.spec.name} is available again."
    }
    internal fun applyHome(id: String, token: String?, page: String) = start("Save Home clock", cancellable = false) {
        PackClockApply.applyHome(context, id, token, page)
        if (token == null) "Added one clock to the selected Home page." else "Changed only the selected Home clock."
    }
    internal fun applyLock(id: String) = start("Save Lock Clock face", cancellable = false) {
        PackClockApply.applyLock(context, id)
        "Lock Clock face selected. Use Set as wallpaper… for Android's wallpaper confirmation."
    }

    private fun start(title: String, cancellable: Boolean, source: FaceSource? = null, previousId: String? = null, work: () -> String) {
        if (operation != null || updateReview != null || closed.get()) return
        cancelled.set(false)
        notices.clear()
        operation = FaceOperation(title, FacePackProgress(if (source is FaceSource.Catalog) FacePackStage.DOWNLOADING else if (source is FaceSource.Document) FacePackStage.COPYING else FacePackStage.INSTALLING), cancellable)
        worker.execute {
            try {
                val result = work()
                post { message(result) }
            } catch (update: FacePackUpdateRequiredException) {
                if (source != null && !cancelled.get()) post { updateReview = FaceUpdateReview(source, update.existingId, update.name, update.version, update.digest) }
                else post { message("Operation cancelled. No face was installed.") }
            } catch (failure: Exception) {
                val preserved = previousId != null && store.find(previousId) != null
                post { message(if (cancelled.get()) "Operation cancelled. No new face was installed." + if (preserved) " The previous version is still installed." else ""
                    else safeFaceFailure(failure) + if (preserved) " The previous version is still installed." else "") }
            } finally {
                runCatching { activeInput.getAndSet(null)?.close() }
                providerSignal.set(null)
                post { snapshot = store.snapshot(); operation = null }
            }
        }
    }

    internal fun cancelOperation() {
        val current = operation ?: return
        if (!current.cancellable || current.progress.stage == FacePackStage.INSTALLING || current.cancelling) return
        cancelled.set(true)
        operation = current.copy(cancelling = true)
        // Closing a remote provider can block: never do it on the UI thread. No Future.cancel during commit.
        val signal = providerSignal.get()
        val stream = activeInput.getAndSet(null)
        Thread({ runCatching { signal?.cancel() }; runCatching { stream?.close() } }, "clock-face-cancel").apply { isDaemon = true }.start()
    }

    override fun onCleared() {
        closed.set(true)
        cancelled.set(true)
        observation.close()
        val signal = providerSignal.getAndSet(null)
        val stream = activeInput.getAndSet(null)
        Thread({ runCatching { signal?.cancel() }; runCatching { stream?.close() } }, "clock-face-close").apply { isDaemon = true }.start()
        worker.shutdown() // An atomic store commit is allowed to finish and remains truthful after reopening.
        previews.evictAll()
        super.onCleared()
    }
}

/** Deliberately maps categories, not arbitrary provider/parser exception text or filesystem paths. */
internal fun safeFaceFailure(failure: Exception): String = when ((failure as? FacePackOperationException)?.reason) {
    FacePackFailure.BUSY -> "Another face operation is running. Wait and try again."
    FacePackFailure.CANCELLED -> "Operation cancelled. Nothing new was installed."
    FacePackFailure.TIMEOUT -> "The source took too long to respond. Try again or choose another file."
    FacePackFailure.OFFLINE -> "The download could not connect. Check your connection and try again."
    FacePackFailure.SOURCE_UNAVAILABLE -> "The source is unavailable or is limiting downloads. Try again later or choose another file."
    FacePackFailure.UNSUPPORTED_SOURCE -> "This download source is not supported. Choose an available catalog entry or import a ZIP."
    FacePackFailure.STORAGE -> "There is not enough collection storage. Remove an unused face or free device storage, then try again."
    FacePackFailure.RESOURCE -> "Not enough memory to load this face right now. Close other apps and retry. Installed files are kept."
    FacePackFailure.INVALID_PACK -> "This is not a valid supported clock face ZIP. Choose another file."
    FacePackFailure.INCOMPATIBLE -> "This face requires a newer Nano version or an unsupported capability. Update Nano or choose another face."
    FacePackFailure.VERIFICATION -> "The face could not be verified, or the file changed after confirmation. No update was installed. Choose the file again."
    FacePackFailure.CONFLICT -> "This face conflicts with an installed version or the selection changed. Older versions and changed content with the same version cannot replace a face."
    FacePackFailure.REGISTRY -> "Installed faces could not be loaded. Existing files were preserved. Restart Nano before changing the collection."
    null -> if (failure is SecurityException) "Access to the selected document expired. Choose the file again."
        else "The operation could not be completed safely. Existing installed faces were preserved. Try again or choose another file."
}
