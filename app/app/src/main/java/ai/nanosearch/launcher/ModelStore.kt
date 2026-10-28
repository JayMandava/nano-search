package ai.nanosearch.launcher

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateMapOf
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InterruptedIOException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** What a file being fetched is doing right now. A file that is not in [ModelStore.status] is idle. */
sealed interface Transfer {
    class Downloading(val done: Long, val total: Long, val stalled: Boolean = false) : Transfer
    object Verifying : Transfer
    class Failed(val reason: String) : Transfer
}

/**
 * Which model does each job, which ones are on the phone, and how new ones arrive: downloaded by the system's
 * DownloadManager (so it survives the app closing and resumes on a flaky connection), checked against the expected
 * SHA-256, then moved into the app's private storage. This is the only place Nano Search touches the network.
 */
object ModelStore {
    private lateinit var app: Context
    private val prefs get() = app.getSharedPreferences("nano", Context.MODE_PRIVATE)
    val dir get() = File(app.filesDir, "models").apply { mkdirs() }

    /** Progress by file name. Compose reads this directly. */
    val status = mutableStateMapOf<String, Transfer>()

    fun init(context: Context) {
        app = context.applicationContext
        migrateLegacyNames()
    }

    // ---- what exists and what is chosen

    fun entries(slot: Slot): List<ModelEntry> = ModelCatalog.forSlot(slot) + customs().filter { it.slot == slot }

    private fun preferred(slot: Slot): ModelEntry {
        val id = prefs.getString("sel_${slot.name}", null)
        return entries(slot).firstOrNull { it.id == id } ?: ModelCatalog.defaultFor(slot)
    }

    fun selected(slot: Slot): ModelEntry {
        val chosen = preferred(slot)
        // Keep inference usable while the requested choice is downloading or unavailable.
        return if (installed(chosen)) chosen else entries(slot).firstOrNull { installed(it) } ?: chosen
    }

    /** One displayed choice per job: a requested download, otherwise the usable model. */
    fun displayChoice(slot: Slot): ModelEntry {
        val chosen = preferred(slot)
        return if (active(chosen)) chosen else selected(slot)
    }

    fun select(entry: ModelEntry) = prefs.edit().putString("sel_${entry.slot.name}", entry.id).apply()

    fun file(part: Part) = File(dir, part.file)

    fun installed(entry: ModelEntry) = entry.parts.all { partInstalled(entry, it) }
    internal fun partInstalled(entry: ModelEntry, part: Part) = file(part).let { it.exists() && (entry.custom || it.length() == part.bytes) }

    /** Another job still uses this file, so it must not be deleted. */
    fun inUseBy(entry: ModelEntry): Slot? = Slot.entries.firstOrNull { s ->
        s != entry.slot && selected(s).parts.any { p -> entry.parts.any { it.file == p.file } }
    }

    /** Deletes a model's files. Returns why not, or null when done. */
    fun delete(entry: ModelEntry): String? {
        if (selected(entry.slot).id == entry.id && entries(entry.slot).count { installed(it) } > 1) return "Pick another model first."
        inUseBy(entry)?.let { return "Also used for ${it.title.lowercase()}." }
        entry.parts.forEach { file(it).delete() }
        if (entry.custom) saveCustoms(customs().filter { it.id != entry.id })
        return null
    }

    // ---- models the user brought

    private fun customs(): List<ModelEntry> = runCatching {
        val arr = JSONArray(prefs.getString("customModels", "[]"))
        (0 until arr.length()).map { arr.getJSONObject(it) }.map { o ->
            ModelEntry(
                o.getString("id"), Slot.valueOf(o.getString("slot")), o.getString("name"), "Imported from a file.", "Your own",
                listOf(Part(o.getString("file"), "", "", 0)), ChatFormat.valueOf(o.getString("format")), o.getBoolean("skip"), custom = true,
            )
        }
    }.getOrDefault(emptyList())

    private fun saveCustoms(list: List<ModelEntry>) {
        val arr = JSONArray()
        list.forEach { e ->
            arr.put(JSONObject().put("id", e.id).put("slot", e.slot.name).put("name", e.name).put("file", e.file).put("format", e.format.name).put("skip", e.skipThinking))
        }
        prefs.edit().putString("customModels", arr.toString()).apply()
    }

    /** Copies a file the user picked into the app and offers it for [slot]. Run off the main thread. */
    fun import(slot: Slot, uri: Uri, displayName: String): ModelEntry {
        val safe = displayName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val fileName = "custom-${slot.name.lowercase()}-$safe"
        val tmp = File(dir, "$fileName.tmp")
        app.contentResolver.openInputStream(uri)!!.use { input -> tmp.outputStream().use { input.copyTo(it, 1 shl 20) } }
        tmp.renameTo(File(dir, fileName))
        val lower = displayName.lowercase()
        val entry = ModelEntry(
            "custom-${slot.name.lowercase()}-$safe", slot, displayName.substringBeforeLast('.'), "Imported from a file.", "Your own",
            listOf(Part(fileName, "", "", 0)), if ("gemma" in lower) ChatFormat.GEMMA else ChatFormat.CHATML, "qwen3" in lower, custom = true,
        )
        saveCustoms(customs().filter { it.id != entry.id } + entry)
        select(entry)
        return entry
    }

    // ---- downloading

    private val manager get() = app.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    private val main = Handler(Looper.getMainLooper())
    private val verifier = Executors.newSingleThreadExecutor { task ->
        Thread(task, "model-verification").apply { isDaemon = true }
    }
    private fun downloadId(part: Part) = prefs.getLong("dl_${part.file}", -1L)
    private fun attemptToken(part: Part) = prefs.getString("dl_attempt_${part.file}", null)
    private fun stagingFile(part: Part, token: String?): File {
        val directory = app.getExternalFilesDir("downloads") ?: throw IOException("External storage unavailable")
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Download directory unavailable")
        return File(directory, part.file + (token?.let { ".$it" } ?: "") + ".part")
    }
    private data class Verification(
        val part: Part, val id: Long, val token: String?, val stage: File, val temporary: File,
        val cancelled: AtomicBoolean = AtomicBoolean(false),
    )
    // Main-thread ownership lasts through cleanup, not just hashing/copying. UI state
    // is never published from the worker. A canceled/retried generation cannot publish.
    private val verifying = HashMap<String, Verification>()
    private val retiring = HashSet<String>()
    private val idleSince = HashMap<String, Long>()

    fun active(entry: ModelEntry) = entry.parts.any { status[it.file] is Transfer.Downloading || status[it.file] == Transfer.Verifying }

    fun download(entry: ModelEntry, wifiOnly: Boolean) {
        val missing = entry.parts.filter { !partInstalled(entry, it) }
        if (dir.usableSpace < missing.sumOf { it.bytes } * 21 / 10) {
            missing.filter { downloadId(it) == -1L }.forEach { status[it.file] = Transfer.Failed("Not enough free space.") }
            return
        }
        for (p in missing) {
            if (p.file in retiring || p.file in verifying) continue
            if (downloadId(p) != -1L) {
                if (status[p.file] !is Transfer.Failed || !cancelPart(p)) continue
            }
            val token = UUID.randomUUID().toString()
            var stage: File? = null
            try {
                stage = stagingFile(p, token)
                val req = DownloadManager.Request(Uri.parse(p.url))
                    .setTitle("Nano Search: ${entry.name}")
                    .setDestinationInExternalFilesDir(app, "downloads", stage.name)
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setAllowedNetworkTypes(if (wifiOnly) DownloadManager.Request.NETWORK_WIFI else DownloadManager.Request.NETWORK_WIFI or DownloadManager.Request.NETWORK_MOBILE)
                val id = manager.enqueue(req)
                prefs.edit().putLong("dl_${p.file}", id).putString("dl_attempt_${p.file}", token).apply()
                idleSince.remove(p.file)
                status[p.file] = Transfer.Downloading(0, p.bytes)
            } catch (_: RuntimeException) {
                runCatching { stage?.delete() }
                status[p.file] = Transfer.Failed("Could not start the download. Check storage availability and retry.")
            } catch (_: IOException) {
                runCatching { stage?.delete() }
                status[p.file] = Transfer.Failed("Download storage is unavailable. Check storage and retry.")
            }
        }
        if (active(entry)) select(entry)
    }

    fun cancel(entry: ModelEntry) { entry.parts.forEach { cancelPart(it) } }

    private fun cancelPart(part: Part): Boolean {
        verifying.remove(part.file)?.cancelled?.set(true)
        val id = downloadId(part)
        val token = attemptToken(part)
        retiring += part.file
        try {
            if (id != -1L) manager.remove(id)
        } catch (_: RuntimeException) {
            // Keep the ID so an explicit retry can retire it before enqueuing a replacement.
            status[part.file] = Transfer.Failed("Could not cancel the download. Retry to stop it and start again.")
            return false
        } finally { retiring -= part.file }
        clearDownload(part, id, token)
        runCatching { stagingFile(part, token).delete() }
        idleSince.remove(part.file)
        status.remove(part.file)
        return true
    }

    private fun clearDownload(part: Part, id: Long, token: String?) {
        if (downloadId(part) == id && attemptToken(part) == token) {
            prefs.edit().remove("dl_${part.file}").remove("dl_attempt_${part.file}").apply()
        }
    }

    private fun retireCompleted(part: Part, id: Long, token: String?, stage: File?) {
        // Retire the ID before touching DownloadManager's destination. Reentrant polling
        // cannot schedule a second verification of an already committed installation.
        clearDownload(part, id, token)
        runCatching { manager.remove(id) }
        runCatching { stage?.delete() }
        idleSince.remove(part.file)
    }

    /** Poll on the main thread. Shared Qwen files are queried/verified once, not once per role. */
    fun poll() = poll(ModelCatalog.all)

    /** The same production path with tiny fixture entries and a controllable worker in tests. */
    internal fun poll(entries: List<ModelEntry>, executor: Executor = verifier) {
        val seen = HashSet<String>()
        for (entry in entries) for (p in entry.parts) {
            if (!seen.add(p.file) || p.file in verifying || p.file in retiring) continue
            val id = downloadId(p)
            val token = attemptToken(p)
            if (partInstalled(entry, p)) {
                if (id != -1L) retireCompleted(p, id, token, runCatching { stagingFile(p, token) }.getOrNull())
                status.remove(p.file) // Also reconcile stale in-memory errors from an older build.
                continue
            }
            if (id == -1L || status[p.file] is Transfer.Failed) continue
            try {
                manager.query(DownloadManager.Query().setFilterById(id))?.use { c ->
                    if (!c.moveToFirst()) {
                        clearDownload(p, id, token)
                        runCatching { stagingFile(p, token).delete() }
                        idleSince.remove(p.file); status.remove(p.file)
                        return@use
                    }
                    when (c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))) {
                        DownloadManager.STATUS_SUCCESSFUL -> finish(p, id, token, executor)
                        DownloadManager.STATUS_FAILED -> {
                            val reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                            retireCompleted(p, id, token, runCatching { stagingFile(p, token) }.getOrNull())
                            status[p.file] = Transfer.Failed(if (reason == DownloadManager.ERROR_INSUFFICIENT_SPACE) "Not enough free space." else "Download failed. Check the connection and retry.")
                        }
                        else -> {
                            val done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                            val since = if (done > 0) null else idleSince.getOrPut(p.file) { System.currentTimeMillis() }
                            if (done > 0) idleSince.remove(p.file)
                            status[p.file] = Transfer.Downloading(done, p.bytes, since != null && System.currentTimeMillis() - since > 20_000)
                        }
                    }
                }
            } catch (_: RuntimeException) {
                status[p.file] = Transfer.Failed("Could not check the download. Retry when storage is available.")
            }
        }
    }

    private fun finish(part: Part, id: Long, token: String?, executor: Executor) {
        val stage = try { stagingFile(part, token) } catch (_: IOException) {
            retireCompleted(part, id, token, null)
            status[part.file] = Transfer.Failed("Download storage is unavailable. Check storage and retry.")
            return
        }
        val job = Verification(part, id, token, stage, File(dir, part.file + "." + (token ?: "legacy-$id") + ".tmp"))
        verifying[part.file] = job
        status[part.file] = Transfer.Verifying
        executor.execute {
            var failure: String? = null
            try {
                if (job.cancelled.get()) throw InterruptedIOException()
                val digest = MessageDigest.getInstance("SHA-256")
                stage.inputStream().use { input -> job.temporary.outputStream().use { out ->
                    val buffer = ByteArray(1 shl 20)
                    while (true) {
                        if (job.cancelled.get()) throw InterruptedIOException()
                        val n = input.read(buffer)
                        if (n < 0) break
                        digest.update(buffer, 0, n); out.write(buffer, 0, n)
                    }
                } }
                val hash = digest.digest().joinToString("") { "%02x".format(it) }
                if (hash != part.sha256 || job.temporary.length() != part.bytes) {
                    failure = "The file did not match its checksum and was discarded. Retry the download."
                }
            } catch (_: FileNotFoundException) {
                failure = "The downloaded file is no longer available. Retry the download."
            } catch (_: Exception) {
                failure = "Could not save the model. Check storage and retry."
            }
            main.post {
                val owns = verifying[part.file] === job && downloadId(part) == id && attemptToken(part) == token
                if (!owns || job.cancelled.get()) { runCatching { job.temporary.delete() }; return@post }
                if (failure == null && !runCatching { job.temporary.renameTo(file(part)) }.getOrDefault(false)) {
                    failure = "Could not finish saving the model. Check storage and retry."
                }
                retireCompleted(part, id, token, stage)
                runCatching { job.temporary.delete() }
                verifying.remove(part.file)
                if (failure == null) status.remove(part.file) else status[part.file] = Transfer.Failed(requireNotNull(failure))
            }
        }
    }

    // ---- first run after the update

    /** Earlier builds used fixed file names; give them the catalogue names so nothing has to be downloaded again. */
    private fun migrateLegacyNames() {
        listOf("parser.gguf" to "parser-qwen35-2b", "answer.gguf" to "answer-gemma4-e2b", "whisper.bin" to "voice-small").forEach { (old, id) ->
            val from = File(dir, old)
            val to = ModelCatalog.all.first { it.id == id }.let { file(it.parts.first()) }
            if (from.exists() && !to.exists()) from.renameTo(to)
        }
    }
}
