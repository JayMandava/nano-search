package ai.nanosearch.launcher

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.ModelDownloadListener
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * Voice search through Android's speech recognizer. It asks for the on-device recognizer when the phone has one
 * and sets "prefer offline" either way; whether audio stays on the phone depends on the speech service installed.
 * Every outcome is reported: [onError] gets the recognizer's error code so the caller can tell the user what happened.
 * The caller owns the RECORD_AUDIO permission.
 */
class VoiceInput(
    private val activity: Activity,
    private val onPartial: (String) -> Unit,
    private val onFinal: (String) -> Unit,
    private val onState: (listening: Boolean) -> Unit,
    private val onError: (Int) -> Unit,
) : Voice {
    private var recognizer: SpeechRecognizer? = null

    override val listening get() = recognizer != null

    override val available: Boolean
        get() = SpeechRecognizer.isOnDeviceRecognitionAvailable(activity) || SpeechRecognizer.isRecognitionAvailable(activity)

    override fun toggle() { if (listening) stop() else start() }

    private fun recognizerIntent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
    }

    fun start(): Boolean {
        if (recognizer != null) return true
        val onDevice = SpeechRecognizer.isOnDeviceRecognitionAvailable(activity)
        val r = when {
            onDevice -> SpeechRecognizer.createOnDeviceSpeechRecognizer(activity)
            SpeechRecognizer.isRecognitionAvailable(activity) -> SpeechRecognizer.createSpeechRecognizer(activity)
            else -> return false
        }
        Log.i(TAG, "voice: starting, onDevice=$onDevice")
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
            override fun onPartialResults(partialResults: Bundle?) {
                first(partialResults)?.let(onPartial)
            }
            override fun onResults(results: Bundle?) {
                val text = first(results)
                Log.i(TAG, "voice: result '$text'")
                finish()
                if (!text.isNullOrBlank()) onFinal(text) else onError(SpeechRecognizer.ERROR_NO_MATCH)
            }
            override fun onError(error: Int) {
                Log.i(TAG, "voice: error $error")
                finish()
                this@VoiceInput.onError(error)
            }
        })
        recognizer = r
        r.startListening(recognizerIntent())
        onState(true)
        return true
    }

    fun stop() {
        recognizer?.stopListening() // final results (or an error) arrive through the listener
    }

    override fun release() {
        finish()
    }

    /**
     * Asks the speech service to fetch the offline language pack for the recognizer's locale.
     * [onStatus] gets short progress strings: "scheduled", "progress N", "done", "error N", "unsupported".
     */
    fun downloadPack(onStatus: (String) -> Unit) {
        if (Build.VERSION.SDK_INT < 34 || !SpeechRecognizer.isOnDeviceRecognitionAvailable(activity)) {
            onStatus("unsupported")
            return
        }
        val r = SpeechRecognizer.createOnDeviceSpeechRecognizer(activity)
        r.triggerModelDownload(recognizerIntent(), activity.mainExecutor, object : ModelDownloadListener {
            override fun onProgress(completedPercent: Int) {
                Log.i(TAG, "voice: pack download $completedPercent%")
                onStatus("progress $completedPercent")
            }
            override fun onSuccess() {
                Log.i(TAG, "voice: pack ready")
                onStatus("done")
                r.destroy()
            }
            override fun onScheduled() {
                Log.i(TAG, "voice: pack download scheduled")
                onStatus("scheduled")
            }
            override fun onError(error: Int) {
                Log.i(TAG, "voice: pack download error $error")
                onStatus("error $error")
                r.destroy()
            }
        })
    }

    private fun finish() {
        recognizer?.destroy()
        recognizer = null
        onState(false)
    }

    private fun first(b: Bundle?): String? = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()

    private companion object {
        const val TAG = "nanosearch"
    }
}
