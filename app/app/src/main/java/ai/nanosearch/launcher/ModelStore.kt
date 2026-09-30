package ai.nanosearch.launcher

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import androidx.compose.runtime.mutableStateMapOf
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

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

    fun selected(slot: Slot): ModelEntry {
        val id = prefs.getString("sel_${slot.name}", null)
        val all = entries(slot)
        val chosen = all.firstOrNull { it.id == id } ?: ModelCatalog.defaultFor(slot)
        // If the chosen model is gone (deleted, or never downloaded), use one that is on the phone rather than nothing.
        return if (installed(chosen)) chosen else all.firstOrNull { installed(it) } ?: chosen
    }

    fun select(entry: ModelEntry) = prefs.edit().putString("sel_${entry.slot.name}", entry.id).apply()

    fun file(part: Part) = File(dir, part.file)

    fun installed(entry: ModelEntry) = entry.parts.all { partInstalled(entry, it) }
    private fun partInstalled(entry: ModelEntry, part: Part) = file(part).let { it.exists() && (entry.custom || it.length() == part.bytes) }

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
    private fun downloadId(part: Part) = prefs.getLong("dl_${part.file}", -1L)
    private fun stagingFile(part: Part) = File(app.getExternalFilesDir("downloads"), part.file + ".part")

    private val idleSince = HashMap<String, Long>()

    fun active(entry: ModelEntry) = entry.parts.any { status[it.file] is Transfer.Downloading || status[it.file] == Transfer.Verifying }

    fun download(entry: ModelEntry, wifiOnly: Boolean) {
        val missing = entry.parts.filter { !partInstalled(entry, it) }
        if (dir.usableSpace < missing.sumOf { it.bytes } * 21 / 10) {
            missing.forEach { status[it.file] = Transfer.Failed("Not enough free space.") }
            return
        }
        for (p in missing) {
            if (downloadId(p) != -1L) continue
            status.remove(p.file)
            stagingFile(p).delete()
            val req = DownloadManager.Request(Uri.parse(p.url))
                .setTitle("Nano Search: ${entry.name}")
                .setDestinationInExternalFilesDir(app, "downloads", p.file + ".part")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setAllowedNetworkTypes(if (wifiOnly) DownloadManager.Request.NETWORK_WIFI else DownloadManager.Request.NETWORK_WIFI or DownloadManager.Request.NETWORK_MOBILE)
            prefs.edit().putLong("dl_${p.file}", manager.enqueue(req)).apply()
            status[p.file] = Transfer.Downloading(0, p.bytes)
        }
    }

    fun cancel(entry: ModelEntry) {
        entry.parts.forEach { p ->
            downloadId(p).takeIf { it != -1L }?.let { manager.remove(it) }
            prefs.edit().remove("dl_${p.file}").apply()
            stagingFile(p).delete()
            status.remove(p.file)
        }
    }

    /** Looks at every download in flight and finishes the ones that have arrived. Call it every second or so while a screen shows progress. */
    fun poll() {
        for (entry in ModelCatalog.all) for (p in entry.parts) {
            val id = downloadId(p)
            if (id == -1L || status[p.file] == Transfer.Verifying) continue
            manager.query(DownloadManager.Query().setFilterById(id))?.use { c ->
                if (!c.moveToFirst()) { prefs.edit().remove("dl_${p.file}").apply(); status.remove(p.file); return@use } // cancelled from the system UI
                val state = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                when (state) {
                    DownloadManager.STATUS_SUCCESSFUL -> finish(entry, p, id)
                    DownloadManager.STATUS_FAILED -> {
                        val reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                        manager.remove(id); prefs.edit().remove("dl_${p.file}").apply()
                        status[p.file] = Transfer.Failed(if (reason == DownloadManager.ERROR_INSUFFICIENT_SPACE) "Not enough free space." else "Download failed. Check the connection and try again.")
                    }
                    else -> {
                        val done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                        // Nothing arriving for 20 seconds usually means no connection, or Android is blocking this app's network.
                        val since = if (done > 0) null else idleSince.getOrPut(p.file) { System.currentTimeMillis() }
                        if (done > 0) idleSince.remove(p.file)
                        status[p.file] = Transfer.Downloading(done, p.bytes, since != null && System.currentTimeMillis() - since > 20_000)
                    }
                }
            }
        }
    }

    /** Copies the download into private storage while hashing it, and keeps it only if the hash is right. */
    private fun finish(entry: ModelEntry, part: Part, id: Long) {
        status[part.file] = Transfer.Verifying
        Thread {
            val tmp = File(dir, part.file + ".tmp")
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                stagingFile(part).inputStream().use { input ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(1 shl 20)
                        while (true) { val n = input.read(buf); if (n < 0) break; digest.update(buf, 0, n); out.write(buf, 0, n) }
                    }
                }
                val hex = digest.digest().joinToString("") { "%02x".format(it) }
                if (hex == part.sha256 && tmp.length() == part.bytes) {
                    tmp.renameTo(file(part))
                    status.remove(part.file)
                    if (installed(entry) && !installed(selected(entry.slot))) select(entry)
                } else {
                    tmp.delete()
                    status[part.file] = Transfer.Failed("The file did not match its checksum and was discarded. Try again.")
                }
            } catch (e: Exception) {
                tmp.delete()
                status[part.file] = Transfer.Failed("Could not save the model: ${e.message}")
            } finally {
                manager.remove(id)
                prefs.edit().remove("dl_${part.file}").apply()
                stagingFile(part).delete()
            }
        }.start()
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
