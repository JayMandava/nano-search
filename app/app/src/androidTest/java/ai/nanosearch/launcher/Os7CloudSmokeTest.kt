package ai.nanosearch.launcher

import android.content.Intent
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual UI on the isolated, fresh cloud emulator. No personal access or model downloads. */
@RunWith(AndroidJUnit4::class)
class Os7CloudSmokeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private fun capture(name: String) {
        device.waitForIdle()
        val dir = File(context.getExternalFilesDir(null), "os7-cloud-qa").apply { mkdirs() }
        assertTrue(device.takeScreenshot(File(dir, "$name.png")))
        device.dumpWindowHierarchy(File(dir, "$name.xml"))
    }
    private fun see(text: String) = assertNotNull("Missing $text", device.wait(Until.findObject(By.text(text)), 10000))

    @Test fun settingsModelsRepeatedNavigationAndLayouts() {
        for ((name, width, font) in listOf(Triple("normal", 1080, "1.0"), Triple("narrow", 900, "1.0"), Triple("large-text", 1080, "1.3"))) {
            device.executeShellCommand("wm size ${width}x2400")
            device.executeShellCommand("settings put system font_scale $font")
            try {
                ActivityScenario.launch(SettingsActivity::class.java).use {
                    see("Settings")
                    capture("$name-options")
                    repeat(3) { device.swipe(width / 2, 1800, width / 2, 600, 25); device.waitForIdle() }
                    capture("$name-options-bottom")
                }
                ActivityScenario.launch(ModelsActivity::class.java).use {
                    see("Models")
                    capture("$name-models-top")
                    repeat(4) { index ->
                        device.swipe(width / 2, 1800, width / 2, 600, 25)
                        device.waitForIdle()
                        capture("$name-models-$index")
                    }
                    repeat(4) { device.swipe(width / 2, 600, width / 2, 1800, 25) }
                    see("Models")
                }
            } finally {
                device.executeShellCommand("wm size reset")
                device.executeShellCommand("settings put system font_scale 1.0")
            }
        }
    }

    @Test fun repeatedHomeAndSettingsReturn() {
        context.getSharedPreferences("nano", 0).edit().putBoolean("setupDone", true).commit()
        val component = "${context.packageName}/ai.nanosearch.launcher.MainActivity"
        device.executeShellCommand("cmd package set-home-activity $component")
        repeat(4) {
            ActivityScenario.launch(ModelsActivity::class.java).use {
                see("Models")
                device.pressHome()
                assertTrue(device.wait(Until.hasObject(By.pkg(context.packageName).depth(0)), 10000))
                capture("home-from-models-$it")
            }
        }
    }
}
