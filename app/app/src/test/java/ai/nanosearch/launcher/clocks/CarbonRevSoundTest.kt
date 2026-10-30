package ai.nanosearch.launcher.clocks

import android.app.Application
import android.app.NotificationManager
import android.media.AudioManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],manifest=Config.NONE,application=Application::class)
class CarbonRevSoundTest {
    class Fake:RevAudioBackend {
        var audible=true;var focus=true;var starts=0;var focuses=0;var releases=0
        var finished:(()->Unit)?=null;var lost:(()->Unit)?=null
        override fun audible()=audible
        override fun requestFocus(lost:()->Unit):Boolean {focuses++;this.lost=lost;return focus}
        override fun start(finished:()->Unit):Boolean {starts++;this.finished=finished;return true}
        override fun release() {releases++}
    }
    @Test fun explicitTapOnlyNoOverlapAcrossHostsAndBusyTapsAreIgnored() {
        var time=1000L;val a=Fake();val b=Fake();var factories=0
        val first=CarbonRevSound({factories++;a},{time});val second=CarbonRevSound({b},{time})
        try {
            assertFalse(first.play());first.setActive(true);second.setActive(true)
            assertEquals(0,factories);assertTrue(first.play());assertFalse(first.play());assertFalse(second.play())
            assertEquals(1,a.starts);assertEquals(0,b.starts)
            a.finished!!.invoke();assertFalse(first.playing);assertEquals(1,a.releases)
            time+=2700;assertTrue(second.play());assertEquals(1,b.starts)
        } finally {first.stop();second.stop()}
    }
    @Test fun zeroVolumeAndRejectedFocusNeverStartOrLeaveOwnership() {
        val muted=Fake().apply {audible=false};val rejected=Fake().apply {focus=false};val okay=Fake()
        for(audio in listOf(muted,rejected,okay)) {
            val player=CarbonRevSound({audio},{1000})
            try {player.setActive(true);assertEquals(audio===okay,player.play())}
            finally {player.stop()}
        }
        assertEquals(0,muted.focuses);assertEquals(0,muted.starts);assertEquals(0,rejected.starts)
        assertEquals(1,okay.starts)
    }
    @Test fun hideFocusLossAndStaleCompletionCannotRestartOrStopNewPlayback() {
        var time=1000L;val first=Fake();val second=Fake();var count=0
        val player=CarbonRevSound({if(count++==0)first else second},{time})
        try {
            player.setActive(true);assertTrue(player.play());player.setActive(false)
            assertFalse(player.playing);assertEquals(1,first.releases)
            time+=1000;player.setActive(true);assertTrue(player.play())
            first.finished!!.invoke();assertTrue(player.playing)
            second.lost!!.invoke();assertFalse(player.playing);assertEquals(1,second.releases)
        } finally {player.stop()}
    }
    @Test fun acceptedTapDebounceIsNotAnAutoplayQueue() {
        var time=1000L;val audio=Fake();val player=CarbonRevSound({audio},{time})
        try {
            player.setActive(true);assertTrue(player.play());audio.finished!!.invoke()
            time+=100;assertFalse(player.play());assertEquals(1,audio.starts)
            time+=400;assertTrue(player.play());assertEquals(2,audio.starts)
        } finally {player.stop()}
    }
    @Test fun unavailableBackendFailsQuietlyWithoutAcquiringPlaybackOwnership() {
        val player=CarbonRevSound({throw IllegalStateException("Unavailable")},{1000})
        player.setActive(true);assertFalse(player.play());assertFalse(player.playing)
        val audio=Fake();val next=CarbonRevSound({audio},{1000})
        try {next.setActive(true);assertTrue(next.play())}finally{next.stop()}
    }
    @Test fun androidBackendHonorsMediaZeroDndAndFocusRejection() {
        val app=RuntimeEnvironment.getApplication()
        val manager=app.getSystemService(AudioManager::class.java)
        val notifications=app.getSystemService(NotificationManager::class.java)
        shadowOf(notifications).setNotificationPolicyAccessGranted(true)
        notifications.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
        val audio=AndroidRevAudioBackend(app)
        try {
            manager.setStreamVolume(AudioManager.STREAM_MUSIC,0,0);assertFalse(audio.audible())
            manager.setStreamVolume(AudioManager.STREAM_MUSIC,5,0);assertTrue(audio.audible())
            notifications.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY);assertFalse(audio.audible())
            notifications.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE);assertFalse(audio.audible())
            notifications.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
            shadowOf(manager).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
            assertFalse(audio.requestFocus {})
        } finally {audio.release()}
    }
    @Test fun synthesizedAssetHasExactProvenanceNoClippingAndSilentEndpoints() {
        val data=File("src/main/res/raw/nano_supercar_rev.wav").readBytes()
        assertEquals("ae92fdbfe61629238b0f1e89cedbfb5f1d64d374a022a612d06d67ecc2da3586",MessageDigest.getInstance("SHA-256").digest(data).joinToString("") {"%02x".format(it)})
        val header=ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(48000,header.getInt(24));assertEquals(1,header.getShort(22).toInt());assertEquals(16,header.getShort(34).toInt())
        val pcm=data.copyOfRange(44,data.size);assertEquals(2.65,pcm.size/2.0/48000,0.0001)
        val samples=ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        assertEquals(0,samples.get(0).toInt());assertEquals(0,samples.get(samples.limit()-1).toInt())
        for(i in 0 until samples.limit())assertTrue(kotlin.math.abs(samples.get(i).toInt())<=21300)
    }
}
