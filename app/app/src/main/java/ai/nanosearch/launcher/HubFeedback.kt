package ai.nanosearch.launcher

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.view.HapticFeedbackConstants
import android.view.View
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/** The little cues of the Hub: a haptic tick, and, if the user turned sounds on, a short soft tone made on the spot (no sound files). */
object HubFeedback {
    enum class Cue(val freq: Double, val haptic: Int) {
        OPEN(660.0, HapticFeedbackConstants.CONTEXT_CLICK),
        DISMISS(520.0, HapticFeedbackConstants.CLOCK_TICK),
        CLEAR(440.0, HapticFeedbackConstants.CONFIRM),
        ARRIVE(880.0, HapticFeedbackConstants.CLOCK_TICK),
    }

    private val tones = HashMap<Cue, ShortArray>()
    private val senseTones = HashMap<Cue, ShortArray>()

    private fun tone(cue: Cue): ShortArray = tones.getOrPut(cue) {
        val rate = 22050; val n = rate * 70 / 1000
        ShortArray(n) { i ->
            val t = i / rate.toDouble()
            val env = exp(-t * 55.0) * (1 - exp(-t * 900.0)) // a quick attack and a short decay: a tick, not a beep
            ((sin(2 * PI * cue.freq * t) + 0.4 * sin(2 * PI * cue.freq * 2 * t)) * env * 0.22 * Short.MAX_VALUE).toInt().toShort()
        }
    }

    fun play(context: Context, view: View?, cue: Cue) {
        view?.performHapticFeedback(cue.haptic)
        if (!AppSettings.hubSounds(context)) return
        val data = if (AppSettings.isSense(context)) senseTones.getOrPut(cue) { SenseTones.tick(cue.freq) } else tone(cue)
        runCatching {
            val track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(22050).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(data.size * 2).setTransferMode(AudioTrack.MODE_STATIC).build()
            track.write(data, 0, data.size)
            track.setNotificationMarkerPosition(data.size)
            track.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
                override fun onMarkerReached(t: AudioTrack) { t.release() }
                override fun onPeriodicNotification(t: AudioTrack) = Unit
            })
            track.play()
        }
    }
}
