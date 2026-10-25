package ai.nanosearch.launcher.clocks

import ai.nanosearch.launcher.LockClock
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Redirects only this fixture's prefs/files; never touches Nano's actual selection/photo/layout. */
internal class IsolatedClockContext(base: Context, private val fixtureName: String) : ContextWrapper(base) {
    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = super.getSharedPreferences("$name-$fixtureName", mode)
    private val fixtureFiles = File(base.cacheDir, fixtureName).apply { mkdirs() }
    override fun getFilesDir(): File = fixtureFiles
    override fun getApplicationContext(): Context = this
    fun clean() { baseContext.deleteSharedPreferences("nano-$fixtureName"); fixtureFiles.deleteRecursively() }
}

@RunWith(AndroidJUnit4::class)
class ClockPreferencesTest {
    private lateinit var context: IsolatedClockContext
    @Before fun createFixture() { context = IsolatedClockContext(InstrumentationRegistry.getInstrumentation().targetContext, "clock-test-${UUID.randomUUID()}") }
    @After fun cleanFixture() { context.clean() }

    @Test fun faceSelectionPersistsAndUnknownPreferencesRemainSteel() {
        val preferences = context.getSharedPreferences("nano", Context.MODE_PRIVATE)
        assertEquals(ClockFaces.STEEL_ID, LockClock.face(context))
        LockClock.setFace(context, ClockFaces.CARBON_ID)
        assertEquals(ClockFaces.CARBON_ID, LockClock.face(context))
        assertEquals(ClockFaces.CARBON_ID, preferences.getString("lockclock_face", null))
        LockClock.setFace(context, "unrecognised")
        assertEquals(ClockFaces.STEEL_ID, LockClock.face(context))
        preferences.edit().putString("lockclock_face", "removed-face").commit()
        assertEquals(ClockFaces.STEEL_ID, LockClock.face(context))
    }

    @Test fun switchingFacesAndMotionDoesNotChangeBackgroundPositionPhotoOrOtherPreferences() {
        val preferences = context.getSharedPreferences("nano", Context.MODE_PRIVATE)
        preferences.edit().putString("lockclock_bg", "photo").putString("lockclock_pos", "low").putString("home_widgets", "existing-layout").putBoolean("seeded_home", true).commit()
        val photo = LockClock.photoFile(context)
        val bytes = byteArrayOf(1, 2, 3, 4, 5)
        photo.writeBytes(bytes)
        val originalPositionOptions = LockClock.POSITIONS.toList()
        val before = preferences.all.toMap()
        LockClock.setFace(context, ClockFaces.CARBON_ID)
        LockClock.setReducedMotion(context, true)
        assertTrue(LockClock.reducedMotion(context))
        LockClock.cycleFace(context)
        assertEquals(ClockFaces.STEEL_ID, LockClock.face(context))
        assertEquals("photo", LockClock.background(context))
        assertEquals("low", LockClock.position(context))
        assertArrayEquals(bytes, photo.readBytes())
        assertEquals(originalPositionOptions, LockClock.POSITIONS)
        assertEquals(listOf("top", "middle", "low"), LockClock.POSITIONS.map { it.first })
        assertEquals(before, preferences.all.filterKeys { it !in setOf("lockclock_face", "clocks_reduced_motion") })
    }
}
