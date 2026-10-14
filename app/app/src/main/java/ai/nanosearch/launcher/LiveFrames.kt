package ai.nanosearch.launcher

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.Manifest
import android.app.AlarmManager
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import android.net.wifi.WifiManager
import android.text.format.DateFormat
import android.util.Size
import java.util.Date

/** What an app is doing right now, for the Active Frames of Spirit: the music playing, and later the same for anything else that has a live state to read. */
object LiveFrames {
    class Media(val pkg: String, val title: String, val artist: String, val art: Bitmap?, val playing: Boolean, private val controller: MediaController) {
        fun previous() = controller.transportControls.skipToPrevious()
        fun next() = controller.transportControls.skipToNext()
        fun toggle() = if (playing) controller.transportControls.pause() else controller.transportControls.play()
    }

    /** The apps with a media session, newest state first. Needs the notification access the Hub already asks for; without it the list is empty. */
    fun media(context: Context): List<Media> {
        val sessions = runCatching {
            context.getSystemService(MediaSessionManager::class.java).getActiveSessions(ComponentName(context, NotificationHub::class.java))
        }.getOrNull() ?: return emptyList()
        return sessions.mapNotNull { c ->
            val m = c.metadata ?: return@mapNotNull null
            val title = m.getString(MediaMetadata.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val artist = (m.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: m.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST) ?: m.getString(MediaMetadata.METADATA_KEY_ALBUM)).orEmpty()
            val art = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: m.getBitmap(MediaMetadata.METADATA_KEY_ART) ?: m.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
            val playing = c.playbackState?.state == PlaybackState.STATE_PLAYING
            Media(c.packageName, title, artist, art?.let { shrink(it, 320) }, playing, c)
        }.sortedByDescending { it.playing }
    }

    /** A copy no larger than [max] on its long side, so frames never hold a full-size picture. */
    fun shrink(b: Bitmap, max: Int): Bitmap {
        val longSide = maxOf(b.width, b.height)
        if (longSide <= max) return b
        val f = max.toFloat() / longSide
        return Bitmap.createScaledBitmap(b, (b.width * f).toInt().coerceAtLeast(1), (b.height * f).toInt().coerceAtLeast(1), true)
    }

    /** One tappable shortcut an app publishes (Google's voice search, a messenger's chats). */
    class Shortcut(val label: String, val icon: Drawable?, val open: () -> Unit)

    /** What a frame shows for an app, read from the phone's own data: a big figure, a few lines, some pictures, or the app's shortcuts. Any part can be empty. */
    class Live(val big: String? = null, val bigSub: String? = null, val lines: List<String> = emptyList(), val photos: List<Bitmap> = emptyList(), val shortcuts: List<Shortcut> = emptyList()) {
        val isEmpty get() = big == null && lines.isEmpty() && photos.isEmpty() && shortcuts.isEmpty()
        /** Something that changes when what is shown changes, so a frame is only redrawn when it must be. */
        val signature get() = "$big|$bigSub|${lines.joinToString("/")}|${photos.size}|${shortcuts.joinToString("/") { it.label }}"
    }

    private fun has(c: Context, p: String) = c.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    /** The live content for the app [pkg] (called "[label]"), or null when there is nothing real to show. Do not call on the main thread. */
    fun live(context: Context, pkg: String, label: String, extras: List<HubData.Item>): Live? {
        val kind = AppIcons.kindOf(pkg, label)
        val clock = DateFormat.getTimeFormat(context)
        val live: Live? = when (kind) {
            "clock" -> {
                val next = runCatching { context.getSystemService(AlarmManager::class.java).nextAlarmClock }.getOrNull()
                Live(big = clock.format(Date()), bigSub = if (next != null) "Next alarm " + DateFormat.format("EEE ", next.triggerTime) + clock.format(Date(next.triggerTime)) else "No alarm set")
            }
            "weather" -> Weather.cached(context)?.let { w -> Live(big = Weather.format(context, w), bigSub = Weather.spot(context)?.first.orEmpty()) }
            "calendar" -> extras.filter { it.kind == "event" }.take(3).map { e ->
                (if (android.text.format.DateUtils.isToday(e.whenMs)) clock.format(Date(e.whenMs)) else DateFormat.format("EEE ", e.whenMs).toString() + clock.format(Date(e.whenMs))) + "  " + e.title
            }.takeIf { it.isNotEmpty() }?.let { Live(lines = it) }
            "phone" -> extras.filter { it.kind == "call" }.take(3).map { "Missed: ${it.title}" }.takeIf { it.isNotEmpty() }?.let { Live(lines = it) }
            "message" -> extras.filter { it.kind == "message" && it.pkg == pkg }.take(3).map { "${it.title}: ${it.body}" }.takeIf { it.isNotEmpty() }?.let { Live(lines = it) }
            "camera", "gallery" -> photos(context, 3).takeIf { it.isNotEmpty() }?.let { Live(photos = it) }
            "files" -> recentFiles(context).takeIf { it.isNotEmpty() }?.let { Live(lines = it) }
            "settings" -> {
                val b = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                val pct = b?.let { it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) * 100 / it.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1) } ?: -1
                val charging = b?.getIntExtra(BatteryManager.EXTRA_STATUS, 0).let { it == BatteryManager.BATTERY_STATUS_CHARGING || it == BatteryManager.BATTERY_STATUS_FULL }
                val free = StatFs(Environment.getDataDirectory().path).availableBytes / 1_000_000_000.0
                val wifi = runCatching { context.applicationContext.getSystemService(WifiManager::class.java).isWifiEnabled }.getOrDefault(false)
                Live(big = if (pct >= 0) "$pct%" else null, bigSub = if (charging) "Charging" else "Battery",
                    lines = listOf("%.0f GB free".format(free), if (wifi) "Wi-Fi on" else "Wi-Fi off"))
            }
            else -> null
        }
        if (live != null && !live.isEmpty) return live
        // what the app has running in the shade (a VPN's state, a download, Tor's status)
        val running = ongoing(pkg)
        if (running.isNotEmpty()) return Live(lines = running)
        // anything else: the shortcuts the app publishes (what the app can do, one tap away)
        val sc = shortcuts(context, pkg)
        return if (sc.isNotEmpty()) Live(shortcuts = sc) else null
    }

    /** The newest files this app is allowed to see (photos, videos, audio, whatever the granted permissions cover), by name. */
    private fun recentFiles(context: Context): List<String> = runCatching {
        val out = ArrayList<String>()
        context.contentResolver.query(MediaStore.Files.getContentUri("external"), arrayOf(MediaStore.Files.FileColumns.DISPLAY_NAME), "${MediaStore.Files.FileColumns.MEDIA_TYPE} != 0", null,
            "${MediaStore.Files.FileColumns.DATE_ADDED} DESC LIMIT 3")?.use { c -> while (c.moveToNext()) c.getString(0)?.let { out.add(it) } }
        out
    }.getOrDefault(emptyList())

    /** What an app shows in the notification shade while it runs (not a message to read): its title and text, newest first. */
    private fun ongoing(pkg: String): List<String> = runCatching {
        val out = ArrayList<String>()
        for (sbn in HubStore.service?.activeNotifications.orEmpty().filter { it.packageName == pkg && it.notification.flags and android.app.Notification.FLAG_GROUP_SUMMARY == 0 }.sortedByDescending { it.postTime }.take(2)) {
            val x = sbn.notification.extras
            x.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString()?.takeIf { it.isNotBlank() }?.let { out.add(it) }
            x.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString()?.takeIf { it.isNotBlank() }?.let { out.add(it) }
        }
        out.take(3)
    }.getOrDefault(emptyList())

    private fun photos(context: Context, n: Int): List<Bitmap> {
        if (!has(context, Manifest.permission.READ_MEDIA_IMAGES)) return emptyList()
        return runCatching {
            val out = ArrayList<Bitmap>()
            context.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Images.Media._ID), null, null, "${MediaStore.Images.Media.DATE_ADDED} DESC LIMIT $n")?.use { c ->
                while (c.moveToNext()) {
                    val uri = android.content.ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(0))
                    runCatching { context.contentResolver.loadThumbnail(uri, Size(240, 240), null) }.getOrNull()?.let { out.add(it) }
                }
            }
            out
        }.getOrDefault(emptyList())
    }

    private fun shortcuts(context: Context, pkg: String): List<Shortcut> = runCatching {
        val la = context.getSystemService(LauncherApps::class.java)
        if (!la.hasShortcutHostPermission()) return@runCatching emptyList()
        val q = LauncherApps.ShortcutQuery().setPackage(pkg).setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED)
        (la.getShortcuts(q, android.os.Process.myUserHandle()) ?: emptyList()).filter { it.isEnabled }.sortedBy { it.rank }.take(3).map { sc ->
            Shortcut((sc.shortLabel ?: sc.longLabel ?: "").toString(), runCatching { la.getShortcutIconDrawable(sc, 0) }.getOrNull()) {
                runCatching { la.startShortcut(sc, null, null) }
            }
        }
    }.getOrDefault(emptyList())
}
