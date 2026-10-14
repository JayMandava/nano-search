package ai.nanosearch.launcher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.util.Xml
import org.xmlpull.v1.XmlPullParser

/**
 * Installed icon packs. An icon pack is an ordinary app that carries a mapping file (`appfilter.xml`, in its assets or its xml resources) from an app's
 * component to one of its own pictures, and may add a back, a mask and a front for the apps it does not list. Launchers find packs by a handful of
 * agreed intent actions; this reads the same format, so a pack made for any of them works here.
 *
 * The pack is the user's choice (Settings, Appearance). With one selected, an app it lists gets the pack's picture exactly as drawn; an app it does not
 * list is wrapped in the pack's back, mask and front if it has them, and otherwise gets this launcher's own icon as before. Choosing none changes nothing.
 */
object IconPacks {
    class Pack(val pkg: String, val label: String)

    /** The intent actions packs declare themselves with: the ones most launchers agree on. */
    private val ACTIONS = listOf(
        "org.adw.launcher.THEMES", "com.novalauncher.THEME", "com.anddoes.launcher.THEME", "com.dlto.atom.launcher.THEME",
        "com.gau.go.launcherex.theme", "org.adw.launcher.icons.ACTION_PICK_ICON", "com.fede.launcher.THEME_ICONPACK", "ch.deletescape.lawnchair.ICONPACK",
    )

    /** The packs installed now, by name. */
    fun installed(context: Context): List<Pack> {
        val pm = context.packageManager
        val seen = LinkedHashMap<String, Pack>()
        for (action in ACTIONS) {
            val found = runCatching { pm.queryIntentActivities(Intent(action), 0) }.getOrDefault(emptyList())
            for (ri in found) {
                val pkg = ri.activityInfo?.packageName ?: continue
                if (pkg == context.packageName || pkg in seen) continue
                seen[pkg] = Pack(pkg, runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg))
            }
        }
        return seen.values.sortedBy { it.label.lowercase() }
    }

    /** A pack's mapping, read once. */
    private class Loaded(
        val pkg: String, val res: Resources,
        val byComponent: Map<String, String>, val byPackage: Map<String, String>, val calendars: Map<String, String>,
        val backs: List<Bitmap>, val mask: Bitmap?, val front: Bitmap?, val scale: Float,
    )

    @Volatile private var loaded: Loaded? = null
    @Volatile private var loadedFor: String? = null

    /** The selected pack's package name, or "" when the launcher's own icons are in use (also part of the icon cache key). */
    fun active(context: Context): String = AppSettings.iconPack(context)

    /** Forget what was read, so a pack that was updated or removed is read afresh. */
    fun reset() { synchronized(this) { loaded = null; loadedFor = null } }

    private fun load(context: Context): Loaded? {
        val want = AppSettings.iconPack(context)
        if (want.isEmpty()) return null
        if (loadedFor == want) return loaded
        synchronized(this) {
            if (loadedFor == want) return loaded
            val l = runCatching { read(context, want) }.getOrNull()
            loaded = l; loadedFor = want
            return l
        }
    }

    private fun read(context: Context, pkg: String): Loaded? {
        val res = context.packageManager.getResourcesForApplication(pkg)
        val parser: XmlPullParser = run {
            val id = res.getIdentifier("appfilter", "xml", pkg)
            if (id != 0) res.getXml(id)
            else runCatching { Xml.newPullParser().apply { setInput(res.assets.open("appfilter.xml"), "UTF-8") } }.getOrNull()
        } ?: return null
        val component = Regex("""ComponentInfo\{([^/}]+)/([^}]*)\}""")
        val byComponent = HashMap<String, String>(); val byPackage = HashMap<String, String>(); val calendars = HashMap<String, String>()
        val backNames = ArrayList<String>(); var maskName: String? = null; var frontName: String? = null; var scale = 1f
        fun key(raw: String?): Pair<String, String>? {
            val m = component.find(raw ?: return null) ?: return null
            val p = m.groupValues[1]; var a = m.groupValues[2]
            if (a.startsWith(".")) a = p + a
            return p to a
        }
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG) {
                fun attr(n: String) = (0 until parser.attributeCount).firstOrNull { parser.getAttributeName(it) == n }?.let { parser.getAttributeValue(it) }
                when (parser.name) {
                    "item" -> { val k = key(attr("component")); val d = attr("drawable")
                        if (k != null && !d.isNullOrEmpty()) { byComponent["${k.first}/${k.second}"] = d; byPackage.putIfAbsent(k.first, d) } }
                    "calendar" -> { val k = key(attr("component")); val pre = attr("prefix"); if (k != null && pre != null) calendars["${k.first}/${k.second}"] = pre }
                    "iconback" -> for (i in 0 until parser.attributeCount) if (parser.getAttributeName(i).startsWith("img")) backNames += parser.getAttributeValue(i)
                    "iconmask" -> maskName = attr("img1")
                    "iconupon" -> frontName = attr("img1")
                    "scale" -> scale = attr("factor")?.toFloatOrNull()?.coerceIn(0.3f, 1.2f) ?: 1f
                }
            }
            parser.next()
        }
        fun bmp(name: String?): Bitmap? {
            if (name.isNullOrEmpty()) return null
            val id = res.getIdentifier(name, "drawable", pkg).takeIf { it != 0 } ?: return null
            return runCatching { draw(res.getDrawable(id, null)) }.getOrNull()
        }
        return Loaded(pkg, res, byComponent, byPackage, calendars, backNames.mapNotNull { bmp(it) }, bmp(maskName), bmp(frontName), scale)
    }

    private fun draw(d: Drawable, size: Int = AppIcons.SIZE): Bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also {
        d.setBounds(0, 0, size, size); d.draw(Canvas(it))
    }

    /**
     * The icon the selected pack gives [cn], or null when no pack is selected or it has nothing for this app and no way to wrap one (the launcher's own
     * icon is then used). [own] is the app's own picture, [day] today's date for packs that draw a calendar page.
     */
    fun themed(context: Context, cn: ComponentName, own: Drawable, day: Int): Bitmap? {
        val l = load(context) ?: return null
        val full = cn.packageName + "/" + cn.className
        // a calendar page that shows the date: the pack names a prefix and a picture for each day
        l.calendars[full]?.let { prefix ->
            val id = l.res.getIdentifier(prefix + day, "drawable", l.pkg)
            if (id != 0) runCatching { return draw(l.res.getDrawable(id, null)) }
        }
        // listed exactly, or listed under another activity of the same app (an activity that was renamed since the pack was made)
        val name = l.byComponent[full] ?: l.byPackage[cn.packageName]
        if (name != null) {
            val id = l.res.getIdentifier(name, "drawable", l.pkg)
            if (id != 0) runCatching { return draw(l.res.getDrawable(id, null)) }
        }
        // not listed: the pack's own way of dressing the rest
        if (l.backs.isEmpty() && l.mask == null && l.front == null) return null
        return wrap(l, cn, own)
    }

    private fun wrap(l: Loaded, cn: ComponentName, own: Drawable): Bitmap {
        val s = AppIcons.SIZE
        val out = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        if (l.backs.isNotEmpty()) c.drawBitmap(l.backs[Math.floorMod(cn.packageName.hashCode(), l.backs.size)], null, RectF(0f, 0f, s.toFloat(), s.toFloat()), p)
        // the app's picture, scaled down into the middle and cut by the mask
        val layer = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val inset = s * (1f - l.scale) / 2f
        own.setBounds(inset.toInt(), inset.toInt(), (s - inset).toInt(), (s - inset).toInt()); own.draw(Canvas(layer))
        l.mask?.let { m -> Canvas(layer).drawBitmap(m, null, RectF(0f, 0f, s.toFloat(), s.toFloat()), Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) }) }
        c.drawBitmap(layer, 0f, 0f, p)
        l.front?.let { c.drawBitmap(it, null, RectF(0f, 0f, s.toFloat(), s.toFloat()), p) }
        return out
    }


}
