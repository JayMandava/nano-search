package ai.nanosearch.launcher

import android.app.Activity
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.speech.SpeechRecognizer
import android.util.Log
import kotlin.math.abs
import kotlin.math.sqrt

/** What the search bar needs from a voice source: tap to start, tap again to finish early. */
interface Voice {
    val listening: Boolean
    val available: Boolean
    fun toggle()
    fun release()
}

class NativeWhisper {
    external fun load(path: String, threads: Int, cpuMask: Long): Long
    external fun transcribe(handle: Long, pcm: FloatArray, lang: String, prompt: String?, audioCtx: Int): String?
    external fun free(handle: Long)

    companion object {
        init {
            System.loadLibrary("nanollm")
        }
    }
}

/**
 * Fully on-device voice search: records from the microphone, decides for itself when the person has stopped
 * talking, and transcribes with Whisper running on the phone's fast cores. Nothing leaves the phone.
 * [onPhase] gets 0 = idle, 1 = listening, 2 = transcribing. The caller owns the RECORD_AUDIO permission.
 */
class LocalVoice(
    private val activity: Activity,
    private val modelFile: java.io.File,
    private val onFinal: (String) -> Unit,
    private val onPhase: (Int) -> Unit,
    private val onError: (Int) -> Unit,
) : Voice {
    private val native = NativeWhisper()
    private var handle = 0L
    private val ui = Handler(Looper.getMainLooper())
    private val unloadRunnable = Runnable { Services.llm.execute { unloadModel() } }

    @Volatile private var recording = false
    @Volatile private var finishEarly = false

    override val listening get() = recording
    override val available get() = modelFile.exists()

    override fun toggle() {
        if (recording) finishEarly = true else start()
    }

    override fun release() {
        finishEarly = true
    }

    private fun start() {
        recording = true
        finishEarly = false
        Thread({ record() }, "voice-record").start()
    }

    /** Records until speech has started and then stopped (or a time limit), then hands the audio to Whisper. */
    private fun record() {
        val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = try {
            AudioRecord(MediaRecorder.AudioSource.MIC, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, RATE))
        } catch (e: Exception) {
            null
        }
        if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) {
            recording = false
            onError(SpeechRecognizer.ERROR_AUDIO)
            return
        }
        val samples = ArrayList<Short>(RATE * 6)
        val frame = ShortArray(FRAME)
        var frames = 0
        var floor = 0.0
        var speechFrames = 0
        var silentFrames = 0
        var started = false
        var maxRms = 0.0
        var levelSum = 0.0
        rec.startRecording()
        onPhase(1)
        while (recording && !finishEarly) {
            val n = rec.read(frame, 0, FRAME)
            if (n <= 0) break
            var sum = 0.0
            for (i in 0 until n) sum += frame[i].toDouble() * frame[i]
            val rms = sqrt(sum / n)
            if (rms > maxRms) maxRms = rms
            frames++
            levelSum += rms
            if (frames % 25 == 0) { Log.i(TAG, "voice: level ${(levelSum / 25).toInt()} (floor ${floor.toInt()}, speech=$started)"); levelSum = 0.0 }
            if (frames <= CALIBRATION_FRAMES) floor = (floor * (frames - 1) + rms) / frames
            val threshold = maxOf(floor * 3.0, MIN_SPEECH_RMS)
            for (i in 0 until n) samples.add(frame[i])
            if (rms > threshold) { speechFrames++; silentFrames = 0 } else silentFrames++
            if (!started && speechFrames >= 3) started = true
            val seconds = frames * FRAME / RATE.toDouble()
            if (started && silentFrames >= END_SILENCE_FRAMES) break
            if (!started && seconds > NO_SPEECH_SECONDS) break
            if (seconds > MAX_SECONDS) break
        }
        rec.stop()
        rec.release()
        recording = false
        Log.i(TAG, "voice: recorded ${"%.1f".format(frames * FRAME / RATE.toDouble())}s, noise floor ${floor.toInt()}, loudest ${maxRms.toInt()}, speech ${if (started) "detected" else "not detected"}")

        if (!started) {
            onPhase(0)
            onError(SpeechRecognizer.ERROR_NO_MATCH)
            return
        }
        // Trim most of the trailing silence, then scale quiet recordings up.
        val keep = (samples.size - (silentFrames - 8).coerceAtLeast(0) * FRAME).coerceAtLeast(RATE / 2)
        val pcm = FloatArray(minOf(keep, samples.size)) { samples[it] / 32768f }
        val peak = pcm.maxOfOrNull { abs(it) } ?: 0f
        if (peak in 0.001f..0.2f) { val g = 0.5f / peak; for (i in pcm.indices) pcm[i] *= g }
        // Opt-in for debugging only: `touch files/models/debug_voice` and the last recording is kept as files/last_voice.wav.
        if (java.io.File(modelFile.parentFile, "debug_voice").exists()) runCatching { writeWav(java.io.File(activity.filesDir, "last_voice.wav"), pcm) }
        onPhase(2)
        Services.llm.execute { finish(pcm) }
    }

    private fun finish(pcm: FloatArray) {
        val text = transcribe(pcm)
        ui.removeCallbacks(unloadRunnable)
        ui.postDelayed(unloadRunnable, IDLE_UNLOAD_MS)
        onPhase(0)
        if (text.isNullOrBlank()) onError(SpeechRecognizer.ERROR_NO_MATCH) else onFinal(text)
    }

    /** Whisper adds bracketed noise labels and a full stop; a search request needs neither. */
    fun transcribe(pcm: FloatArray, prompt: String? = null, fullWindow: Boolean = false): String? {
        if (handle == 0L) {
            val plan = CpuPlan.current()
            handle = native.load(modelFile.absolutePath, plan.threads, plan.mask)
            if (handle == 0L) return null
        }
        val t0 = System.nanoTime()
        // 50 encoder frames per second of audio, plus headroom; 0 would mean the full 30 s window.
        val audioCtx = if (fullWindow) 0 else minOf(1500, pcm.size / 320 + AUDIO_CTX_MARGIN)
        val raw = native.transcribe(handle, pcm, "en", prompt, audioCtx) ?: return null
        var text = raw.replace(Regex("\\[[^\\]]*\\]|\\([^)]*\\)"), " ").replace(Regex("\\s+"), " ").trim()
        // A truncated encoder window occasionally makes the decoder say the same sentence twice; keep one.
        val sentences = text.split(Regex("(?<=[.?!])\\s+")).map { it.trim(' ', '.', '?', '!') }
        if (sentences.size >= 2 && sentences.all { it.equals(sentences[0], ignoreCase = true) }) text = sentences[0]
        text = text.trimEnd('.', ',', '!')
        Log.i(TAG, "voice: '$text' (${pcm.size / RATE.toDouble()}s audio, ${(System.nanoTime() - t0) / 1_000_000}ms)")
        return text
    }

    private fun writeWav(file: java.io.File, pcm: FloatArray) {
        val data = ByteArray(pcm.size * 2)
        for (i in pcm.indices) { val v = (pcm[i] * 32767f).toInt().coerceIn(-32768, 32767); data[2 * i] = v.toByte(); data[2 * i + 1] = (v shr 8).toByte() }
        val header = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + data.size); put("WAVEfmt ".toByteArray()); putInt(16); putShort(1); putShort(1)
            putInt(RATE); putInt(RATE * 2); putShort(2); putShort(16); put("data".toByteArray()); putInt(data.size)
        }
        file.outputStream().use { it.write(header.array()); it.write(data) }
    }

    private fun unloadModel() {
        if (handle != 0L) native.free(handle)
        handle = 0L
    }

    private companion object {
        const val TAG = "nanosearch"
        const val RATE = 16_000
        const val FRAME = 320                 // 20 ms
        const val CALIBRATION_FRAMES = 15     // first 300 ms set the noise floor
        const val MIN_SPEECH_RMS = 350.0
        const val END_SILENCE_FRAMES = 45     // 900 ms of quiet after speech ends the recording
        const val NO_SPEECH_SECONDS = 6.0
        const val MAX_SECONDS = 12.0
        const val IDLE_UNLOAD_MS = 2 * 60_000L
        const val AUDIO_CTX_MARGIN = 100
    }
}
