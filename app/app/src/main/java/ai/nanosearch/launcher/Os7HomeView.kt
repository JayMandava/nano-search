package ai.nanosearch.launcher

import android.content.Context
import android.content.ComponentName
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.BatteryManager
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.*

/** Original Android rendering of the OS 7 interaction pattern. No BlackBerry artwork or fonts. */
class Os7HomeView(
    context: Context,
    private val launch: (Item, View) -> Unit,
    private val appMenu: (Item, View) -> Unit,
    private val search: () -> Unit,
    private val notifications: () -> Unit,
    private val connections: () -> Unit,
    private val profiles: () -> Unit,
    private val menu: (View) -> Unit,
    private val standalone: Boolean = BuildConfig.OS7_STANDALONE,
) : LinearLayout(context) {
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private val white = Color.WHITE
    private var topInset=0
    private var statusHeight=dp(48)
    private lateinit var topChrome:FrameLayout
    private lateinit var statusArea:FrameLayout
    private lateinit var carrierLabel:TextView
    val searchTopOffset get()=if(standalone) statusHeight+dp(48) else dp(76)
    private fun rowHeight() = dp(64) + (36 * resources.displayMetrics.scaledDensity).toInt()
    private var loaded = false
    private var all = emptyList<Item>()
    private var shown = emptyList<Item>()
    private val tabs = listOf("All", "Favorites", "Media", "Downloads", "Frequent")
    private var panel = 0
    private fun neighbor(delta:Int):Int { val visible=Os7Panels.visiblePanels(context).map { it.ordinal }; val i=visible.indexOf(panel).coerceAtLeast(0); return visible[(i+delta+visible.size)%visible.size] }
    private var rows = if(standalone) 3 else 1
    val expanded get() = rows > 1
    val canReorder get() = panel != 4
    var folderId: String? = null; private set
    private var metadata = emptyList<Os7Panels.App>()
    private val icons = android.util.LruCache<String,android.graphics.drawable.Drawable>(128)
    fun clearIcons() { icons.evictAll(); adapter.notifyDataSetChanged() }
    private val battery = text(12f)
    private val alerts = text(14f)
    private val notificationRow = LinearLayout(context)
    private val tray = LinearLayout(context)
    private lateinit var footer:LinearLayout
    private val previous = text(14f)
    private val selected = text(16f)
    private val next = text(14f)
    private val empty = text(14f)
    private val grid = GridView(context)
    private val adapter = object : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(position: Int) = shown[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convert: View?, parent: ViewGroup): View {
            val cell = (convert as? LinearLayout) ?: LinearLayout(context).apply {
                orientation = VERTICAL; gravity = Gravity.CENTER
                setPadding(dp(3), dp(8), dp(3), dp(4))
                background = focusBackground()
                addView(ImageView(context), LayoutParams(dp(48), dp(48)))
                addView(text(12f).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END }, LayoutParams(-1, (36 * resources.displayMetrics.scaledDensity).toInt()))
            }
            val item = shown[position]
            (cell.getChildAt(0) as ImageView).setImageDrawable(if(item.kind == "folder") AppIcons.kindGem(context,"files",0) else icons.get(item.key) ?: runCatching { AppIcons.get(context, ComponentName.unflattenFromString(item.key)!!) }.getOrNull()?.also { icons.put(item.key,it) })
            (cell.getChildAt(1) as TextView).text = item.title
            cell.contentDescription = item.title
            return cell
        }
    }
    init {
        orientation = VERTICAL
        // Genuine device values, drawn in original chrome; Android's system shade remains a swipe away.
        val top = FrameLayout(context).apply {
            clipChildren=false
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xD0101419.toInt(),0x50101419))
            battery.gravity=Gravity.START or Gravity.CENTER_VERTICAL
            battery.setPadding(dp(8),0,0,0)
            battery.compoundDrawablePadding=dp(4)
            val carrier=runCatching { (context.getSystemService(Context.TELEPHONY_SERVICE) as android.telephony.TelephonyManager).networkOperatorName }.getOrDefault("")
            statusArea=FrameLayout(context).apply {
                addView(battery,FrameLayout.LayoutParams(dp(100),-1,Gravity.START))
                carrierLabel=text(12f).apply { text=carrier; maxLines=1; ellipsize=android.text.TextUtils.TruncateAt.END }
                addView(carrierLabel,FrameLayout.LayoutParams(dp(100),-1,Gravity.END).apply { rightMargin=dp(52) })
                addView(button("Connections",R.drawable.ic_os7_connections) { connections() },FrameLayout.LayoutParams(dp(48),dp(48),Gravity.END or Gravity.TOP))
            }
            if(standalone) {
                addView(statusArea,FrameLayout.LayoutParams(-1,dp(48),Gravity.TOP))
                addView(TextClock(context).apply { format12Hour="EEE d MMM";format24Hour="EEE d MMM";textSize=12f;setTextColor(white);gravity=Gravity.START or Gravity.CENTER_VERTICAL;setPadding(dp(8),0,0,0) },FrameLayout.LayoutParams(dp(105),dp(48),Gravity.BOTTOM))
                addView(TextClock(context).apply { format12Hour="h:mm";format24Hour="HH:mm";setSingleLine();setAutoSizeTextTypeUniformWithConfiguration(18,32,1,android.util.TypedValue.COMPLEX_UNIT_SP);setTextColor(white);gravity=Gravity.CENTER;typeface=Typeface.create("sans-serif-condensed",Typeface.NORMAL) },FrameLayout.LayoutParams(-1,dp(48),Gravity.BOTTOM).apply { leftMargin=dp(105);rightMargin=dp(105) })
            } else {
                addView(statusArea,FrameLayout.LayoutParams(-1,dp(28),Gravity.TOP))
                addView(TextClock(context).apply { format12Hour="EEE d MMM";format24Hour="EEE d MMM";textSize=12f;setTextColor(white);gravity=Gravity.START or Gravity.CENTER_VERTICAL },FrameLayout.LayoutParams(dp(105),dp(30),Gravity.BOTTOM))
                addView(TextClock(context).apply { format12Hour="h:mm";format24Hour="HH:mm";textSize=34f;setTextColor(white);gravity=Gravity.CENTER },FrameLayout.LayoutParams(-1,dp(48),Gravity.BOTTOM).apply { leftMargin=dp(105);rightMargin=dp(105) })
            }
        }
        topChrome=top
        addView(top, LayoutParams(-1,searchTopOffset))
        notificationRow.apply {
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0x8010161C.toInt())
            addView(button("Sound profile", android.R.drawable.ic_lock_silent_mode_off) { profiles() }, LayoutParams(dp(48),dp(48)))
            alerts.apply { setTextColor(0xFFFFD86B.toInt()); background = focusBackground(); setOnClickListener { notifications() }; contentDescription="Notifications"; isFocusable=true }
            addView(alerts, LayoutParams(0,dp(48),1f))
            addView(button("Search", R.drawable.ic_search) { search() }, LayoutParams(dp(48),dp(48)))
        }
        addView(notificationRow)
        addView(View(context), LayoutParams(-1,0,1f))
        tray.orientation=VERTICAL
        tray.background=GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,intArrayOf(0xA0101720.toInt(),0xE6080C12.toInt()))
        val navigation = LinearLayout(context).apply {
            gravity=Gravity.CENTER_VERTICAL
            background=GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,intArrayOf(0xB0434950.toInt(),0xC0202832.toInt(),0xC0121820.toInt()))
            addView(previous,LayoutParams(0,dp(48),1f)); addView(selected,LayoutParams(0,dp(48),1.5f)); addView(next,LayoutParams(0,dp(48),1f))
        }
        previous.apply { setOnClickListener { changePanel(-1) }; isFocusable=true }
        next.apply { setOnClickListener { changePanel(1) }; isFocusable=true }
        selected.apply { if(!standalone) setOnClickListener { setRows(if(rows<3) rows+1 else 1) }; isFocusable=true }
        var trayTouchY=0f
        if(!standalone) selected.setOnTouchListener { _,event ->
            when(event.actionMasked) {
                MotionEvent.ACTION_DOWN -> trayTouchY=event.rawY
                MotionEvent.ACTION_UP -> {
                    val dy=event.rawY-trayTouchY
                    if(kotlin.math.abs(dy)>dp(32)) { setRows(rows+if(dy<0) 1 else -1); return@setOnTouchListener true }
                }
            }; false
        }
        tray.addView(navigation)
        grid.apply {
            numColumns=columnCount(); stretchMode=GridView.STRETCH_COLUMN_WIDTH
            horizontalSpacing=dp(4); verticalSpacing=dp(4); setPadding(dp(8),0,dp(8),dp(4))
            this.adapter=this@Os7HomeView.adapter
            selector=android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
            setOnItemClickListener { _,v,p,_ -> val item=shown[p]; if(item.kind=="folder") { folderId=item.key; grid.setSelection(0); setRows(3) } else launch(item,v) }
            setOnItemLongClickListener { _,v,p,_ -> appMenu(shown[p],v); true }
            var x=0f; var y=0f
            setOnTouchListener { _,e ->
                when(e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { x=e.x; y=e.y }
                    MotionEvent.ACTION_UP -> {
                        val dx=e.x-x; val dy=e.y-y
                        if(kotlin.math.abs(dx)>dp(80) && kotlin.math.abs(dx)>kotlin.math.abs(dy)*1.5f) { changePanel(if(dx<0) 1 else -1); return@setOnTouchListener true }
                        if(!standalone && kotlin.math.abs(dy)>dp(48) && kotlin.math.abs(dy)>kotlin.math.abs(dx)*1.5f && ((dy<0 && rows<3) || !canScrollVertically(if(dy>0) -1 else 1))) { setRows((rows+if(dy<0) 1 else -1).coerceIn(0,3)); return@setOnTouchListener true }
                    }
                }; false
            }
        }
        tray.addView(grid,LayoutParams(-1,rowHeight()))
        empty.apply { setPadding(dp(20),dp(16),dp(20),dp(16)); visibility=GONE }
        tray.addView(empty,LayoutParams(-1,-2))
        addView(tray,LayoutParams(-1,-2))
        footer=LinearLayout(context).apply {
            gravity=Gravity.END; setBackgroundColor(0xEA0B1017.toInt())
            addView(text(13f).apply { text="Menu"; contentDescription="Home menu"; background=focusBackground(); isFocusable=true; setOnClickListener { menu(it) } },LayoutParams(dp(88),dp(48)))
        }
        addView(footer)
        setOnSizeChangedListener()
        refresh()
    }
    private fun setOnSizeChangedListener() { addOnLayoutChangeListener { _,l,t,r,b,ol,ot,or,ob -> if(r-l != or-ol || b-t != ob-ot) { grid.numColumns=columnCount(); resizeTray() } } }
    private fun columnCount() = ((resources.displayMetrics.widthPixels/resources.displayMetrics.density)/76).toInt().coerceIn(4,6)
    private fun text(size: Float) = TextView(context).apply { textSize=size; setTextColor(white); gravity=Gravity.CENTER; setShadowLayer(2f,0f,1f,Color.BLACK) }
    private fun button(label:String, icon:Int, action:()->Unit)=ImageView(context).apply {
        setImageResource(icon); setColorFilter(white); setPadding(dp(13),dp(13),dp(13),dp(13)); contentDescription=label; isFocusable=true; background=focusBackground(); setOnClickListener { action() }
    }
    private fun focusBackground()=StateListDrawable().apply {
        fun blue()=GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,intArrayOf(0xFF53A5DE.toInt(),0xFF154875.toInt())).apply { cornerRadius=dp(3).toFloat(); setStroke(dp(1),0xFF8DD1FF.toInt()) }
        addState(intArrayOf(android.R.attr.state_pressed),blue()); addState(intArrayOf(android.R.attr.state_focused),blue()); addState(intArrayOf(android.R.attr.state_selected),blue()); addState(intArrayOf(),android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
    }
    fun setApps(items:List<Item>, described:List<Os7Panels.App>) { loaded=true; all=items; metadata=described; refresh() }
    fun refresh() {
        battery.text="${(context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).coerceIn(0,100)}%"
        battery.setCompoundDrawablesWithIntrinsicBounds(BatteryOutline((context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)),null,null,null)
        val count=HubStore.count(setOf("message","call","app","event"))
        alerts.text=if(count>0) "✉  $count" else ""
        alerts.contentDescription=if(count>0) "$count notifications" else "Open notifications"
        if (Os7Panels.Panel.entries[panel] !in Os7Panels.visiblePanels(context)) panel=0
        previous.text="‹ ${tabs[neighbor(-1)]}"
        selected.text=tabs[panel]+if(standalone) "" else if(expanded) "  ▾" else "  ▴"
        selected.contentDescription=if(standalone) "${tabs[panel]} panel, three rows" else "${tabs[panel]} panel, $rows rows. Change application tray height"
        next.text="${tabs[neighbor(1)]} ›"
        val ordered=Os7Panels.select(Os7Panels.Panel.entries[panel],metadata,AppUsage.favourites(context),AppUsage.counts(context),Os7Panels.order(context))
        val indexed=all.associateBy { it.key }
        val folders=Os7Panels.folders(context)
        val folder=folders.firstOrNull { it.id==folderId }
        if(folderId!=null && folder==null) folderId=null
        shown=if(folder!=null) {
            selected.text=folder.name
            selected.contentDescription=if(standalone) "${folder.name} folder, three rows" else "${folder.name} folder, $rows rows. Change application tray height"
            ordered.filter { it.component in folder.components }.mapNotNull { indexed[it.component] }
        } else {
            val items=ordered.mapNotNull { indexed[it.component] }
            if(panel==0) {
                val members=folders.flatMap { it.components }.toSet()
                folders.map { Item("folder",it.name,it.id) }+items.filterNot { it.key in members }
            } else items
        }
        if(panel!=4) {
            val positions=Os7Panels.order(context).withIndex().associate { it.value to it.index }
            shown=shown.sortedWith(compareBy<Item> { positions[it.key] ?: Int.MAX_VALUE }.thenBy { if(it.kind=="folder") 0 else 1 }.thenBy { it.title.lowercase(java.util.Locale.ROOT) })
        }
        empty.text=if(folder!=null) "This folder is empty. Press and hold an app to move it here." else when(panel) {1->"Press and hold an app to add a favorite.";2->"No media apps found.";3->"No downloaded apps found.";4->"Apps you open from Nano appear here.";else->if(loaded) "No visible apps. Manage hidden apps in Options." else "Loading applications…"}
        empty.visibility=if(shown.isEmpty()) VISIBLE else GONE
        grid.visibility=if(shown.isEmpty() || rows==0) GONE else VISIBLE
        if(rows==0) empty.visibility=GONE
        adapter.notifyDataSetChanged()
    }
    private inner class BatteryOutline(private val level:Int) : android.graphics.drawable.Drawable() {
        private val paint=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        override fun getIntrinsicWidth()=dp(24)
        override fun getIntrinsicHeight()=dp(12)
        override fun draw(canvas:android.graphics.Canvas) {
            val b=android.graphics.RectF(bounds); val line=dp(1).toFloat().coerceAtLeast(1f)
            b.inset(line,line); b.right-=dp(3)
            paint.color=Color.WHITE; paint.style=android.graphics.Paint.Style.STROKE; paint.strokeWidth=line
            canvas.drawRoundRect(b,line,line,paint)
            paint.style=android.graphics.Paint.Style.FILL
            canvas.drawRect(b.right,b.centerY()-dp(2),b.right+dp(3),b.centerY()+dp(2),paint)
            b.inset(dp(2).toFloat(),dp(2).toFloat()); b.right=b.left+b.width()*level.coerceIn(0,100)/100f
            canvas.drawRect(b,paint)
        }
        override fun setAlpha(alpha:Int) { paint.alpha=alpha }
        override fun setColorFilter(filter:android.graphics.ColorFilter?) { paint.colorFilter=filter }
        @Deprecated("Drawable opacity") override fun getOpacity()=android.graphics.PixelFormat.TRANSLUCENT
    }
    private val batteryChanged = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: android.content.Intent) {
            val level=intent.getIntExtra(BatteryManager.EXTRA_LEVEL,-1)
            val scale=intent.getIntExtra(BatteryManager.EXTRA_SCALE,100)
            battery.text=if(level>=0 && scale>0) "${level*100/scale}%" else "—"
            battery.contentDescription="Battery ${battery.text}"
            battery.setCompoundDrawablesWithIntrinsicBounds(BatteryOutline(if(scale>0) level*100/scale else 0),null,null,null)
        }
    }
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        context.registerReceiver(batteryChanged,android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED),Context.RECEIVER_EXPORTED)
    }
    override fun onDetachedFromWindow() {
        context.unregisterReceiver(batteryChanged)
        super.onDetachedFromWindow()
    }
    private fun changePanel(delta:Int) { folderId=null; panel=neighbor(delta); grid.setSelection(0); refresh() }
    fun setExpanded(value:Boolean) = setRows(if(value) 3 else 1)
    private fun setRows(value:Int) { rows=if(standalone) 3 else value.coerceIn(0,3); resizeTray(); refresh() }
    private fun resizeTray() { grid.layoutParams=grid.layoutParams.apply { height=minOf(rows*rowHeight(), heightAvailable()).coerceAtLeast(dp(48)) } }
    private fun heightAvailable()=(height-paddingTop-paddingBottom-searchTopOffset-dp(144)).coerceAtLeast(dp(96))
    fun applyTopInset(inset:Int,cutouts:List<android.graphics.Rect> = emptyList()) {
        if(!standalone) return
        topInset=inset
        val screenWidth=resources.displayMetrics.widthPixels
        // Center camera: use the left/right status regions. Corner cameras: keep controls below it.
        val corner=cutouts.any { it.left < dp(100) || it.right > screenWidth-dp(52) }
        statusHeight=if(corner) inset+dp(48) else maxOf(inset,dp(48))
        statusArea.setPadding(0,if(corner) inset else 0,0,0)
        val cutoutRight=if(corner) 0 else cutouts.maxOfOrNull { it.right } ?: 0
        val available=(screenWidth-cutoutRight-dp(60)).coerceAtLeast(0)
        carrierLabel.layoutParams=carrierLabel.layoutParams.apply { width=minOf(dp(100),available) }
        statusArea.layoutParams=statusArea.layoutParams.apply { height=statusHeight }
        topChrome.layoutParams=topChrome.layoutParams.apply { height=searchTopOffset }
        resizeTray()
    }
    fun showSearch(show:Boolean) {
        notificationRow.visibility=if(show) INVISIBLE else VISIBLE
        tray.visibility=if(show && standalone) INVISIBLE else VISIBLE
        footer.visibility=if(show && standalone) INVISIBLE else VISIBLE
    }
    fun reset() { showSearch(false); grid.setSelection(0); panel=0; folderId=null; setRows(1) }
    fun back():Boolean { if(folderId!=null) { folderId=null; grid.setSelection(0); refresh(); return true }; if(rows>1 && !standalone) { setRows(1); return true }; return false }
    fun move(item:Item, offset:Int) {
        val keys=shown.map { it.key }.toMutableList()
        val at=keys.indexOf(item.key); val target=(at+offset).coerceIn(0,(keys.size-1).coerceAtLeast(0))
        if(at>=0 && at!=target) { java.util.Collections.swap(keys,at,target); Os7Panels.saveOrder(context,keys); refresh() }
    }
}
