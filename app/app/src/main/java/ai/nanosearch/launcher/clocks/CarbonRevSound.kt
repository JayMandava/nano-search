package ai.nanosearch.launcher.clocks

import ai.nanosearch.launcher.R
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

internal interface RevAudioBackend {
    fun audible(): Boolean
    fun requestFocus(lost: () -> Unit): Boolean
    fun start(finished: () -> Unit): Boolean
    fun release()
}

/** Main-thread, process-wide single voice. Busy taps are ignored rather than queued/restarted. */
internal class CarbonRevSound(
    private val factory: () -> RevAudioBackend,
    private val now: () -> Long = SystemClock::elapsedRealtime,
) {
    private var active=false
    private var backend: RevAudioBackend?=null
    private var lastAccepted=Long.MIN_VALUE
    val playing: Boolean get() = backend!=null
    fun setActive(value:Boolean) {active=value;if(!value)stop()}
    fun play():Boolean {
        if(!active || owner!=null || (lastAccepted!=Long.MIN_VALUE && now()-lastAccepted<400L))return false
        val audio=runCatching {factory()}.getOrNull() ?: return false
        if(!audio.audible()) {audio.release();return false}
        backend=audio;owner=this
        if(!audio.requestFocus {if(backend===audio)stop()}) {stop();return false}
        if(backend!==audio)return false
        if(!audio.start {if(backend===audio)stop()}) {stop();return false}
        lastAccepted=now()
        return true
    }
    fun stop() {
        val old=backend;backend=null
        if(owner===this)owner=null
        old?.release()
    }
    companion object {private var owner:CarbonRevSound?=null}
}

/** MEDIA volume, conservative DND handling and transient focus; never initialized by drawing. */
internal class AndroidRevAudioBackend(context:Context):RevAudioBackend {
    private val app=context.applicationContext
    private val manager=app.getSystemService(AudioManager::class.java)
    private val notifications=app.getSystemService(NotificationManager::class.java)
    private val attributes=AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
    private var request:AudioFocusRequest?=null
    private var player:MediaPlayer?=null
    private var released=false
    override fun audible():Boolean = runCatching {
        manager!=null && manager.getStreamVolume(AudioManager.STREAM_MUSIC)>0 &&
            !manager.isStreamMute(AudioManager.STREAM_MUSIC) &&
            notifications?.currentInterruptionFilter==NotificationManager.INTERRUPTION_FILTER_ALL
    }.getOrDefault(false)
    override fun requestFocus(lost:()->Unit):Boolean {
        val focus=AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attributes).setAcceptsDelayedFocusGain(false).setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener({change -> if(change<0)lost()},Handler(Looper.getMainLooper())).build()
        request=focus
        return runCatching {manager?.requestAudioFocus(focus)==AudioManager.AUDIOFOCUS_REQUEST_GRANTED}.getOrDefault(false)
    }
    override fun start(finished:()->Unit):Boolean = runCatching {
        val media=MediaPlayer();player=media
        media.setAudioAttributes(attributes);media.setVolume(.35f,.35f)
        app.resources.openRawResourceFd(R.raw.nano_supercar_rev).use {fd -> media.setDataSource(fd.fileDescriptor,fd.startOffset,fd.length)}
        media.setOnCompletionListener {finished()}
        media.setOnErrorListener {_,_,_ -> finished();true}
        media.setOnPreparedListener {
            if(!released && audible())runCatching {media.start()}.onFailure {finished()}
            else finished()
        }
        media.prepareAsync();true
    }.getOrDefault(false)
    override fun release() {
        if(released)return
        released=true
        player?.let {media ->
            runCatching {media.setOnPreparedListener(null);media.setOnCompletionListener(null);media.setOnErrorListener(null)}
            runCatching {media.stop()};runCatching {media.release()}
        }
        player=null
        request?.let {runCatching {manager?.abandonAudioFocusRequest(it)}};request=null
    }
}
