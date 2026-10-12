package ai.nanosearch.launcher

import android.content.ContentValues
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Environment
import android.provider.MediaStore
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin

/**
 * The sounds of the Sense era, made here from sine waves (a soft bell: a fundamental with a few decaying overtones), so there are no sound files and nothing
 * copied from any phone: a notification chime, a short ping, and a ringtone phrase. They can also be added to the phone's own sound lists.
 */
object SenseTones {
    enum class Tone(val title: String, val kind: Kind) { CHIME("Sense Chime", Kind.NOTIFICATION), PING("Sense Ping", Kind.NOTIFICATION), RING("Sense Ring", Kind.RINGTONE) }
    enum class Kind { NOTIFICATION, RINGTONE }

    const val RATE = 22050
    private val cache = HashMap<Tone, ShortArray>()

    /** Adds one bell note to [out] starting [at] seconds in. */
    private fun bell(out: FloatArray, at: Double, freq: Double, len: Double, amp: Double, decay: Double = 5.5) {
        val start = (at * RATE).toInt()
        val n = (len * RATE).toInt()
        for (i in 0 until n) {
            val j = start + i
            if (j >= out.size) break
            val t = i / RATE.toDouble()
            val attack = 1.0 - exp(-t * 700.0)
            val wave = sin(2 * PI * freq * t) + 0.45 * sin(2 * PI * freq * 2.0 * t) * exp(-t * 3.0) + 0.2 * sin(2 * PI * freq * 3.01 * t) * exp(-t * 6.0) + 0.08 * sin(2 * PI * freq * 4.2 * t) * exp(-t * 9.0)
            out[j] += (wave * attack * exp(-t * decay) * amp).toFloat()
        }
    }

    private fun pack(buf: FloatArray): ShortArray {
        val peak = buf.maxOf { abs(it) }.coerceAtLeast(1e-6f)
        val gain = 0.8f / peak
        return ShortArray(buf.size) { (buf[it] * gain * Short.MAX_VALUE).toInt().toShort() }
    }

    fun pcm(tone: Tone): ShortArray = cache.getOrPut(tone) {
        when (tone) {
            Tone.CHIME -> FloatArray((RATE * 1.4).toInt()).also { bell(it, 0.0, 659.3, 1.2, 1.0); bell(it, 0.13, 987.8, 1.2, 0.9) }
            Tone.PING -> FloatArray((RATE * 0.6).toInt()).also { bell(it, 0.0, 1568.0, 0.55, 1.0, 8.0) }
            Tone.RING -> FloatArray(RATE * 7).also { out ->
                val up = doubleArrayOf(523.3, 659.3, 784.0, 1046.5)
                val down = doubleArrayOf(784.0, 659.3, 587.3, 523.3)
                for (rep in 0 until 2) {
                    val base = rep * 3.5
                    up.forEachIndexed { i, f -> bell(out, base + i * 0.16, f, 0.9, 1.0, 4.5) }
                    down.forEachIndexed { i, f -> bell(out, base + 1.0 + i * 0.2, f, 1.2, 0.9, 3.8) }
                }
            }
        }.let(::pack)
    }

    /** A very short soft bell at [freq], for the Hub's cues. */
    fun tick(freq: Double): ShortArray = pack(FloatArray(RATE * 120 / 1000).also { bell(it, 0.0, freq, 0.12, 1.0, 30.0) }).also { for (i in it.indices) it[i] = (it[i] * 0.3f).toInt().toShort() }

    fun play(context: Context, data: ShortArray) {
        runCatching {
            val track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
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

    fun play(context: Context, tone: Tone) = play(context, pcm(tone))

    fun wav(data: ShortArray): ByteArray {
        val bytes = ByteBuffer.allocate(data.size * 2).order(ByteOrder.LITTLE_ENDIAN).also { b -> data.forEach { b.putShort(it) } }.array()
        val out = ByteArrayOutputStream()
        fun int(v: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()
        fun short(v: Int) = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(v.toShort()).array()
        out.write("RIFF".toByteArray()); out.write(int(36 + bytes.size)); out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray()); out.write(int(16)); out.write(short(1)); out.write(short(1)); out.write(int(RATE)); out.write(int(RATE * 2)); out.write(short(2)); out.write(short(16))
        out.write("data".toByteArray()); out.write(int(bytes.size)); out.write(bytes)
        return out.toByteArray()
    }

    /**
     * Adds the Sense sounds to the phone's own lists (the system sound picker finds them there): the ringtone under Ringtones, the others under Notifications.
     * Returns how many were added; ones already there are left alone.
     */
    fun install(context: Context): Int {
        val resolver = context.contentResolver
        var added = 0
        for (tone in Tone.entries) {
            val ringtone = tone.kind == Kind.RINGTONE
            val dir = if (ringtone) Environment.DIRECTORY_RINGTONES else Environment.DIRECTORY_NOTIFICATIONS
            val name = "${tone.title}.wav"
            val exists = resolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Audio.Media._ID), "${MediaStore.Audio.Media.DISPLAY_NAME} = ?", arrayOf(name), null)?.use { it.moveToFirst() } == true
            if (exists) continue
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, name)
                put(MediaStore.Audio.Media.TITLE, tone.title)
                put(MediaStore.Audio.Media.MIME_TYPE, "audio/x-wav")
                put(MediaStore.Audio.Media.RELATIVE_PATH, "$dir/Nano Search")
                put(MediaStore.Audio.Media.IS_RINGTONE, ringtone)
                put(MediaStore.Audio.Media.IS_NOTIFICATION, !ringtone)
                put(MediaStore.Audio.Media.IS_ALARM, false)
                put(MediaStore.Audio.Media.IS_MUSIC, false)
            }
            val uri = runCatching { resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values) }.getOrNull() ?: continue
            val ok = runCatching { resolver.openOutputStream(uri)?.use { s: OutputStream -> s.write(wav(pcm(tone))) } != null }.getOrDefault(false)
            if (ok) added++ else runCatching { resolver.delete(uri, null, null) }
        }
        return added
    }
}
