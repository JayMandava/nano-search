package ai.nanosearch.launcher

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState

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
}
