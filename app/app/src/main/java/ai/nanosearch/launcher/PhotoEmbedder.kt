package ai.nanosearch.launcher

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.provider.MediaStore
import android.util.Log
import android.util.Size

/**
 * Background job that gives every photo a fingerprint (see [ClipEngine]). It resumes where it stopped, newest photos first.
 * While charging it works through the whole library; on battery it does a small batch each time so new photos are
 * covered without draining anything. It uses one CPU thread and pauses briefly between photos when not charging.
 */
class PhotoEmbedder(private val context: Context, private val index: SearchIndex) {
    private val zero = FloatArray(512)

    fun run(shouldStop: () -> Boolean) {
        val clip = Services.clip
        if (!clip.available) return
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, 100) ?: 100
        val budget = when {
            charging -> Int.MAX_VALUE
            level >= 30 -> 60
            else -> 0
        }
        if (budget == 0) return

        val t0 = System.nanoTime()
        var done = 0
        val base = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        while (done < budget && !shouldStop()) {
            val pending = index.pendingPhotos(200)
            if (pending.isEmpty()) break
            val batch = ArrayList<Triple<Long, Long, FloatArray>>()
            for (p in pending) {
                if (done >= budget || shouldStop()) break
                val bitmap = runCatching { context.contentResolver.loadThumbnail(ContentUris.withAppendedId(base, p[0]), Size(320, 320), null) }.getOrNull()
                val vec = bitmap?.let { b -> clip.encodeImage(b).also { b.recycle() } }
                // A photo that cannot be read gets a zero fingerprint so it is not retried on every run.
                batch.add(Triple(p[0], p[1], vec ?: zero))
                done++
                Services.clipUsed()
                if (!charging) Thread.sleep(100)
            }
            index.putVectors(batch)
        }
        if (done > 0) {
            val ms = (System.nanoTime() - t0) / 1_000_000
            Log.i("nanosearch", "fingerprinted $done photos in ${ms}ms (${ms / done} ms each, charging=$charging); ${index.vectorCount()} total")
        }
    }
}
