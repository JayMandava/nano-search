package ai.nanosearch.launcher

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The notifications the Hub and the icon dots show. They are read from the system's notification listener and kept only in memory: nothing is
 * written to disk and nothing leaves the phone. Whatever is on the shade when the listener connects is picked up again, so a restart loses nothing.
 */
object HubStore {
    class Note(
        val key: String, val pkg: String, val title: String, val body: String, val whenMs: Long, val kind: String,
        val contentIntent: PendingIntent?, val reply: Notification.Action?, val clearable: Boolean,
    )

    private val notes = LinkedHashMap<String, Note>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    @Volatile internal var service: NotificationHub? = null

    fun snapshot(): List<Note> = synchronized(notes) { notes.values.sortedByDescending { it.whenMs } }
    fun countFor(pkg: String): Int = synchronized(notes) { notes.values.count { it.pkg == pkg } }
    fun count(kinds: Set<String>): Int = synchronized(notes) { notes.values.count { it.kind in kinds } }

    fun listen(l: () -> Unit) { listeners.add(l) }
    fun unlisten(l: () -> Unit) { listeners.remove(l) }
    private fun changed() { main.post { listeners.forEach { it() } } }

    /** True when the user has allowed Nano Search to read notifications (Settings > Notification access). */
    fun allowed(context: Context): Boolean =
        context.getSystemService(NotificationManager::class.java).isNotificationListenerAccessGranted(ComponentName(context, NotificationHub::class.java))

    fun dismiss(key: String) { service?.cancelNotification(key) }

    // Texts, missed calls and events come from the phone's own databases, so "dismissing" one only means the Hub stops listing it; the Messages app and the call log are untouched.
    private fun prefs(c: Context) = c.getSharedPreferences("nano", Context.MODE_PRIVATE)

    // Pinned items stay at the top. Snoozed items from the phone's databases are hidden until a time; a snoozed notification is handed back to Android, which posts it again then.
    fun pinned(c: Context): Set<String> = prefs(c).getString("hub_pinned", "")!!.split('\u0001').filter { it.isNotBlank() }.toSet()
    fun togglePin(c: Context, id: String) {
        val now = pinned(c)
        prefs(c).edit().putString("hub_pinned", (if (id in now) now - id else now + id).toList().takeLast(100).joinToString("\u0001")).apply()
    }

    private fun snoozeMap(c: Context): Map<String, Long> =
        prefs(c).getString("hub_snoozed", "")!!.split('\u0001').filter { it.contains('@') }.associate { it.substringBeforeLast('@') to (it.substringAfterLast('@').toLongOrNull() ?: 0L) }
    fun snoozedIds(c: Context): Set<String> { val now = System.currentTimeMillis(); return snoozeMap(c).filterValues { it > now }.keys }
    fun snoozeItem(c: Context, id: String, untilMs: Long) {
        val live = snoozeMap(c).filterValues { it > System.currentTimeMillis() } + (id to untilMs)
        prefs(c).edit().putString("hub_snoozed", live.entries.toList().takeLast(200).joinToString("\u0001") { "${it.key}@${it.value}" }).apply()
    }
    fun snoozeNotification(key: String, ms: Long) { service?.snoozeNotification(key, ms) }

    /** Apps the user chose to leave out of the Hub (their notifications still reach the shade). */
    fun mutedApps(c: Context): Set<String> = prefs(c).getString("hub_muted", "")!!.split('\u0001').filter { it.isNotBlank() }.toSet()
    fun toggleMuted(c: Context, pkg: String) {
        val now = mutedApps(c)
        prefs(c).edit().putString("hub_muted", (if (pkg in now) now - pkg else now + pkg).joinToString("\u0001")).apply()
    }

    fun dismissedIds(c: Context): Set<String> = prefs(c).getString("hub_dismissed", "")!!.split('\u0001').filter { it.isNotBlank() }.toSet()
    fun markDismissed(c: Context, ids: Collection<String>) {
        val all = (dismissedIds(c) + ids).toList().takeLast(600) // the oldest forgotten first, so the list never grows without end
        prefs(c).edit().putString("hub_dismissed", all.joinToString("\u0001")).apply()
    }
    fun dismissAll() { service?.cancelAllNotifications() }

    internal fun reset(context: Context, all: Array<StatusBarNotification>?) {
        synchronized(notes) { notes.clear(); all?.forEach { build(context, it)?.let { n -> notes[n.key] = n } } }
        changed()
    }

    internal fun post(context: Context, sbn: StatusBarNotification) {
        val n = build(context, sbn)
        synchronized(notes) { if (n != null) notes[n.key] = n else notes.remove(sbn.key) }
        changed()
    }

    /** Debug hook: pretend [pkg] posted a notification, to try the dots and the Hub without that app. */
    fun debugInject(pkg: String, title: String, body: String) {
        val key = "debug|$pkg|$title"
        synchronized(notes) { notes[key] = Note(key, pkg, title, body, System.currentTimeMillis(), "message", null, null, false) }
        changed()
    }

    internal fun remove(key: String) { synchronized(notes) { notes.remove(key) }; changed() }

    private fun build(context: Context, sbn: StatusBarNotification): Note? {
        val n = sbn.notification
        if (sbn.packageName == context.packageName) return null
        // Not things to read: running services, media players, the summary line that groups others.
        if (n.flags and (Notification.FLAG_ONGOING_EVENT or Notification.FLAG_FOREGROUND_SERVICE or Notification.FLAG_GROUP_SUMMARY) != 0) return null
        if (n.category == Notification.CATEGORY_TRANSPORT || n.category == Notification.CATEGORY_PROGRESS || n.category == Notification.CATEGORY_SERVICE || n.category == Notification.CATEGORY_SYSTEM) return null
        val x = n.extras
        var title = x.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        var body = (x.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: x.getCharSequence(Notification.EXTRA_TEXT) ?: x.getCharSequence(Notification.EXTRA_SUB_TEXT))?.toString().orEmpty()
        // A chat notification carries the whole conversation: show the newest line.
        @Suppress("DEPRECATION")
        (x.getParcelableArray(Notification.EXTRA_MESSAGES))?.lastOrNull()?.let { m ->
            val b = m as? android.os.Bundle
            val text = b?.getCharSequence("text")?.toString()
            if (!text.isNullOrBlank()) { body = text; b.getCharSequence("sender")?.toString()?.takeIf { it.isNotBlank() && title.isBlank() }?.let { title = it } }
        }
        if (title.isBlank() && body.isBlank()) return null
        val smsApp = Telephony.Sms.getDefaultSmsPackage(context)
        val template = x.getString(Notification.EXTRA_TEMPLATE).orEmpty()
        val kind = when {
            n.category == Notification.CATEGORY_MESSAGE || n.category == Notification.CATEGORY_EMAIL || template.endsWith("MessagingStyle") || sbn.packageName == smsApp -> "message"
            n.category == Notification.CATEGORY_CALL || n.category == "missed_call" -> "call"
            n.category == Notification.CATEGORY_EVENT || n.category == Notification.CATEGORY_REMINDER || n.category == Notification.CATEGORY_ALARM -> "event"
            else -> "app"
        }
        val reply = n.actions?.firstOrNull { a -> a.remoteInputs?.any { it.allowFreeFormInput } == true }
        return Note(sbn.key, sbn.packageName, title, body, sbn.postTime, kind, n.contentIntent, reply, sbn.isClearable)
    }
}

/** Android calls this when a notification appears or goes; it only feeds [HubStore]. Enabled by the user in Settings > Notification access. */
class NotificationHub : NotificationListenerService() {
    override fun onListenerConnected() { HubStore.service = this; HubStore.reset(this, runCatching { activeNotifications }.getOrNull()) }
    override fun onListenerDisconnected() { if (HubStore.service === this) HubStore.service = null }
    override fun onNotificationPosted(sbn: StatusBarNotification) = HubStore.post(this, sbn)
    override fun onNotificationRemoved(sbn: StatusBarNotification) = HubStore.remove(sbn.key)
}
