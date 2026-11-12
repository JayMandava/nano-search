package ai.nanosearch.launcher

import android.app.Activity
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.ceil

/** Actual UI on the isolated, fresh cloud emulator. No personal access or model downloads. */
@RunWith(AndroidJUnit4::class)
class Os7CloudSmokeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private val evidenceDir get() = File(context.getExternalFilesDir(null), "os7-cloud-qa").apply { mkdirs() }

    @Before fun isolatedOnboardedFixture() {
        assertTrue("Run only against the isolated cloud preview", context.packageName.endsWith(".os7preview"))
        assertTrue("This test exercises the standalone launcher", BuildConfig.OS7_STANDALONE)
        // These are the actual keys read by SetupActivity. Apply before either test can launch Home.
        assertTrue(context.getSharedPreferences("nano", 0).edit()
            .putBoolean("setupDone", true).putBoolean("setupShown", true).commit())
        assertFalse("Fixture must not redirect Home to onboarding", SetupActivity.needed(context))
    }

    private fun capture(name: String) {
        device.waitForIdle()
        assertTrue(device.takeScreenshot(File(evidenceDir, "$name.png")))
        device.dumpWindowHierarchy(File(evidenceDir, "$name.xml"))
    }

    private fun see(text: String) = assertNotNull("Missing $text", device.wait(Until.findObject(By.text(text)), 10000))

    private fun assertResumed(type: Class<out Activity>) {
        val deadline = SystemClock.uptimeMillis() + 10000
        var resumed = emptyList<String>()
        do {
            instrumentation.runOnMainSync {
                resumed = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .map { it.javaClass.name }
            }
            if (type.name in resumed) return
            SystemClock.sleep(50)
        } while (SystemClock.uptimeMillis() < deadline)
        fail("Expected ${type.simpleName} resumed, found $resumed")
    }

    private fun assertHome() {
        assertResumed(MainActivity::class.java)
        assertNotNull("MainActivity must show the OS7 home controls",
            device.wait(Until.findObject(By.desc("Home menu")), 10000))
        assertNotNull("Home must return to the All panel",
            device.wait(Until.findObject(By.desc("All panel, three rows")), 10000))
        assertEquals(context.packageName, device.currentPackageName)
        assertFalse("Models must no longer cover Home", device.hasObject(By.text("Models")))
        assertFalse("Setup must not intercept Home", device.hasObject(By.text("Step 1 of 4")))
    }

    private fun swipe(up: Boolean = true) {
        val x = device.displayWidth / 2
        val high = device.displayHeight / 4
        val low = device.displayHeight * 3 / 4
        assertTrue("Scroll gesture could not be injected", device.swipe(x, if (up) low else high, x, if (up) high else low, 25))
        device.waitForIdle()
    }

    /** Scoped emulator observations, with raw evidence. This is not a real-device FPS benchmark. */
    private fun measureScroll(name: String, scroll: () -> Unit) {
        device.waitForIdle()
        File(evidenceDir, "$name-gfx-reset.txt").writeText(device.executeShellCommand("dumpsys gfxinfo ${context.packageName} reset"))
        File(evidenceDir, "$name-gfx-before.txt").writeText(device.executeShellCommand("dumpsys gfxinfo ${context.packageName} framestats"))
        val started = System.nanoTime()
        var nominalHz = 0f
        instrumentation.runOnMainSync {
            nominalHz = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .firstOrNull()?.display?.refreshRate ?: 0f
        }
        try {
            scroll()
        } finally {
            device.waitForIdle()
            val raw = device.executeShellCommand("dumpsys gfxinfo ${context.packageName} framestats")
            File(evidenceDir, "$name-gfx-after.txt").writeText(raw)
            // gfxinfo can include older rows and multiple windows. Select only valid frames
            // started within this sample; keep raw output to audit dropped/nonzero-flag rows.
            val frameNs = mutableListOf<Long>()
            var columns = emptyList<String>()
            raw.lineSequence().forEach { line ->
                val values = line.trim().split(',').map { it.trim() }
                if (values.firstOrNull() == "Flags") columns = values
                else if (columns.isNotEmpty() && values.firstOrNull()?.toLongOrNull() != null) {
                    fun value(key: String) = values.getOrNull(columns.indexOf(key))?.toLongOrNull()
                    val intended = value("IntendedVsync")
                    val completed = value("FrameCompleted")
                    if (value("Flags") == 0L && intended != null && completed != null && intended >= started && completed >= intended) {
                        frameNs += completed - intended
                    }
                }
            }
            val sorted = frameNs.sorted()
            fun percentile(p: Double): Any = if (sorted.isEmpty()) JSONObject.NULL
                else sorted[(ceil(sorted.size * p).toInt() - 1).coerceIn(sorted.indices)] / 1_000_000.0
            val summary = JSONObject()
                .put("scope", name)
                .put("environment", "cloud emulator; not a device FPS or before/after-build comparison")
                .put("sampleWallMs", (System.nanoTime() - started) / 1_000_000.0)
                .put("nominalDisplayHz", nominalHz)
                .put("recordedValidFrames", sorted.size)
                .put("medianFrameMs", percentile(0.50))
                .put("p95FrameMs", percentile(0.95))
                .put("p99FrameMs", percentile(0.99))
                .put("framesOver16_67Ms", sorted.count { it > 16_666_667L })
                .put("framesOver11_11Ms", sorted.count { it > 11_111_111L })
                .put("framesOver8_33Ms", sorted.count { it > 8_333_333L })
                .put("frameDurationsNs", JSONArray(sorted))
                .put("limitations", "gfxinfo retains a bounded frame history; observed durations are not sustained FPS. No passing performance threshold is claimed.")
            File(evidenceDir, "$name-frames.json").writeText(summary.toString(2))
        }
    }

    @Test fun settingsModelsRepeatedNavigationAndLayouts() {
        val originalFont = device.executeShellCommand("settings get system font_scale").trim()
        for ((name, width, font) in listOf(Triple("normal", 1080, "1.0"), Triple("narrow", 900, "1.0"), Triple("large-text", 1080, "1.3"))) {
            try {
                device.executeShellCommand("wm size ${width}x2400")
                device.executeShellCommand("settings put system font_scale $font")
                ActivityScenario.launch(SettingsActivity::class.java).use {
                    see("Settings")
                    assertResumed(SettingsActivity::class.java)
                    capture("$name-options")
                    measureScroll("$name-options-scroll") { repeat(3) { swipe() } }
                    capture("$name-options-bottom")
                }
                ActivityScenario.launch(ModelsActivity::class.java).use {
                    see("Models")
                    assertResumed(ModelsActivity::class.java)
                    capture("$name-models-top")
                    measureScroll("$name-models-scroll") {
                        repeat(4) { swipe() }
                        repeat(4) { swipe(up = false) }
                    }
                    see("Everything runs on this phone")
                    capture("$name-models-return-top")
                    repeat(4) { index -> swipe(); capture("$name-models-$index") }
                }
            } finally {
                device.executeShellCommand("wm size reset")
                if (originalFont == "null" || originalFont.isEmpty()) device.executeShellCommand("settings delete system font_scale")
                else device.executeShellCommand("settings put system font_scale $originalFont")
            }
        }
    }

    @Test fun repeatedHomeAndSettingsReturn() {
        val component = "${context.packageName}/ai.nanosearch.launcher.MainActivity"
        File(evidenceDir, "set-home-result.txt").writeText(device.executeShellCommand("cmd package set-home-activity $component"))
        assertTrue(device.pressHome())
        assertHome()
        repeat(4) { iteration ->
            device.findObject(By.desc("Home menu")).click()
            val options = device.wait(Until.findObject(By.text("Options")), 10000)
            assertNotNull("Home menu must offer Options", options)
            options!!.click()
            see("Settings")
            assertResumed(SettingsActivity::class.java)
            val models = device.wait(Until.findObject(By.text("On-device models")), 10000)
            assertNotNull("Settings must offer Models", models)
            models!!.click()
            see("Models")
            assertResumed(ModelsActivity::class.java)
            // Back must return to Settings, not another copy of Models or Home.
            assertTrue(device.pressBack())
            see("Settings")
            assertResumed(SettingsActivity::class.java)
            device.findObject(By.text("On-device models")).click()
            see("Models")
            assertResumed(ModelsActivity::class.java)
            // Package-only checks are insufficient: all three activities have the same package.
            assertTrue(device.pressHome())
            assertHome()
            capture("home-from-models-$iteration")
        }
    }
}
