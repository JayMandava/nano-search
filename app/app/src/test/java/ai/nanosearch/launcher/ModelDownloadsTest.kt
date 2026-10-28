package ai.nanosearch.launcher

import android.app.Application
import android.app.DownloadManager
import android.content.Context
import android.os.Looper
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executor
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowDownloadManager

/** Tiny file lifecycle tests. DownloadManager is shadowed; no real requests or model weights. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE, application = Application::class, shadows = [CleanupDownloadManager::class])
class ModelDownloadsTest {
    private val bytes = "tiny".toByteArray()
    private val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private val entries get() = ModelCatalog.all.map { e -> ModelEntry(e.id, e.slot, e.name, e.note, e.license,
        e.parts.map { Part(it.file, it.url, digest, bytes.size.toLong()) }) }
    private val app get() = RuntimeEnvironment.getApplication()
    private val prefs get() = app.getSharedPreferences("nano", 0)
    private val manager get() = app.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    private val downloads get() = Shadow.extract<ShadowDownloadManager>(manager)
    private lateinit var work: WorkQueue
    private fun gemma() = entries.single { it.id == "answer-gemma4-e2b" }
    private fun qwen() = entries.single { it.id == "answer-qwen35-2b" }
    @Before fun setup() {
        prefs.edit().clear().commit(); ModelStore.init(app); ModelStore.status.clear(); work = WorkQueue()
    }
    @After fun cleanup() {
        CleanupDownloadManager.beforeRemove = null
        entries.forEach(ModelStore::cancel)
        ModelStore.dir.deleteRecursively()
        app.getExternalFilesDir("downloads")?.deleteRecursively()
        prefs.edit().clear().commit(); ModelStore.status.clear()
    }
    private fun id(p: Part) = prefs.getLong("dl_${p.file}", -1L)
    private fun stage(p: Part) = File(requireNotNull(Shadow.extract<ShadowDownloadManager.ShadowRequest>(downloads.getRequest(id(p))).destination.path))
    private fun complete(p: Part, data: ByteArray = bytes) {
        stage(p).writeBytes(data)
        Shadow.extract<ShadowDownloadManager.ShadowRequest>(downloads.getRequest(id(p))).status = DownloadManager.STATUS_SUCCESSFUL
    }
    private fun drain() { work.runAll(); shadowOf(Looper.getMainLooper()).idle() }

    @Test fun duplicatePollDuringCleanupCannotTurnSuccessfulInstallIntoEnoent() {
        val e = gemma(); val p = e.parts.single()
        ModelStore.download(e, true); complete(p)
        val oldStage = stage(p)
        CleanupDownloadManager.beforeRemove = {
            oldStage.delete()
            ModelStore.poll(listOf(e), work)
        }
        ModelStore.poll(listOf(e), work)
        drain()
        assertEquals(1, work.executed)
        assertTrue(ModelStore.installed(e))
        assertNull(ModelStore.status[p.file])
        assertEquals(-1L, id(p))
        assertFalse(oldStage.exists())
    }
    @Test fun repeatedTapsAcrossAllModelsEnqueueOnlyOneRequestPerSharedFile() {
        repeat(3) { entries.forEach { ModelStore.download(it, true) } }
        val files = entries.flatMap { it.parts }.distinctBy { it.file }
        assertEquals(files.size, downloads.requestCount)
        files.forEach { complete(it) }
        ModelStore.poll(entries, work); ModelStore.poll(entries, work)
        assertEquals(files.size, work.pending)
        drain()
        assertEquals(files.size, work.executed)
        assertTrue(entries.all(ModelStore::installed))
        assertTrue(ModelStore.status.isEmpty())
        assertTrue(files.all { id(it) == -1L })
    }
    @Test fun cancellationAndImmediateRetryKeepDistinctPathsAndRejectOldWorkerResult() {
        val e = gemma(); val p = e.parts.single()
        ModelStore.download(e, true); complete(p); val firstStage = stage(p)
        ModelStore.poll(listOf(e), work)
        ModelStore.cancel(e)
        ModelStore.download(e, true); val nextId = id(p); val nextStage = stage(p)
        assertNotEquals(firstStage, nextStage)
        complete(p)
        work.runNext(); shadowOf(Looper.getMainLooper()).idle() // Canceled generation.
        assertEquals(nextId, id(p))
        assertTrue(nextStage.exists())
        assertTrue(ModelStore.status[p.file] is Transfer.Downloading)
        ModelStore.poll(listOf(e), work); drain()
        assertTrue(ModelStore.installed(e)); assertNull(ModelStore.status[p.file])
        assertTrue(ModelStore.dir.listFiles().orEmpty().none { it.name.endsWith(".tmp") })
    }
    @Test fun lateSuccessfulCopyCannotOverwriteARetriedGeneration() {
        val e = gemma(); val p = e.parts.single()
        ModelStore.download(e, true); complete(p); ModelStore.poll(listOf(e), work)
        work.runNext() // Copy finished; main-thread commit is still queued.
        ModelStore.cancel(e); ModelStore.download(e, true); complete(p)
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(ModelStore.installed(e))
        assertTrue(stage(p).exists())
        ModelStore.poll(listOf(e), work); drain()
        assertTrue(ModelStore.installed(e)); assertNull(ModelStore.status[p.file])
    }
    @Test fun failedPartDoesNotAffectAnotherModelsProgressAndSuccessClearsRetryError() {
        val a = gemma(); val b = qwen()
        ModelStore.download(a, true); ModelStore.download(b, true)
        complete(a.parts.single(), "bad!".toByteArray()); complete(b.parts.single())
        ModelStore.poll(listOf(a, b), work); drain()
        assertTrue(ModelStore.status[a.file] is Transfer.Failed)
        assertNull(ModelStore.status[b.file]); assertTrue(ModelStore.installed(b))
        ModelStore.download(a, true)
        assertTrue(ModelStore.status[a.file] is Transfer.Downloading)
        complete(a.parts.single()); ModelStore.poll(listOf(a), work); drain()
        assertTrue(ModelStore.installed(a)); assertNull(ModelStore.status[a.file])
    }
    @Test fun missingStagingFileReportsSafeRetryTextWithoutLeakingStoragePaths() {
        val e = gemma(); val p = e.parts.single()
        ModelStore.download(e, true); complete(p); stage(p).delete()
        ModelStore.poll(listOf(e), work); drain()
        val reason = (ModelStore.status[p.file] as Transfer.Failed).reason
        assertTrue(reason.contains("Retry")); assertFalse(reason.contains("/")); assertFalse(ModelStore.installed(e))
    }
    @Test fun unavailableDirectoryIsRecreatedAndOldInstalledStaleErrorIsCleared() {
        val e = gemma(); val p = e.parts.single()
        app.getExternalFilesDir("downloads")!!.deleteRecursively()
        ModelStore.download(e, true)
        assertTrue(stage(p).parentFile!!.isDirectory)
        complete(p); ModelStore.poll(listOf(e), work); drain()
        ModelStore.status[p.file] = Transfer.Failed("Old ENOENT")
        ModelStore.poll(listOf(e), work)
        assertTrue(ModelStore.installed(e)); assertNull(ModelStore.status[p.file])
    }
    @Test fun legacyInFlightPartWithoutAttemptTokenStillCompletes() {
        val e = gemma(); val p = e.parts.single()
        val request = DownloadManager.Request(android.net.Uri.parse(p.url))
            .setDestinationInExternalFilesDir(app, "downloads", p.file + ".part")
        prefs.edit().putLong("dl_${p.file}", manager.enqueue(request)).apply()
        complete(p); ModelStore.poll(listOf(e), work); drain()
        assertTrue(ModelStore.installed(e)); assertNull(ModelStore.status[p.file])
    }
    class WorkQueue : Executor {
        private val tasks = ArrayDeque<Runnable>()
        var executed = 0; private set
        val pending get() = tasks.size
        override fun execute(command: Runnable) { tasks.add(command) }
        fun runNext() { tasks.removeFirst().run(); executed++ }
        fun runAll() { while (tasks.isNotEmpty()) runNext() }
    }
}

@Implements(DownloadManager::class)
class CleanupDownloadManager : ShadowDownloadManager() {
    companion object { var beforeRemove: (() -> Unit)? = null }
    @Implementation override fun remove(vararg ids: Long): Int {
        val callback = beforeRemove; beforeRemove = null; callback?.invoke()
        return super.remove(*ids)
    }
}
