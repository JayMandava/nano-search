package ai.nanosearch.launcher

import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class Os7SettingsModeTest {
    private lateinit var context: Context

    @Before fun resetPreferences() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("nano", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun standaloneUsesOs7WhileLegacyModeChoicePersists() {
        assertEquals(BuildConfig.OS7_STANDALONE,AppSettings.os7Mode(context))
        AppSettings.setOs7Mode(context, false)
        assertEquals(BuildConfig.OS7_STANDALONE, AppSettings.os7Mode(context))
        AppSettings.setOs7Mode(context, true)
        assertTrue(AppSettings.os7Mode(context))
    }

    @Test fun standaloneDisablesFramesWithoutDestroyingPreference() {
        AppSettings.setFrames(context,true)
        assertEquals(!BuildConfig.OS7_STANDALONE, AppSettings.frames(context))
        assertTrue(context.getSharedPreferences("nano",Context.MODE_PRIVATE).getBoolean("frames",false))
    }

    @Test fun switchingInterfaceDoesNotRewriteExistingPreferences() {
        val prefs = context.getSharedPreferences("nano", Context.MODE_PRIVATE)
        prefs.edit().putString("themeMode", "dark")
            .putString("favourites", "example/.Main")
            .putString("iconPack", "example.icons")
            .putBoolean("hub", false)
            .putBoolean("fullScreen", true)
            .putBoolean("setupDone", true).commit()
        val before = prefs.all.toMap()
        AppSettings.setOs7Mode(context, false)
        AppSettings.setOs7Mode(context, true)
        before.forEach { (key, value) -> assertEquals("Unchanged preference: $key", value, prefs.all[key]) }
    }
}
