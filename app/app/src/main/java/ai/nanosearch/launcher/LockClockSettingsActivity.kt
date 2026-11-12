package ai.nanosearch.launcher

import ai.nanosearch.launcher.clocks.ClockFaces
import ai.nanosearch.launcher.clocks.SteelDialView
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Standalone v2 entry to the existing Steel Dial renderer and Android-owned wallpaper confirmation. */
class LockClockSettingsActivity : SmoothActivity() {
    private lateinit var backgroundButton: Button
    private lateinit var positionButton: Button
    private lateinit var motionButton: Button
    private val pickPhoto = 41
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The production app keeps its original wallpaper/settings flow.
        if (!BuildConfig.OS7_STANDALONE) { finish(); return }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(24))
            setBackgroundColor(0xFFF4F4F3.toInt())
        }
        fun text(label: String, size: Float = 15f) = TextView(this).apply {
            text = label; textSize = size; setTextColor(0xFF171B1F.toInt())
            setPadding(0, dp(8), 0, dp(8))
        }
        fun button(label: String, action: () -> Unit) = Button(this).apply {
            text = label; isAllCaps = false; minHeight = dp(48)
            setOnClickListener { action() }
            content.addView(this, LinearLayout.LayoutParams(-1, -2))
        }
        button("Back to Options") { finish() }
        content.addView(text("Steel Dial wallpaper", 22f).apply {
            setTextColor(Color.WHITE)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xFF555E66.toInt(), 0xFF171D24.toInt()))
        })
        content.addView(text("Live Steel Dial wallpaper."))
        content.addView(SteelDialView(this).apply {
            // This is a preview, not the ordinary Home widget's alarm shortcut.
            setOnClickListener(null); isClickable = false
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xFF30353A.toInt(), Color.BLACK))
        }, LinearLayout.LayoutParams(-1, dp(290)))
        val hasPersonalFont = runCatching { assets.open("Porscha911.ttf").use { true } }.getOrDefault(false)
        content.addView(text(if (hasPersonalFont) "911 Porscha numerals"
            else "Barlow numerals", 13f))
        backgroundButton = button("") { LockClock.cycleBackground(this); refreshChoices() }
        positionButton = button("") { LockClock.cyclePosition(this); refreshChoices() }
        motionButton = button("") { LockClock.setReducedMotion(this, !LockClock.reducedMotion(this)); refreshChoices() }
        button("Choose a background photo…") {
            runCatching {
                startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE), pickPhoto)
            }.onFailure { toast("A photo picker is unavailable on this device.") }
        }
        content.addView(text("If this wallpaper is active, your choices update it immediately.", 13f))
        button("Preview Steel Dial in Android…") {
            if (!LockClock.available(this)) toast("The clock wallpaper is unavailable in this build.")
            else runCatching {
                LockClock.setFace(this, ClockFaces.STEEL_ID)
                LockClock.openPicker(this)
            }.onFailure { toast("Android's wallpaper preview could not be opened.") }
        }
        content.addView(text("Confirm in Android’s preview to apply. Choose Home, Lock screen or both. Availability depends on your phone; Android may retain its own lock-screen clock.", 13f))
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(content)
            setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        }
        setContentView(scroll)
        refreshChoices()
    }

    private fun refreshChoices() {
        backgroundButton.text = "Background: ${LockClock.nameOf(LockClock.backgrounds(this), LockClock.background(this))}"
        positionButton.text = "Position: ${LockClock.nameOf(LockClock.POSITIONS, LockClock.position(this))}"
        motionButton.text = "Reduced motion: ${if (LockClock.reducedMotion(this)) "On" else "Off"}"
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    @Deprecated("Android photo picker result")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != pickPhoto || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        Thread {
            val saved = LockClock.setPhoto(this, uri)
            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    if (saved) refreshChoices() else toast("That photo could not be read.")
                }
            }
        }.start()
    }
}
