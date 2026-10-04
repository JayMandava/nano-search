package ai.nanosearch.launcher

import ai.nanosearch.launcher.ui.MenuItem
import ai.nanosearch.launcher.ui.Overlays
import ai.nanosearch.launcher.ui.PageDots
import ai.nanosearch.launcher.ui.Palette
import ai.nanosearch.launcher.ui.PickApp
import ai.nanosearch.launcher.ui.pressScale
import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.app.ActivityOptions
import android.appwidget.AppWidgetHost
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.util.LruCache
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextClock
import android.widget.TextView
import android.widget.Toast
import androidx.viewpager.widget.PagerAdapter
import androidx.viewpager.widget.ViewPager
import java.io.File
import java.util.concurrent.Executors

/**
 * Home screen with a swipe-up app drawer. One search bar sits at the top of both and searches everything on the
 * device; pressing Enter acts on the request (open, call, message) or answers it. Long-press the home screen for
 * widgets and wallpaper, long-press a dock icon to change it.
 */
class MainActivity : Activity() {

    private lateinit var index: SearchIndex
    private val models get() = Services.models
    private val parser get() = Services.parser
    private val answerer get() = Services.answerer
    private val llm get() = Services.llm

    // layers
    private lateinit var home: DragLayout
    private lateinit var drawer: DragLayout
    private lateinit var searchLayer: LinearLayout
    private lateinit var homeContent: LinearLayout
    private lateinit var dockRow: LinearLayout
    private lateinit var pager: ViewPager
    private lateinit var dots: PageDots
    private var pages: List<HomePage> = emptyList()
    private val widgetHost by lazy { AppWidgetHost(this, HomeWidgets.HOST_ID) }
    private lateinit var overlayHost: FrameLayout
    private lateinit var overlays: Overlays
    // search
    private lateinit var input: EditText
    private lateinit var mic: ImageView
    private lateinit var clockBlock: LinearLayout
    private lateinit var resultsPanel: LinearLayout
    private lateinit var list: ListView
    private lateinit var divider: View
    private lateinit var grid: GridView
    private lateinit var banner: TextView
    // answer card
    private lateinit var card: LinearLayout
    private lateinit var cardProgress: ProgressBar
    private lateinit var thread: LinearLayout
    private lateinit var wallpaperScreen: WallpaperScreen
    private lateinit var cardPulse: TextView
    private lateinit var cardScroll: ScrollView
    private lateinit var pulse: ObjectAnimator
    private lateinit var micPulse: ObjectAnimator
    private lateinit var voice: Voice
    private var voiceFile: File? = null
    private var builtThemeKey = ""

    private val results = ResultAdapter()
    private val apps = AppGridAdapter()
    private val io = Executors.newSingleThreadExecutor()
    private val indexer = Executors.newSingleThreadExecutor() // slow indexing (thousands of photos) must never delay a search
    private val thumbExecutor = Executors.newFixedThreadPool(2)
    private val embedder = Executors.newSingleThreadExecutor() // photo fingerprinting: long and low priority, never shares a thread with search
    private val embedRunning = java.util.concurrent.atomic.AtomicBoolean(false)
    private val embedAgain = java.util.concurrent.atomic.AtomicBoolean(false) // a trigger arrived while a run was under way
    private val thumbs = LruCache<String, android.graphics.Bitmap>(160)
    private val ui = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("nano", MODE_PRIVATE) }
    private val screenH get() = resources.displayMetrics.heightPixels.toFloat()
    private var drawerOpen = false
    private var searchActive = false
    private var searchSeq = 0
    private var parseSeq = 0
    private var askSeq = 0

    /** The open chat: while it is open every message continues it, until the user closes it with the card's ✕ or back. */
    private class ChatTurn(val q: String, var a: String = "")
    private val chat = ArrayList<ChatTurn>()
    private var chatOpen = false

    /** True from the moment a question is sent until its answer is finished, stopped or abandoned; while it is, Enter is ignored and the mic button is Stop. */
    private var busy = false
    private var stopSeq = -1
    private var micPhase = 0
    private var action = "search" // what tapping a contact does; set by the model's reading of the query
    private var lastParse: Pair<String, Parsed?>? = null

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            refreshIndex(heavy = false)
            rebuildDock()
        }
    }
    private val parseRunnable = Runnable { parseCurrent() }

    /** Material 3 colours for everything the launcher paints itself; rebuilt if the user changes the theme in Settings. */
    private val pal by lazy { Palette.of(this) }
    private val themeKey get() = AppSettings.themeMode(this) + AppSettings.dynamicColor(this) + resources.configuration.uiMode

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun round(color: Int, radiusDp: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }

    // ---------------------------------------------------------------- setup

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Services.init(this)
        builtThemeKey = themeKey
        index = SearchIndex(this)

        overlayHost = FrameLayout(this)
        overlays = Overlays(this, overlayHost, pal)
        wallpaperScreen = WallpaperScreen(
            this, overlayHost, pal,
            pageCount = { pageIds().size },
            pickPhoto = { tryStart { startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE), REQ_PHOTO) } },
            openSystemApp = { tryStart { startActivity(Intent(Intent.ACTION_SET_WALLPAPER).setPackage("com.android.wallpaper")) } },
            openLive = { tryStart { startActivity(Intent(android.app.WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER)) } },
        )
        home = buildHome()
        drawer = buildDrawer()
        drawer.translationY = screenH
        drawer.visibility = View.INVISIBLE
        searchLayer = buildSearchLayer()
        voice = buildVoice()

        val root = FrameLayout(this).apply {
            addView(home, FrameLayout.LayoutParams(-1, -1))
            addView(drawer, FrameLayout.LayoutParams(-1, -1))
            addView(searchLayer, FrameLayout.LayoutParams(-1, -2))
            addView(overlayHost, FrameLayout.LayoutParams(-1, -1)) // menus and sheets, above everything
            // targetSdk 35 is edge-to-edge: keep content clear of the system bars and the keyboard.
            setOnApplyWindowInsetsListener { _, insets ->
                val sys = insets.getInsets(WindowInsets.Type.systemBars())
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                val barSpace = dp(BAR_SPACE_DP)
                searchLayer.setPadding(bars.left, sys.top, bars.right, bars.bottom)
                home.setPadding(bars.left, sys.top + barSpace, bars.right, sys.bottom)
                drawer.setPadding(bars.left, sys.top + barSpace, bars.right, bars.bottom)
                insets
            }
        }
        setContentView(root)
        if (savedInstanceState == null) consumeDebugExtras(intent) // never on recreation (rotation replays the launch intent)
    }

    /**
     * Debug hooks are one-shot: each extra is removed as it is used, so recreating the activity (rotation, theme change)
     * can never run it again against files that are gone.
     */
    private fun consumeDebugExtras(i: Intent) {
        if (i.hasExtra("bench")) { i.removeExtra("bench"); runBench() }
        if (i.hasExtra("tune")) { i.removeExtra("tune"); CpuTuner.run(this, force = true, dryRunWith = CpuPlan.candidates + CpuPlan.Plan((1L shl CpuPlan.coreCount) - 1, 6, "all cores, 6 threads", "all")) }
        if (i.hasExtra("askbench")) { i.removeExtra("askbench"); runAskBench() }
        i.getStringExtra("wallpaper_uri")?.let {
            val which = when (i.getStringExtra("wallpaper_which")) {
                "home" -> android.app.WallpaperManager.FLAG_SYSTEM
                "lock" -> android.app.WallpaperManager.FLAG_LOCK
                else -> android.app.WallpaperManager.FLAG_SYSTEM or android.app.WallpaperManager.FLAG_LOCK
            }
            i.removeExtra("wallpaper_uri"); i.removeExtra("wallpaper_which"); setWallpaperFrom(Uri.parse(it), which)
        }
        i.getStringExtra("wallpaper_crop")?.let { i.removeExtra("wallpaper_crop"); wallpaperScreen.startCrop(Uri.parse(it)) }
        if (i.hasExtra("wallpaper_backup")) { i.removeExtra("wallpaper_backup"); backupWallpapers() }
        if (i.hasExtra("wallpaper_restore")) { i.removeExtra("wallpaper_restore"); restoreWallpapers() }
        i.getStringExtra("whisper_wav")?.let { i.removeExtra("whisper_wav"); transcribeWav(it) }
        i.getStringExtra("ocr_test")?.let { i.removeExtra("ocr_test"); ocrTest(it) }
        i.getStringExtra("clip_tokenize")?.let { i.removeExtra("clip_tokenize"); it.split('|').forEach { q -> Log.i(TAG, "clip tokens '$q': ${Services.clip.debugTokens(q)}") } }
    }

    /** Debug hooks: keep the current home and lock wallpapers in the app's files, and put them back, so tests never lose them. */
    private fun backupWallpapers() = io.execute {
        val wm = android.app.WallpaperManager.getInstance(this)
        for ((name, which) in listOf("home" to android.app.WallpaperManager.FLAG_SYSTEM, "lock" to android.app.WallpaperManager.FLAG_LOCK)) {
            val f = File(filesDir, "wallpaper_backup_$name")
            f.delete()
            runCatching {
                wm.getWallpaperFile(which)?.use { pfd -> java.io.FileInputStream(pfd.fileDescriptor).use { input -> f.outputStream().use { input.copyTo(it) } } }
            }.onFailure { Log.w(TAG, "wallpaper backup $name failed: $it") }
            Log.i(TAG, "wallpaper backup $name: ${if (f.exists()) "${f.length()} bytes" else "none (default or unreadable)"}")
        }
    }

    private fun restoreWallpapers() = io.execute {
        val wm = android.app.WallpaperManager.getInstance(this)
        runCatching { wm.suggestDesiredDimensions(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels) } // a wide test wallpaper may have changed it
        val home = File(filesDir, "wallpaper_backup_home")
        val lock = File(filesDir, "wallpaper_backup_lock")
        val both = android.app.WallpaperManager.FLAG_SYSTEM or android.app.WallpaperManager.FLAG_LOCK
        runCatching {
            if (home.exists() && !lock.exists()) {
                // The lock screen had no wallpaper of its own: it shared the home one. Put it back that way.
                home.inputStream().use { wm.setStream(it, null, true, both) }
                Log.i(TAG, "wallpaper restore: home image set for home and lock, as it was")
            } else {
                for ((name, which, f) in listOf(Triple("home", android.app.WallpaperManager.FLAG_SYSTEM, home), Triple("lock", android.app.WallpaperManager.FLAG_LOCK, lock))) {
                    if (f.exists()) f.inputStream().use { wm.setStream(it, null, true, which) } else wm.clear(which)
                    Log.i(TAG, "wallpaper restore $name: ${if (f.exists()) "from backup" else "cleared to default"}")
                }
            }
        }.onFailure { Log.w(TAG, "wallpaper restore failed: $it") }
    }

    /** Debug hook: `--es ocr_test /sdcard/x.png` logs what the text reader finds in an image file, and how long it takes. */
    private fun ocrTest(path: String) {
        embedder.execute {
            val t0 = System.nanoTime()
            val bmp = runCatching { ImageDecoder.decodeBitmap(ImageDecoder.createSource(File(path))) { d, _, _ -> d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE } }.getOrNull()
                ?: return@execute Unit.also { Log.i(TAG, "ocr_test: cannot decode $path") }
            val t1 = System.nanoTime()
            val boxes = Services.ocr.detect(bmp)
            val t2 = System.nanoTime()
            val lines = Services.ocr.read(bmp, boxes)
            val t3 = System.nanoTime()
            Log.i(TAG, "ocr_test ${File(path).name}: ${bmp.width}x${bmp.height}, ${boxes.size} boxes, ${lines.size} lines | decode ${(t1 - t0) / 1_000_000} ms, detect ${(t2 - t1) / 1_000_000} ms, read ${(t3 - t2) / 1_000_000} ms")
            lines.forEach { Log.i(TAG, "ocr_test   [%.2f] %s".format(it.confidence, it.text)) }
        }
    }

    /** Debug hook: `--es whisper_wav /sdcard/x.wav` (16 kHz mono 16-bit) logs what Whisper hears. */
    private fun transcribeWav(path: String) {
        val v = voice as? LocalVoice ?: return
        llm.execute {
            val bytes = File(path).readBytes()
            val pcm = FloatArray((bytes.size - 44) / 2) { i ->
                ((bytes[44 + 2 * i].toInt() and 0xFF) or (bytes[45 + 2 * i].toInt() shl 8)).toShort() / 32768f
            }
            Log.i(TAG, "wav '${File(path).name}' -> '${v.transcribe(pcm, fullWindow = intent.getBooleanExtra("full_window", false))}'")
        }
    }

    // ---- top search bar + results (shared by home and drawer)

    private fun buildSearchLayer(): LinearLayout {
        input = EditText(this).apply {
            hint = "Search"
            setHintTextColor(pal.onVariant)
            setTextColor(pal.onSurface)
            textSize = 18f
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_GO
            background = round(pal.bar, 28)
            val lens = getDrawable(R.drawable.ic_search)!!.mutate().apply { setTint(pal.onVariant); setBounds(0, 0, dp(24), dp(24)) }
            setCompoundDrawablesRelative(lens, null, null, null)
            compoundDrawablePadding = dp(12)
            setPadding(dp(18), 0, dp(20), 0)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) = Unit
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) = Unit
                override fun afterTextChanged(s: Editable?) = onQueryChanged()
            })
            // Enter (on-screen or hardware) acts on the request or answers it.
            setOnEditorActionListener { _, actionId, event ->
                if (actionId == EditorInfo.IME_ACTION_GO || event?.action == KeyEvent.ACTION_DOWN) submit()
                true
            }
        }
        mic = ImageView(this).apply {
            setImageResource(R.drawable.ic_mic) // the Material mic glyph, as in Google's search bar
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(15), dp(15), dp(15), dp(15))
            background = round(pal.bar, 28)
            imageTintList = android.content.res.ColorStateList.valueOf(pal.onVariant)
            contentDescription = "Voice search"
            setOnClickListener { if (busy) stopAnswer() else toggleVoice() }
        }
        micPulse = ObjectAnimator.ofFloat(mic, "alpha", 0.35f, 1f).apply {
            duration = 600
            repeatMode = ObjectAnimator.REVERSE
            repeatCount = ObjectAnimator.INFINITE
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(input, LinearLayout.LayoutParams(0, dp(56), 1f))
            addView(mic, LinearLayout.LayoutParams(dp(56), dp(56)).apply { leftMargin = dp(8) })
        }

        list = ListView(this).apply {
            adapter = results
            divider = null
            clipToPadding = false
            setPadding(0, dp(4), 0, dp(24))
            setOnItemClickListener { _, _, position, _ ->
                val item = results.getItem(position)
                if (item.kind == "ask") submit() else open(item)
            }
        }
        card = buildCard()
        divider = View(this).apply { setBackgroundColor(pal.outline); visibility = View.GONE }
        resultsPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(pal.panel)
            visibility = View.GONE
            isClickable = true // swallow touches so nothing underneath reacts
            addView(card, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(12), dp(8), dp(12), 0) })
            addView(divider, LinearLayout.LayoutParams(-1, dp(1)).apply { setMargins(dp(20), dp(12), dp(20), dp(4)) })
            addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(bar, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(16), dp(10), dp(16), dp(6)) })
            addView(resultsPanel, LinearLayout.LayoutParams(-1, 0, 1f))
        }
    }

    /**
     * The answer card is a real row: card, divider, then results. The progress bar is its own row inside the card
     * and the close button has its own padded corner, so nothing overlaps.
     */
    private fun buildCard(): LinearLayout {
        cardProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            indeterminateTintList = android.content.res.ColorStateList.valueOf(pal.primary)
        }
        thread = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        cardPulse = TextView(this).apply {
            text = "✦"
            textSize = 22f
            setTextColor(pal.primary)
            setPadding(dp(22), dp(18), dp(22), dp(18))
            visibility = View.GONE
        }
        pulse = ObjectAnimator.ofFloat(cardPulse, "alpha", 0.25f, 1f).apply {
            duration = 650
            repeatMode = ObjectAnimator.REVERSE
            repeatCount = ObjectAnimator.INFINITE
        }
        cardScroll = MaxHeightScrollView(this, (screenH * 0.45f).toInt()).apply {
            visibility = View.GONE
            isFocusable = false // typing must stay in the search bar
            setPadding(dp(20), dp(16), dp(52), dp(16)) // right padding keeps text clear of the close button
            addView(thread)
        }
        val close = TextView(this).apply {
            text = "✕"
            setTextColor(pal.onVariant)
            textSize = 16f
            gravity = Gravity.CENTER
            setOnClickListener { endChat() }
        }
        val body = FrameLayout(this).apply {
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(cardPulse)
                addView(cardScroll)
            }, FrameLayout.LayoutParams(-1, -2))
            addView(close, FrameLayout.LayoutParams(dp(44), dp(44), Gravity.END or Gravity.TOP))
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = round(pal.card, 28)
            visibility = View.GONE
            addView(body, LinearLayout.LayoutParams(-1, -2))
            addView(cardProgress, LinearLayout.LayoutParams(-1, dp(3)).apply { setMargins(dp(20), 0, dp(20), dp(10)) })
        }
    }

    // ---- home

    private fun buildHome(): DragLayout = DragLayout(this, up = true).apply {
        val clock = TextClock(this@MainActivity).apply {
            format12Hour = "h:mm"
            format24Hour = "HH:mm"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 76f)
            typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL)
            setTextColor(Color.WHITE)
            setShadowLayer(8f, 0f, 2f, 0x66000000)
        }
        val date = TextClock(this@MainActivity).apply {
            format12Hour = "EEEE, d MMMM"
            format24Hour = "EEEE, d MMMM"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(0xEEFFFFFF.toInt())
            setShadowLayer(6f, 0f, 1f, 0x66000000)
        }
        dockRow = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = round(pal.dock, 36)
            setPadding(dp(8), dp(10), dp(8), dp(10))
        }
        pager = ViewPager(this@MainActivity).apply {
            setPageTransformer(false) { page, pos -> page.alpha = 1f - kotlin.math.min(kotlin.math.abs(pos), 1f) * 0.5f }
            addOnPageChangeListener(object : ViewPager.SimpleOnPageChangeListener() {
                override fun onPageScrolled(position: Int, positionOffset: Float, positionOffsetPixels: Int) { dots.setPosition(position + positionOffset); slideWallpaper(position + positionOffset) }
            })
        }
        dots = PageDots(this@MainActivity, Color.WHITE)
        clockBlock = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28), dp(4), dp(28), dp(8))
            addView(clock)
            addView(date)
            visibility = if (prefs.getBoolean("showClock", true)) View.VISIBLE else View.GONE
            var lx = 0f; var ly = 0f
            setOnTouchListener { _, e -> if (e.actionMasked == android.view.MotionEvent.ACTION_DOWN) { lx = e.rawX; ly = e.rawY }; false }
            setOnLongClickListener {
                overlays.popup(lx, ly, ly, listOf(listOf(MenuItem(R.drawable.ic_menu_remove, "Remove clock") { setClockShown(false) })))
                true
            }
        }
        homeContent = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(pager, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(dots, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.CENTER_HORIZONTAL })
            // swipe-up handle
            addView(View(this@MainActivity).apply { background = round(0x99FFFFFF.toInt(), 2) },
                LinearLayout.LayoutParams(dp(36), dp(4)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(2); bottomMargin = dp(12) })
            addView(dockRow, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(16), 0, dp(16), dp(16)) })
        }
        addView(homeContent, FrameLayout.LayoutParams(-1, -1))
        rebuildDock()
        buildPages(0)

        // Long-press any empty home space for the home menu; tap it to put the keyboard away.
        var homeX = 0f; var homeY = 0f
        setOnTouchListener { _, e -> if (e.actionMasked == android.view.MotionEvent.ACTION_DOWN) { homeX = e.rawX; homeY = e.rawY }; false }
        isLongClickable = true
        setOnLongClickListener { showHomeMenu(homeX, homeY); true }
        setOnClickListener { if (input.hasFocus()) { hideKeyboard(); input.clearFocus() } }

        // Swipe up opens the drawer, unless the page's content can still scroll that way.
        canStart = { !currentPage().scroll.canScrollVertically(1) }
        onDrag = { dy -> drawer.visibility = View.VISIBLE; drawer.translationY = (screenH - dy).coerceIn(0f, screenH); applyDrawerProgress() }
        onRelease = { vy, dragged ->
            if (dragged) {
                if (vy < -800f || drawer.translationY < screenH * 0.65f) openDrawer() else closeDrawer(clear = false)
            }
        }

        // Swipe down pulls the notification panel, unless something on screen is using the gesture.
        canPullDown = { !searchActive && !drawerOpen && !overlays.isShowing && !currentPage().scroll.canScrollVertically(-1) }
        onPullDown = { expandNotifications() }
    }

    /** The system has no public call for this; the hidden one needs the EXPAND_STATUS_BAR permission, which the manifest declares. */
    private fun expandNotifications() {
        try {
            val bar = getSystemService("statusbar") ?: return
            bar.javaClass.getMethod("expandNotificationsPanel").invoke(bar)
        } catch (e: Exception) {
            Log.w(TAG, "cannot open the notification panel: $e")
        }
    }

    // ---- home pages

    private fun currentPage(): HomePage = pages[pager.currentItem.coerceIn(0, pages.size - 1)]

    private fun pageIds(): List<String> = prefs.getString("pages", "0")!!.split(',').filter { it.isNotBlank() }.ifEmpty { listOf("0") }

    /** (Re)builds every page from what is saved. The clock rides at the top of whichever page is first. */
    private fun buildPages(showIndex: Int) {
        (clockBlock.parent as? ViewGroup)?.removeView(clockBlock)
        pages = pageIds().map { id ->
            HomePage(this, id, widgetHost, overlays, pal,
                onEmptyLongPress = { x, y -> showHomeMenu(x, y) },
                launch = { cn, v -> launchComponent(cn, v) },
                appInfo = { cn -> showAppInfo(cn.packageName) },
            ).also { it.restore() }
        }
        pages.first().root.addView(clockBlock, 0, LinearLayout.LayoutParams(-1, -2))
        pager.adapter = object : PagerAdapter() {
            override fun getCount() = pages.size
            override fun isViewFromObject(view: View, obj: Any) = view === obj
            override fun instantiateItem(container: ViewGroup, position: Int): Any = pages[position].root.also { container.addView(it) }
            override fun destroyItem(container: ViewGroup, position: Int, obj: Any) = container.removeView(obj as View)
            override fun getItemPosition(obj: Any) = POSITION_NONE
        }
        pager.offscreenPageLimit = pages.size.coerceAtLeast(1)
        dots.setCount(pages.size)
        pager.setCurrentItem(showIndex.coerceIn(0, pages.size - 1), false)
        dots.setPosition(pager.currentItem.toFloat())
        slideWallpaper(pager.currentItem.toFloat())
    }

    /** A wallpaper wider than the screen slides as the home pages are swiped; one page, or a wallpaper the width of the screen, stays put. */
    private fun slideWallpaper(page: Float) {
        val n = pageIds().size
        runCatching {
            val wm = android.app.WallpaperManager.getInstance(this)
            wm.setWallpaperOffsetSteps(if (n > 1) 1f / (n - 1) else 1f, 1f)
            wm.setWallpaperOffsets(pager.windowToken ?: return, if (n > 1) (page / (n - 1)).coerceIn(0f, 1f) else 0.5f, 0.5f)
        }
    }

    private fun addPage() {
        if (pages.size >= MAX_PAGES) return
        val seq = prefs.getInt("pageSeq", 1) + 1
        prefs.edit().putInt("pageSeq", seq).putString("pages", (pageIds() + "p$seq").joinToString(",")).apply()
        buildPages(pages.size)
        pager.setCurrentItem(pages.size - 1, true)
    }

    private fun removeCurrentPage() {
        if (pages.size <= 1) return
        val page = currentPage()
        val doRemove = {
            val at = pager.currentItem
            page.clear()
            prefs.edit().putString("pages", (pageIds() - page.id).joinToString(",")).apply()
            buildPages((at - 1).coerceAtLeast(0))
        }
        if (page.itemCount == 0) doRemove() else overlays.listSheet(
            "Remove this page?",
            listOf(
                MenuItem(R.drawable.ic_menu_delete, "Remove page and its ${page.itemCount} item" + if (page.itemCount == 1) "" else "s", destructive = true) { doRemove() },
                MenuItem(R.drawable.ic_menu_remove, "Keep it") {},
            ),
        )
    }

    // ---- dock: four slots, each long-pressable to change

    private val defaultDock get() = listOf(
        Intent(Intent.ACTION_DIAL),
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MESSAGING),
        Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")),
        Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA),
    )

    private fun dockSaved(): MutableList<String> {
        val saved = prefs.getString("dock", "")!!.split(';').toMutableList()
        while (saved.size < DOCK_SLOTS) saved.add("")
        return saved
    }

    /** One dock slot: how to launch it, its icon and its component (null for an unresolved default). */
    private class DockEntry(val launch: Intent, val icon: Drawable, val component: ComponentName?)

    private fun dockEntry(slot: Int, saved: String): DockEntry? {
        val custom = ComponentName.unflattenFromString(saved)
        if (custom != null && runCatching { packageManager.getActivityInfo(custom, 0) }.isSuccess) {
            return DockEntry(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(custom), packageManager.getActivityIcon(custom), custom)
        }
        val i = defaultDock[slot]
        val ri = packageManager.resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY) ?: return null
        return DockEntry(i, ri.loadIcon(packageManager), ri.activityInfo?.let { ComponentName(it.packageName, it.name) })
    }

    private fun rebuildDock() {
        if (!::dockRow.isInitialized) return
        dockRow.removeAllViews()
        val saved = dockSaved()
        for (slot in 0 until DOCK_SLOTS) {
            if (saved[slot] == DOCK_EMPTY) { dockRow.addView(emptyDockSlot(slot), LinearLayout.LayoutParams(0, dp(56), 1f)); continue }
            val entry = dockEntry(slot, saved[slot]) ?: continue
            dockRow.addView(ImageView(this).apply {
                setImageDrawable(entry.icon)
                pressScale()
                setOnClickListener { v -> launchIntent(entry.launch, v) }
                setOnLongClickListener { v ->
                    val pkg = entry.component?.packageName
                    overlays.popupFor(v, listOf(listOf(
                        MenuItem(R.drawable.ic_menu_info, "App info") { if (pkg != null) showAppInfo(pkg) },
                        MenuItem(R.drawable.ic_menu_swap, "Change app") { chooseApp("Dock slot ${slot + 1}") { setDock(slot, it) } },
                        MenuItem(R.drawable.ic_menu_remove, "Remove from dock") { setDock(slot, DOCK_EMPTY) },
                    )))
                    true
                }
            }, LinearLayout.LayoutParams(0, dp(56), 1f))
        }
    }

    /** A removed dock icon leaves a quiet "+" you can tap to put an app there. */
    private fun emptyDockSlot(slot: Int): View = FrameLayout(this).apply {
        addView(ImageView(this@MainActivity).apply {
            setImageResource(R.drawable.ic_menu_add)
            setColorFilter(Color.WHITE)
            alpha = 0.8f
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = round(0x33FFFFFF, 28)
        }, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER))
        setOnClickListener { chooseApp("Add to dock") { setDock(slot, it) } }
        setOnLongClickListener { v ->
            overlays.popupFor(v, listOf(listOf(
                MenuItem(R.drawable.ic_menu_add, "Add app") { chooseApp("Add to dock") { setDock(slot, it) } },
                MenuItem(R.drawable.ic_menu_home, "Reset dock") { prefs.edit().remove("dock").apply(); rebuildDock() },
            )))
            true
        }
    }

    private fun setDock(slot: Int, component: String) {
        val saved = dockSaved()
        saved[slot] = component
        prefs.edit().putString("dock", saved.joinToString(";")).apply()
        rebuildDock()
    }

    /** A searchable sheet of every installed launcher app; [onPick] gets the chosen component string. */
    private fun chooseApp(title: String, onPick: (String) -> Unit) {
        val apps = Indexers.apps(this).sortedBy { it.title.lowercase() }.map { PickApp(it.title, it.key, runCatching { packageManager.getActivityIcon(ComponentName.unflattenFromString(it.key)!!) }.getOrNull()) }
        overlays.appPicker(title, apps) { onPick(it.key) }
    }

    private fun setClockShown(shown: Boolean) {
        prefs.edit().putBoolean("showClock", shown).apply()
        clockBlock.visibility = if (shown) View.VISIBLE else View.GONE
    }

    private fun openSettings() = startActivity(Intent(this, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    private fun showAppInfo(pkg: String) {
        runCatching { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    /** The menu for long-pressing empty home space, in the order Android's own launchers use. */
    private fun showHomeMenu(x: Float, y: Float) {
        val clockShown = clockBlock.visibility == View.VISIBLE
        val groups = mutableListOf(
            listOf(
                MenuItem(R.drawable.ic_menu_wallpaper, "Wallpaper & style") { changeWallpaper() },
                MenuItem(R.drawable.ic_menu_widgets, "Widgets") { currentPage().widgets.pick() },
                MenuItem(R.drawable.ic_menu_apps, "Apps list") { openDrawer() },
                MenuItem(R.drawable.ic_menu_clock, if (clockShown) "Remove clock" else "Add clock") { setClockShown(!clockShown) },
            ),
            buildList {
                if (pages.size < MAX_PAGES) add(MenuItem(R.drawable.ic_menu_page_add, "Add page") { addPage() })
                if (pages.size > 1) add(MenuItem(R.drawable.ic_menu_delete, "Remove this page", destructive = true) { removeCurrentPage() })
            },
            listOf(
                MenuItem(R.drawable.ic_menu_home, "Reset dock") { prefs.edit().remove("dock").apply(); rebuildDock() },
                MenuItem(R.drawable.ic_kind_setting, "Home settings") { openSettings() },
            ),
        )
        overlays.popup(x, y, y, groups.filter { it.isNotEmpty() })
    }

    private fun tryStart(block: () -> Unit) = try { block() } catch (e: Exception) { Toast.makeText(this, "That option isn't available on this phone", Toast.LENGTH_SHORT).show() }

    /** The wallpaper screen: what is set now, the ways to change it, and a crop step for photos. */
    private fun changeWallpaper() = wallpaperScreen.show()

    private fun setWallpaperFrom(uri: Uri, which: Int = android.app.WallpaperManager.FLAG_SYSTEM or android.app.WallpaperManager.FLAG_LOCK) {
        io.execute {
            val msg = runCatching {
                val dm = resources.displayMetrics
                val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                contentResolver.openInputStream(uri)!!.use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
                val sample = maxOf(1, minOf(bounds.outWidth / dm.widthPixels, bounds.outHeight / dm.heightPixels))
                val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
                val src = contentResolver.openInputStream(uri)!!.use { android.graphics.BitmapFactory.decodeStream(it, null, opts) }!!
                val targetRatio = dm.widthPixels.toFloat() / dm.heightPixels
                val cropW = if (src.width.toFloat() / src.height > targetRatio) (src.height * targetRatio).toInt() else src.width
                val cropH = if (src.width.toFloat() / src.height > targetRatio) src.height else (src.width / targetRatio).toInt()
                val cropped = android.graphics.Bitmap.createBitmap(src, (src.width - cropW) / 2, (src.height - cropH) / 2, cropW, cropH)
                val scaled = android.graphics.Bitmap.createScaledBitmap(cropped, dm.widthPixels, dm.heightPixels, true)
                android.app.WallpaperManager.getInstance(this).setBitmap(scaled, null, true, which)
                "Wallpaper updated"
            }.getOrElse { Log.w(TAG, "wallpaper failed: $it"); "Couldn't set that photo as the wallpaper" }
            ui.post { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
        }
    }

    // ---- drawer

    private fun buildDrawer(): DragLayout = DragLayout(this, up = false).apply {
        setBackgroundColor(pal.drawer)
        grid = GridView(this@MainActivity).apply {
            numColumns = 4
            adapter = apps
            verticalSpacing = dp(14)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            selector = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
            clipToPadding = false
            setPadding(dp(8), dp(8), dp(8), dp(24))
            setOnItemClickListener { _, view, position, _ -> open(apps.getItem(position), view) }
            setOnItemLongClickListener { _, view, position, _ -> appMenu(apps.getItem(position), view); true }
        }
        banner = TextView(this@MainActivity).apply {
            text = "Turn on message, calendar and file search  ›"
            setTextColor(pal.onPrimaryContainer)
            textSize = 13f
            gravity = Gravity.CENTER_VERTICAL
            background = round(pal.primaryContainer, 19)
            setPadding(dp(16), 0, dp(16), 0)
            visibility = View.GONE
            setOnClickListener { openAccessSettings() }
        }
        addView(grid, FrameLayout.LayoutParams(-1, -1))
        addView(banner, FrameLayout.LayoutParams(-1, dp(38), Gravity.TOP).apply { setMargins(dp(16), dp(4), dp(16), 0) })

        // Pull down to close, but only when the app grid is scrolled to the top.
        canStart = { !grid.canScrollVertically(-1) }
        onDrag = { dy -> drawer.translationY = dy.coerceIn(0f, screenH); applyDrawerProgress() }
        onRelease = { vy, dragged ->
            if (dragged) {
                if (vy > 800f || drawer.translationY > screenH * 0.3f) closeDrawer(clear = false) else openDrawer()
            }
        }
    }

    private fun appMenu(item: Item, anchor: View) {
        val cn = ComponentName.unflattenFromString(item.key) ?: return
        val pkg = cn.packageName
        overlays.popupFor(anchor, listOf(listOf(
            MenuItem(R.drawable.ic_menu_info, "App info") { showAppInfo(pkg) },
            MenuItem(R.drawable.ic_menu_add, "Add to home") { currentPage().addShortcut(item.key); closeDrawer(clear = true); Toast.makeText(this, "${item.title} added to home", Toast.LENGTH_SHORT).show() },
            MenuItem(R.drawable.ic_menu_apps, "Add to dock") {
                overlays.listSheet("Add ${item.title} to the dock", List(DOCK_SLOTS) { slot -> MenuItem(R.drawable.ic_menu_add, "Dock slot ${slot + 1}") { setDock(slot, item.key) } })
            },
            MenuItem(R.drawable.ic_menu_delete, "Uninstall", destructive = true) { runCatching { startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg"))) } },
        )))
    }

    /** Launches an app from an icon the user touched, growing the app's window out of that icon. */
    private fun launchIntent(intent: Intent, from: View) {
        val options = ActivityOptions.makeScaleUpAnimation(from, 0, 0, from.width, from.height).toBundle()
        runCatching { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), options) }
    }

    private fun launchComponent(cn: ComponentName, from: View) =
        launchIntent(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(cn), from)

    // ---------------------------------------------------------------- lifecycle

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        registerReceiver(packageReceiver, filter)
        widgetHost.startListening()
    }

    override fun onStop() {
        unregisterReceiver(packageReceiver)
        overlays.dismissAll()
        runCatching { widgetHost.stopListening() }
        voice.release()
        super.onStop()
    }

    /** Voice: fully on-device Whisper when its model is installed, otherwise Android's own recognizer. */
    private fun buildVoice(): Voice {
        voiceFile = models.whisper
        return if (voiceFile!!.exists()) {
            LocalVoice(this, voiceFile!!,
                onFinal = { text -> ui.post { input.setText(text); input.setSelection(text.length); submit() } },
                onPhase = { phase -> ui.post { setMicPhase(phase) } },
                onError = { code -> ui.post { onVoiceError(code) } })
        } else {
            VoiceInput(this,
                onPartial = { text -> input.setText(text); input.setSelection(text.length) },
                onFinal = { text -> input.setText(text); input.setSelection(text.length); submit() },
                onState = { on -> ui.post { setMicPhase(if (on) 1 else 0) } },
                onError = { code -> ui.post { onVoiceError(code) } })
        }
    }

    override fun onResume() {
        super.onResume()
        // The theme may have been changed in Settings: repaint everything with the new colours.
        if (builtThemeKey != themeKey) { recreate(); return }
        // The user may have chosen, downloaded or removed a voice model in Settings.
        if (voiceFile != models.whisper || (voice is VoiceInput && models.whisper.exists())) { voice.release(); voice = buildVoice() }
        // First run: the setup tour asks for permissions itself, with reasons.
        if (SetupActivity.needed(this)) startActivity(Intent(this, SetupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) else requestAccessOnce()
        refreshIndex(heavy = false)
        search()
        updateBanner()
        startEmbedding()
        CpuTuner.maybeRun(this)
    }

    /** Fingerprints new photos for "photos of a beach" style search; a no-op if the image models are not installed or a run is in progress. */
    private fun startEmbedding() {
        if (!Services.clip.available && !Services.ocr.available) return
        if (embedRunning.getAndSet(true)) { embedAgain.set(true); return } // the photos may have changed since that run began: go round again
        embedder.execute {
            try {
                do {
                    embedAgain.set(false)
                    PhotoEmbedder(this, index).run { false }
                    PhotoTextReader(this, index).run { false } // after the fingerprints: reading text is the slower job
                } while (embedAgain.get())
            } catch (e: Throwable) {
                Log.w(TAG, "photo background work stopped: $e")
            } finally {
                embedRunning.set(false)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Home pressed (from this screen or from another app): the chat is over, and the screen goes back to plain home with menus closed and the first page showing.
        if (chatOpen) endChat()
        resetUi()
        consumeDebugExtras(intent)
    }

    @Deprecated("Launcher: back never leaves home")
    override fun onBackPressed() {
        when {
            wallpaperScreen.isShowing -> wallpaperScreen.back()
            overlays.isShowing -> overlays.dismissAll()
            card.visibility == View.VISIBLE -> endChat()
            input.text.isNotEmpty() -> input.setText("")
            input.hasFocus() -> { hideKeyboard(); input.clearFocus() }
            drawerOpen -> closeDrawer(clear = true)
            pager.currentItem != 0 -> pager.setCurrentItem(0, true)
        }
    }

    @Deprecated("Widget picker/configure results")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQ_PHOTO) {
            if (resultCode == RESULT_OK) data?.data?.let { wallpaperScreen.startCrop(it) }
            return
        }
        if (!HomeWidgets.dispatch(requestCode, resultCode, data)) super.onActivityResult(requestCode, resultCode, data)
    }

    // ---------------------------------------------------------------- home <-> drawer

    /** Over the drawer or the results, the bars sit on a light panel in the light theme, so their icons must be dark there (and only there). */
    private fun updateBars(covered: Boolean) {
        val lightPanel = !ai.nanosearch.launcher.ui.isDark(this)
        window.insetsController?.setSystemBarsAppearance(
            if (covered && lightPanel) android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS else 0,
            android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
        )
    }

    /** As the drawer rises the home screen behind it eases back and fades, as in Launcher3. */
    private fun applyDrawerProgress() {
        val p = (1f - drawer.translationY / screenH).coerceIn(0f, 1f)
        homeContent.scaleX = 1f - 0.03f * p
        homeContent.scaleY = 1f - 0.03f * p
        homeContent.alpha = 1f - 0.85f * p
    }

    private fun animateDrawer(to: Float, durationMs: Long, end: () -> Unit) {
        ObjectAnimator.ofFloat(drawer, View.TRANSLATION_Y, drawer.translationY, to).apply {
            duration = durationMs
            interpolator = DecelerateInterpolator(1.7f)
            addUpdateListener { applyDrawerProgress() }
            addListener(object : android.animation.AnimatorListenerAdapter() { override fun onAnimationEnd(a: android.animation.Animator) = end() })
        }.start()
    }

    private fun openDrawer() {
        drawerOpen = true
        updateBars(true)
        drawer.visibility = View.VISIBLE
        animateDrawer(0f, 420) { if (drawerOpen) home.visibility = View.INVISIBLE } // nothing ghosts through the drawer
    }

    private fun closeDrawer(clear: Boolean) {
        drawerOpen = false
        updateBars(resultsPanel.visibility == View.VISIBLE)
        home.visibility = View.VISIBLE
        hideKeyboard()
        if (clear) input.setText("")
        animateDrawer(screenH, 300) { if (!drawerOpen) { drawer.visibility = View.INVISIBLE; applyDrawerProgress() } }
    }

    /** Back to a clean home screen: no query, no answer, drawer shut, keyboard away. */
    private fun resetUi() {
        overlays.dismissAll()
        wallpaperScreen.dismiss()
        if (::pager.isInitialized && pager.currentItem != 0) pager.setCurrentItem(0, true)
        input.setText("")
        input.clearFocus()
        hideAnswer()
        closeDrawer(clear = false)
    }

    private fun hideKeyboard() {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(input.windowToken, 0)
    }

    // ---------------------------------------------------------------- search

    private fun onQueryChanged() {
        action = "search"
        ui.removeCallbacks(parseRunnable)
        parseSeq++
        hideAnswer()
        val q = input.text.toString()
        setSearchActive(q.isNotBlank() || chatOpen)
        // Photo requests ("photos from Goa last September") are understood by rules and answered instantly; a model would only blur them.
        if (q.isNotBlank() && looksNatural(q) && parser.available && PhotoQuery.parse(q) == null && !isFollowUp(q)) ui.postDelayed(parseRunnable, PARSE_DEBOUNCE_MS)
        search()
    }

    /** While there is a query the results panel covers whatever is below the bar, on home or in the drawer. */
    private fun setSearchActive(active: Boolean) {
        if (active == searchActive) return
        searchActive = active
        searchLayer.layoutParams = searchLayer.layoutParams.apply { height = if (active) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT }
        resultsPanel.visibility = if (active) View.VISIBLE else View.GONE
        // The whole layer, including the status and navigation bar areas its padding leaves, gets the panel colour while results show.
        searchLayer.setBackgroundColor(if (active) pal.panel else Color.TRANSPARENT)
        updateBars(active || drawerOpen)
        home.visibility = if (active || drawerOpen) View.INVISIBLE else View.VISIBLE // nothing ghosts through the panel
        updateBanner()
    }

    /** Only requests that read like a sentence go to the model; plain names stay on the instant path. */
    private fun looksNatural(q: String): Boolean {
        val words = q.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        return words.size >= 3 || (words.size == 2 && words[0] in VERBS)
    }

    private fun looksLikeQuestion(q: String): Boolean {
        val t = q.trim().lowercase()
        return t.endsWith("?") || QUESTION_STARTS.any { t == it || t.startsWith("$it ") }
    }

    private val semanticRunnable = Runnable { runSearch(input.text.toString(), semantic = true) }

    private fun search() {
        val query = input.text.toString()
        ui.removeCallbacks(semanticRunnable)
        // Anything that may involve the image model ("photos of a dog", or a short plain word like "beach") waits for a pause in typing
        // rather than running on "photos of a d"; the instant keyword results appear straight away either way.
        val mayNeedModel = PhotoQuery.parse(query)?.content != null || (query.isNotBlank() && query.trim().split(Regex("\\s+")).size <= 3)
        runSearch(query, semantic = !mayNeedModel)
        if (mayNeedModel) ui.postDelayed(semanticRunnable, SEMANTIC_DEBOUNCE_MS)
    }

    private fun runSearch(query: String, semantic: Boolean) {
        val seq = ++searchSeq
        io.execute {
            val drawerApps = if (query.isBlank()) index.search("", limit = 500) else null
            val found = if (query.isBlank()) emptyList() else index.search(query, limit = 60, semantic = semantic)
            ui.post {
                if (seq != searchSeq) return@post
                if (drawerApps != null) { apps.set(drawerApps); results.set(emptyList()) } else results.set(withAsk(query, found))
            }
        }
    }

    /** An "Ask" row at the top of the results; pressing Enter does the same thing. */
    private fun withAsk(query: String, found: List<Item>): List<Item> =
        if (answerer.available && query.isNotBlank()) listOf(Item("ask", "Ask “${query.trim()}”", "ask")) + found else found

    private fun kindsFor(kind: String?): List<String>? = when (kind) {
        "app" -> listOf("app", "setting")
        "contact" -> listOf("contact", "message", "call")
        else -> null
    }

    private fun parseCurrent() {
        val query = input.text.toString()
        val seq = parseSeq
        llm.execute {
            if (seq != parseSeq) return@execute // typed on: a newer parse is coming
            val r = parser.parse(query)
            val p = r.parsed
            Log.i(TAG, "parse '$query' -> $p in ${r.totalMs}ms (cold load ${r.coldLoadMs}ms) ${r.stats}")
            Services.parserUsed()
            lastParse = query to p
            if (p == null || p.text.isBlank()) return@execute
            val found = index.search(p.text, kinds = kindsFor(p.kind))
            ui.post {
                if (seq != parseSeq) return@post
                action = p.action
                if (found.isNotEmpty()) {
                    searchSeq++ // supersede any in-flight plain search
                    results.set(withAsk(query, found))
                }
            }
        }
    }

    // ---------------------------------------------------------------- enter: act or answer

    /** In an open chat everything typed is a message, except commands ("call mom"), which act as usual. */
    private fun inChat(q: String) = chatOpen && answerer.available && q.trim().substringBefore(' ').lowercase() !in VERBS

    private fun isFollowUp(q: String) = inChat(q)

    private var lastSubmit = "" to 0L

    private fun submit() {
        val q = input.text.toString().trim()
        if (q.isEmpty()) return
        // One answer at a time: the next question waits until this one is finished (or stopped with the mic button).
        if (busy) { input.performHapticFeedback(HapticFeedbackConstants.REJECT); return }
        // One press of Enter can arrive twice (as an editor action and as a key event); the second would cancel the first answer.
        val now = SystemClock.elapsedRealtime()
        if (lastSubmit.first == q && now - lastSubmit.second < 600) return
        lastSubmit = q to now
        // "photos from Goa": answered instantly from the photo index, so there is nothing for a model to do.
        if (PhotoQuery.parse(q) != null && (results.firstResult()?.kind == "photo" || results.firstResult()?.kind == "phototext")) { hideKeyboard(); return }
        val natural = looksNatural(q)
        val question = looksLikeQuestion(q)
        val canParse = parser.available

        val chatTurn = inChat(q)

        // A plain name: no model needed, just open the best match. In a chat only an exact name does, so "and Pune" stays a message.
        if (!natural && !question) {
            val top = results.firstResult()
            if (top != null && (!chatTurn || top.title.equals(q, ignoreCase = true))) { open(top); return }
        }
        if (!answerer.available && !canParse) return

        val seq = ++askSeq
        busy = true
        stopSeq = -1
        updateBusyUi()
        if (chatTurn) {
            chat.add(ChatTurn(q))
            input.setText("") // like any chat: the message moves into the thread and the bar is ready for the next one
            showWorking(hideKb = false) // and the keyboard stays up
            input.requestFocus()
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).restartInput(input) // the keyboard keeps typing into the cleared bar
            llm.execute { try { answerWith(q, emptyList(), seq, followUp = true) } finally { finishAsk(seq) } }
            return
        }
        showWorking()
        llm.execute { try {
            var parsed: Parsed? = null
            if (!question && canParse) {
                parsed = lastParse?.takeIf { it.first == q }?.second ?: parser.parse(q).parsed
                Services.parserUsed()
            }
            val found = if (parsed != null && parsed.text.isNotBlank()) index.search(parsed.text, kinds = kindsFor(parsed.kind)) else retrieve(q)
            if (seq != askSeq || stopSeq == seq) return@execute
            val act = parsed?.action
            if (parsed != null && found.isNotEmpty() && (act == "open" || act == "call" || act == "message")) {
                ui.post { action = act!!; hideAnswer(); open(found.first()) }
                return@execute
            }
            if (parsed != null && act == "search" && found.isNotEmpty() && !q.endsWith("?")) {
                ui.post { action = "search"; hideAnswer(); results.set(withAsk(q, found)) }
                return@execute
            }
            if (!answerer.available) {
                ui.post { hideAnswer(); if (found.isNotEmpty()) results.set(withAsk(q, found)) }
                return@execute
            }
            answerWith(q, found, seq)
        } finally { finishAsk(seq) } }
    }

    /** Whole-query match first; otherwise pool matches for each meaningful word. */
    private fun retrieve(question: String): List<Item> {
        val whole = index.search(question, 8)
        if (whole.isNotEmpty()) return whole
        val pooled = LinkedHashMap<String, Item>()
        question.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 3 && it !in STOPWORDS }.forEach { w ->
            index.search(w, 4).forEach { pooled["${it.kind}:${it.key}"] = it }
        }
        return pooled.values.take(8)
    }

    private fun answerWith(question: String, found: List<Item>, seq: Int, followUp: Boolean = false) {
        if (stopSeq == seq) return
        if (!followUp) ui.post { if (seq == askSeq) beginChat(question) }
        Services.answererBusy()
        if (!MemoryPolicy.keepBoth) parser.unload() // memory budget: on phones without RAM to spare the two models are never resident together
        val shown = StringBuilder()
        val t0 = System.nanoTime()
        val out = answerer.answer(question, found, followUp) { piece ->
            shown.append(piece)
            ui.post { if (seq == askSeq) showAnswerText(Answerer.clean(shown.toString())) }
            seq == askSeq && stopSeq != seq
        }
        Log.i(TAG, "answer '$question' in ${(System.nanoTime() - t0) / 1_000_000}ms ${answerer.stats()}")
        Services.answererUsed()
        ui.post {
            if (seq != askSeq) return@post
            cardProgress.visibility = View.INVISIBLE
            if (out == null) showAnswerText("Sorry, I couldn't answer that just now. Please try again.")
        }
    }

    private fun showWorking(hideKb: Boolean = true) {
        val history = chatOpen && chat.size > 1
        cardPulse.visibility = if (history) View.GONE else View.VISIBLE
        if (history) pulse.cancel() else pulse.start()
        cardScroll.visibility = if (history) View.VISIBLE else View.GONE
        if (history) renderChat() else if (!chatOpen) thread.removeAllViews()
        cardProgress.visibility = View.VISIBLE
        card.visibility = View.VISIBLE
        divider.visibility = View.VISIBLE
        setSearchActive(true)
        if (hideKb) hideKeyboard()
    }

    private fun showAnswerText(text: String) {
        cardPulse.visibility = View.GONE
        pulse.cancel()
        cardScroll.visibility = View.VISIBLE
        if (chatOpen && chat.isNotEmpty()) { chat.last().a = text; renderChat() }
    }

    /** The first answer opens a chat: the question moves into the thread and the bar is cleared for the next message. */
    private fun beginChat(question: String) {
        chat.clear()
        chat.add(ChatTurn(question))
        chatOpen = true
        refreshHint()
        input.setText("")
    }

    /** The thread: each question in a dimmer bold line, its answer under it, newest at the bottom. Long-press any of them to copy. */
    private fun renderChat() {
        while (thread.childCount < chat.size * 2) {
            val question = thread.childCount % 2 == 0
            thread.addView(threadText(question), LinearLayout.LayoutParams(-1, -2).apply { if (question && thread.childCount > 0) topMargin = dp(18) })
        }
        while (thread.childCount > chat.size * 2) thread.removeViewAt(thread.childCount - 1)
        chat.forEachIndexed { i, t ->
            setIfChanged(thread.getChildAt(2 * i) as TextView, t.q)
            setIfChanged(thread.getChildAt(2 * i + 1) as TextView, t.a.ifEmpty { PENDING })
        }
        cardScroll.post { cardScroll.scrollTo(0, 1_000_000) } // clamps to the bottom (Int.MAX_VALUE overflows the clamp); fullScroll() would take focus from the bar
    }

    private fun setIfChanged(tv: TextView, text: String) { if (tv.text.toString() != text) tv.text = text }

    private fun threadText(question: Boolean) = TextView(this).apply {
        if (question) {
            setTextColor(pal.onVariant)
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
        } else {
            setTextColor(pal.onSurface)
            textSize = 17f
            setLineSpacing(0f, 1.2f)
        }
        setOnLongClickListener { v ->
            val text = (v as TextView).text.toString()
            if (text != PENDING) {
                v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                overlays.popupFor(v, listOf(listOf(MenuItem(R.drawable.ic_menu_copy, "Copy") { copyToClipboard(text) })))
            }
            true
        }
    }

    private fun copyToClipboard(text: String) {
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("NanoSearch", text))
        if (Build.VERSION.SDK_INT < 33) Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show() // newer Android confirms it itself
    }

    /** Hides the card unless a chat is open: other paths ask for this when they have something else to show. */
    private fun hideAnswer() {
        if (!chatOpen) closeCard()
    }

    private fun closeCard() {
        askSeq++
        busy = false
        updateBusyUi()
        pulse.cancel()
        card.visibility = View.GONE
        divider.visibility = View.GONE
    }

    /** The input hint follows what the bar is doing: listening, waiting for an answer, chatting, or plain search. */
    private fun refreshHint() {
        input.hint = when {
            micPhase == 1 -> "Listening…"
            busy -> "Answering…"
            chatOpen -> CHAT_HINT
            else -> "Search"
        }
    }

    /** The mic button is Stop while an answer is being written. */
    private fun updateBusyUi() {
        mic.setImageResource(if (busy) R.drawable.ic_stop else R.drawable.ic_mic)
        mic.contentDescription = if (busy) "Stop" else "Voice search"
        mic.alpha = if (busy && stopSeq == askSeq) 0.4f else 1f // dimmed once Stop has been pressed, until the answer winds down
        refreshHint()
    }

    private fun stopAnswer() {
        if (!busy || stopSeq == askSeq) return
        stopSeq = askSeq
        mic.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        updateBusyUi()
    }

    /** Runs when the work for question [seq] ends, however it ends. */
    private fun finishAsk(seq: Int) {
        ui.post {
            if (seq != askSeq) return@post // the card was closed meanwhile; that already cleared the state
            val stopped = stopSeq == seq
            busy = false
            updateBusyUi()
            if (stopped) tidyAfterStop()
        }
    }

    /** What is left on screen after Stop: a partial answer stays as it is; a turn that never got a word is taken back. */
    private fun tidyAfterStop() {
        cardProgress.visibility = View.INVISIBLE
        pulse.cancel()
        if (!chatOpen || chat.isEmpty()) { closeCard(); return }
        if (chat.last().a.isEmpty()) chat.removeAt(chat.size - 1)
        if (chat.isEmpty()) endChat() else renderChat()
    }

    /** The ✕ and back: the chat is over and the model forgets it. */
    private fun endChat() {
        chatOpen = false
        chat.clear()
        closeCard()
        refreshHint()
        setSearchActive(input.text.isNotBlank())
        llm.execute { answerer.endConversation() }
    }

    // ---------------------------------------------------------------- voice

    private fun toggleVoice() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
            return
        }
        if (!voice.listening && !voice.available) {
            Toast.makeText(this, "Voice search isn't available on this phone", Toast.LENGTH_SHORT).show()
            return
        }
        if (!voice.listening) hideAnswer()
        voice.toggle()
    }

    private fun onVoiceError(code: Int) {
        val msg = when (code) {
            android.speech.SpeechRecognizer.ERROR_NO_MATCH, android.speech.SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Didn't catch that, try again"
            android.speech.SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE, android.speech.SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ->
                "Voice search needs a speech language pack that isn't installed on this phone"
            android.speech.SpeechRecognizer.ERROR_NETWORK, android.speech.SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Voice search needs an internet connection here"
            else -> "Voice search isn't working right now"
        }
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    /** 0 = idle, 1 = listening (red, pulsing), 2 = transcribing (purple, pulsing). */
    private fun setMicPhase(phase: Int) {
        // The mic morphs from a circle to a rounded square while it is busy, and changes colour: listening, transcribing, idle.
        val (fill, ink) = when (phase) { 1 -> pal.errorContainer to pal.onErrorContainer; 2 -> pal.tertiaryContainer to pal.onTertiaryContainer; else -> pal.bar to pal.onVariant }
        val shape = round(fill, 28)
        mic.background = shape
        mic.imageTintList = android.content.res.ColorStateList.valueOf(ink)
        ValueAnimator.ofFloat(shape.cornerRadius, dp(if (phase == 0) 28 else 18).toFloat()).apply {
            duration = 420
            interpolator = android.view.animation.OvershootInterpolator(2.2f)
            addUpdateListener { shape.cornerRadius = it.animatedValue as Float }
        }.start()
        micPhase = phase
        refreshHint()
        if (phase != 0) micPulse.start() else { micPulse.cancel(); mic.alpha = 1f }
    }

    // ---------------------------------------------------------------- indexing and permissions

    private fun refreshIndex(heavy: Boolean) {
        indexer.execute {
            index.replaceKind("app", Indexers.apps(this))
            if (index.count("setting") == 0) index.replaceKind("setting", Indexers.settings())
            val now = System.currentTimeMillis()
            val force = prefs.getBoolean("reindex", false).also { if (it) prefs.edit().remove("reindex").apply() }
            if (heavy || force || now - lastHeavyRefresh > HEAVY_REFRESH_MS || accessSignature() != lastAccessSignature) {
                lastHeavyRefresh = now
                lastAccessSignature = accessSignature()
                // One failing collector must never take the app down or wipe what was indexed before.
                fun collect(kind: String, block: () -> List<Item>?) {
                    if (!AppSettings.sourceEnabled(this, kind)) { index.replaceKind(kind, emptyList()); return }
                    val items = runCatching(block).onFailure { Log.w(TAG, "indexing $kind failed: $it") }.getOrNull() ?: return
                    index.replaceKind(kind, items)
                    Log.i(TAG, "indexed ${items.size} $kind")
                }
                collect("contact") { Indexers.contacts(this) }
                collect("call") { Indexers.calls(this) }
                collect("event") { Indexers.events(this) }
                collect("message") { Indexers.messages(this) }
                collect("file") { Indexers.files(this) }
                // Text read from photos is versioned too: bump OCR_VERSION when the reader or how its text is stored improves.
                if (prefs.getInt("ocrVersion", 0) != OCR_VERSION) { index.clearOcr(); prefs.edit().putInt("ocrVersion", OCR_VERSION).apply() }
                // Places are cached per photo; when the place data or its rule changes, start from scratch.
                val cache = if (prefs.getInt("placesVersion", 0) == Gazetteer.VERSION) index.photoCache() else emptyMap()
                if (!AppSettings.sourceEnabled(this, "photo")) { index.replacePhotos(emptyList()); index.replaceKind("photo", emptyList()); index.replaceKind("phototext", emptyList()) }
                else runCatching { Indexers.photos(this, cache) }.onFailure { Log.w(TAG, "indexing photos failed: $it") }.getOrNull()?.let { rows ->
                    prefs.edit().putInt("placesVersion", Gazetteer.VERSION).apply()
                    index.replacePhotos(rows)
                    index.replaceKind("photo", Indexers.photoItems(rows))
                    Log.i(TAG, "indexed ${rows.size} photo")
                }
                if (AppSettings.sourceEnabled(this, "photo")) startEmbedding()
            }
            ui.post { search() }
        }
    }

    private fun accessSignature() = WANTED_PERMISSIONS.count { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED } * 10 +
        (if (Environment.isExternalStorageManager()) 1 else 0) +
        AppSettings.SOURCES.sumOf { if (AppSettings.sourceEnabled(this, it.kind)) it.kind.length * 1000 else 0 }

    /** Asks once for everything the personal search reads; the user can change any of it later in Settings. */
    private fun requestAccessOnce() {
        val missing = WANTED_PERMISSIONS.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        val askedForCurrent = prefs.getString("askedFor", "") == WANTED_PERMISSIONS.joinToString() // a newly added permission is asked once
        if (missing.isNotEmpty() && !askedForCurrent) {
            requestPermissions(missing.toTypedArray(), REQ_ACCESS)
        } else {
            askAllFilesOnce()
        }
    }

    private fun askAllFilesOnce() {
        if (Environment.isExternalStorageManager() || prefs.getBoolean("askedAllFiles", false)) return
        prefs.edit().putBoolean("askedAllFiles", true).apply()
        runCatching {
            startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
        }
    }

    private fun missingAccess() = WANTED_PERMISSIONS.any { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED } ||
        !Environment.isExternalStorageManager()

    private fun updateBanner() {
        banner.visibility = if (missingAccess()) View.VISIBLE else View.GONE
        (grid.layoutParams as FrameLayout.LayoutParams).topMargin = if (banner.visibility == View.VISIBLE) dp(48) else 0
        grid.requestLayout()
    }

    /** Runtime prompts stop appearing once dismissed, so send the user to the app's permission page. */
    private fun openAccessSettings() {
        val missing = WANTED_PERMISSIONS.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty() && shouldShowRequestPermissionRationale(missing.first())) {
            requestPermissions(missing.toTypedArray(), REQ_ACCESS)
        } else if (missing.isNotEmpty()) {
            runCatching { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
        } else {
            runCatching { startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))) }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode == REQ_MIC) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) toggleVoice()
            return
        }
        if (grantResults.isNotEmpty()) prefs.edit().putString("askedFor", WANTED_PERMISSIONS.joinToString()).apply()
        refreshIndex(heavy = true)
        askAllFilesOnce()
        updateBanner()
    }

    // ---------------------------------------------------------------- opening results

    private fun open(item: Item, from: View? = null) {
        val intent = when (item.kind) {
            "app" -> Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(ComponentName.unflattenFromString(item.key))
            "contact" -> contactIntent(item)
            "message" -> Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${item.key.substringBefore('|')}"))
            "call" -> Intent(Intent.ACTION_DIAL, Uri.parse("tel:${item.key.substringBefore('|')}"))
            "event" -> Intent(Intent.ACTION_VIEW, Uri.withAppendedPath(CalendarContract.Events.CONTENT_URI, item.key))
            "file" -> Intent(Intent.ACTION_VIEW)
                .setDataAndType(Uri.withAppendedPath(MediaStore.Files.getContentUri("external"), item.key.substringBefore('|')), item.key.substringAfter('|'))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            "photo", "phototext" -> Intent(Intent.ACTION_VIEW)
                .setDataAndType(android.content.ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, item.key.substringBefore('|').toLong()), item.key.substringAfter('|'))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            "setting" -> Intent(item.key)
            else -> return
        }
        try {
            val options = from?.let { ActivityOptions.makeScaleUpAnimation(it, 0, 0, it.width, it.height).toBundle() }
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), options)
            resetUi()
        } catch (e: Exception) {
            Log.w(TAG, "cannot open ${item.kind} ${item.title}: $e") // e.g. an app uninstalled since indexing
        }
    }

    /** Dial or compose, never place the call / send the message: the person still presses the button. */
    private fun contactIntent(item: Item): Intent {
        val view = Intent(Intent.ACTION_VIEW, Uri.parse(item.key))
        if (action != "call" && action != "message") return view
        val number = phoneFor(item.key) ?: return view
        return if (action == "call") Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number"))
        else Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$number"))
    }

    private fun phoneFor(lookupUri: String): String? {
        val contactUri = ContactsContract.Contacts.lookupContact(contentResolver, Uri.parse(lookupUri)) ?: return null
        val id = android.content.ContentUris.parseId(contactUri)
        contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
            "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?", arrayOf(id.toString()),
            "${ContactsContract.CommonDataKinds.Phone.IS_PRIMARY} DESC"
        )?.use { if (it.moveToFirst()) return it.getString(0) }
        return null
    }

    // ---------------------------------------------------------------- debug hooks

    /**
     * `am start -n ai.nanosearch.launcher/.MainActivity --es bench 1` runs every line of files/models/queries.txt
     * through the parser and writes files/bench.txt; `--es askbench 1` does the same with questions.txt and the
     * answer model, writing files/answers.txt.
     */
    private fun runBench() {
        val queries = File(models.dir, "queries.txt").takeIf { it.exists() }?.readLines() ?: return
        llm.execute {
            File(filesDir, "bench.txt").bufferedWriter().use { w ->
                queries.forEachIndexed { i, line ->
                    val r = parser.parse(line.substringBefore('|'))
                    val json = r.parsed?.let { """{"action":"${it.action}","kind":"${it.kind}","text":"${it.text}"}""" } ?: "null"
                    w.appendLine("$i|${r.totalMs}|$json|${r.stats}")
                    w.flush()
                }
            }
            Log.i(TAG, "bench done")
        }
    }

    private fun runAskBench() {
        val questions = File(models.dir, "questions.txt").takeIf { it.exists() }?.readLines()?.filter { it.isNotBlank() } ?: return
        llm.execute {
            parser.unload()
            File(filesDir, "answers.txt").bufferedWriter().use { w ->
                questions.forEachIndexed { i, q ->
                    val t0 = System.nanoTime()
                    val sb = StringBuilder()
                    answerer.answer(q, retrieve(q)) { sb.append(it); true }
                    w.appendLine("$i|${(System.nanoTime() - t0) / 1_000_000}|$q|${answerer.stats()}|${Answerer.clean(sb.toString()).replace('\n', ' ')}")
                    w.flush()
                }
            }
            Log.i(TAG, "askbench done")
        }
    }

    // ---------------------------------------------------------------- adapters

    private inner class ResultAdapter : BaseAdapter() {
        private var items: List<Item> = emptyList()
        private val icons = LruCache<String, Drawable>(128)

        fun set(new: List<Item>) {
            items = new
            notifyDataSetChanged()
        }

        fun firstResult(): Item? = items.firstOrNull { it.kind != "ask" }
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()

        private inner class Holder(val root: LinearLayout, val icon: ImageView, val glyph: TextView, val title: TextView, val sub: TextView) {
            var boundKey = ""
        }

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val h = (convertView?.tag as? Holder) ?: newHolder()
            val item = items[position]
            h.title.text = item.title
            h.sub.text = item.sub
            h.sub.visibility = if (item.sub.isEmpty()) View.GONE else View.VISIBLE
            h.boundKey = item.key
            h.icon.clipToOutline = item.kind == "photo" || item.kind == "phototext"
            h.icon.clearColorFilter(); h.icon.background = null; h.icon.setPadding(0, 0, 0, 0)
            if (item.kind == "app") {
                h.glyph.visibility = View.GONE
                h.icon.visibility = View.VISIBLE
                h.icon.scaleType = ImageView.ScaleType.FIT_CENTER
                h.icon.setImageDrawable(iconFor(item))
            } else if (item.kind == "photo" || item.kind == "phototext") {
                h.glyph.visibility = View.GONE
                h.icon.visibility = View.VISIBLE
                h.icon.scaleType = ImageView.ScaleType.CENTER_CROP
                val cached = thumbs.get(item.key)
                if (cached != null) h.icon.setImageBitmap(cached) else {
                    h.icon.setImageDrawable(round(pal.container, 12))
                    thumbExecutor.execute {
                        val bmp = runCatching {
                            val uri = android.content.ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, item.key.substringBefore('|').toLong())
                            contentResolver.loadThumbnail(uri, android.util.Size(dp(96), dp(96)), null)
                        }.getOrNull()
                        if (bmp != null) { thumbs.put(item.key, bmp); ui.post { if (h.boundKey == item.key) h.icon.setImageBitmap(bmp) } }
                    }
                }
            } else {
                // A tonal rounded square with the kind's Material glyph.
                h.glyph.visibility = View.GONE
                h.icon.visibility = View.VISIBLE
                h.icon.scaleType = ImageView.ScaleType.CENTER_INSIDE
                h.icon.setImageResource(KIND_ICONS[item.kind] ?: R.drawable.ic_kind_file)
                val (fill, ink) = when (item.kind) {
                    "contact", "call" -> pal.tertiaryContainer to pal.onTertiaryContainer
                    "message", "file" -> pal.secondaryContainer to pal.onSecondaryContainer
                    "ask" -> pal.primary to pal.onPrimary
                    else -> pal.primaryContainer to pal.onPrimaryContainer
                }
                h.icon.setColorFilter(ink)
                h.icon.setPadding(dp(10), dp(10), dp(10), dp(10))
                h.icon.background = round(fill, 14)
            }
            return h.root
        }

        private fun newHolder(): Holder {
            val icon = ImageView(this@MainActivity).apply {
                outlineProvider = object : android.view.ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: android.graphics.Outline) = outline.setRoundRect(0, 0, view.width, view.height, dp(10).toFloat())
                }
            }
            val glyph = TextView(this@MainActivity).apply { gravity = Gravity.CENTER; textSize = 18f; setTextColor(Color.WHITE) }
            val title = TextView(this@MainActivity).apply {
                textSize = 17f; setTextColor(pal.onSurface); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            }
            val sub = TextView(this@MainActivity).apply {
                textSize = 14f; setTextColor(pal.onVariant); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            }
            val texts = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), 0, 0, 0)
                addView(title)
                addView(sub)
            }
            val iconBox = FrameLayout(this@MainActivity).apply {
                addView(icon, FrameLayout.LayoutParams(-1, -1))
                addView(glyph, FrameLayout.LayoutParams(-1, -1))
            }
            val root = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(20), dp(8), dp(20), dp(8))
                addView(iconBox, LinearLayout.LayoutParams(dp(44), dp(44)))
                addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
            }
            return Holder(root, icon, glyph, title, sub).also { root.tag = it }
        }

        private fun iconFor(item: Item): Drawable? = icons.get(item.key) ?: runCatching {
            packageManager.getActivityIcon(ComponentName.unflattenFromString(item.key)!!)
        }.getOrNull()?.also { icons.put(item.key, it) }
    }

    private inner class AppGridAdapter : BaseAdapter() {
        private var items: List<Item> = emptyList()
        private val icons = LruCache<String, Drawable>(256)

        fun set(new: List<Item>) {
            items = new
            notifyDataSetChanged()
        }

        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val cell = (convertView as? LinearLayout) ?: LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(2), dp(4), dp(2), dp(4))
                // pressed: grow a little, as in Android's launchers (a state animator follows the list's own pressed state)
                stateListAnimator = android.animation.StateListAnimator().apply {
                    fun scaled(v: Float) = ObjectAnimator.ofPropertyValuesHolder(
                        null as Any?, android.animation.PropertyValuesHolder.ofFloat(View.SCALE_X, v), android.animation.PropertyValuesHolder.ofFloat(View.SCALE_Y, v),
                    ).setDuration(130)
                    addState(intArrayOf(android.R.attr.state_pressed), scaled(1.08f))
                    addState(intArrayOf(), scaled(1f))
                }
                addView(ImageView(context), LinearLayout.LayoutParams(dp(54), dp(54)))
                addView(TextView(context).apply {
                    textSize = 12f; setTextColor(pal.onSurface); maxLines = 1; gravity = Gravity.CENTER
                    ellipsize = TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
            }
            val item = items[position]
            (cell.getChildAt(1) as TextView).text = item.title
            (cell.getChildAt(0) as ImageView).setImageDrawable(
                icons.get(item.key) ?: runCatching { packageManager.getActivityIcon(ComponentName.unflattenFromString(item.key)!!) }
                    .getOrNull()?.also { icons.put(item.key, it) }
            )
            return cell
        }
    }

    /** Lets touches fall through to the home layer (long-press, swipe up) when there is nothing to scroll. */
    private class PassThroughScrollView(context: Context) : ScrollView(context) {
        private fun idle() = !canScrollVertically(1) && !canScrollVertically(-1)
        override fun onInterceptTouchEvent(e: android.view.MotionEvent) = if (idle()) false else super.onInterceptTouchEvent(e)
        override fun onTouchEvent(e: android.view.MotionEvent) = if (idle()) false else super.onTouchEvent(e)
    }

    /** A scroll view that stops growing at [maxPx], so a long answer scrolls instead of covering the screen. */
    private class MaxHeightScrollView(context: Context, private val maxPx: Int) : ScrollView(context) {
        override fun onMeasure(widthSpec: Int, heightSpec: Int) {
            super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(maxPx, MeasureSpec.AT_MOST))
        }
    }

    private companion object {
        const val TAG = "nanosearch"
        const val DOCK_EMPTY = "-"
        const val MAX_PAGES = 6
        const val PARSE_DEBOUNCE_MS = 450L
        const val CHAT_HINT = "Message…"
        const val PENDING = "…"
        const val SEMANTIC_DEBOUNCE_MS = 550L
        const val HEAVY_REFRESH_MS = 10 * 60_000L
        const val DOCK_SLOTS = 4
        const val BAR_SPACE_DP = 76 // room for the search bar above home and drawer content
        const val REQ_ACCESS = 2
        const val REQ_MIC = 3
        const val REQ_PHOTO = 43
        const val OCR_VERSION = 2 // 2: text is also stored without joining punctuation
        var lastHeavyRefresh = 0L
        var lastAccessSignature = -1
        val WANTED_PERMISSIONS = listOf(
            Manifest.permission.READ_CONTACTS, Manifest.permission.READ_SMS, Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_CALENDAR, Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.ACCESS_MEDIA_LOCATION,
        )
        val VERBS = setOf("open", "call", "text", "message", "find", "phone", "ring", "dial", "launch", "start", "search", "show", "sms")
        val QUESTION_STARTS = setOf(
            "what", "whats", "why", "how", "who", "when", "where", "which", "tell", "explain", "define", "describe",
            "write", "summarize", "summarise", "give", "is", "are", "can", "could", "does", "do", "did", "should", "will",
        )
        val STOPWORDS = setOf("the", "and", "for", "what", "which", "who", "how", "why", "when", "where", "are", "you", "can", "does", "any", "have", "with", "about", "app", "apps", "contact", "contacts", "tell", "me")
        val KIND_ICONS = mapOf(
            "contact" to R.drawable.ic_kind_contact, "message" to R.drawable.ic_kind_message, "call" to R.drawable.ic_kind_call,
            "event" to R.drawable.ic_kind_event, "file" to R.drawable.ic_kind_file, "setting" to R.drawable.ic_kind_setting, "ask" to R.drawable.ic_kind_ask,
        )
        val GLYPHS = mapOf("contact" to "👤", "message" to "💬", "call" to "📞", "event" to "📅", "file" to "📄", "setting" to "⚙", "ask" to "✦", "photo" to "🖼")
        val COLORS = mapOf(
            "contact" to 0xFF4C8DF6.toInt(), "message" to 0xFF34A853.toInt(), "call" to 0xFF7E57C2.toInt(),
            "event" to 0xFFEA4335.toInt(), "file" to 0xFFF29900.toInt(), "setting" to 0xFF607D8B.toInt(), "ask" to 0xFF8E63F0.toInt(),
        )
    }
}
