package ai.nanosearch.launcher

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CallLog
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.Telephony

/** What the Hub lists: live notifications, plus recent texts, missed calls and the next events when those permissions are held. */
object HubData {
    class Item(
        val id: String, val kind: String, val pkg: String?, val title: String, val body: String, val whenMs: Long, val unread: Boolean,
        val note: HubStore.Note?, val open: Intent?,
    )

    private const val DAYS_BACK = 14L
    private fun has(c: Context, p: String) = c.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    fun load(context: Context): List<Item> {
        val now = System.currentTimeMillis()
        val out = ArrayList<Item>()
        val notes = HubStore.snapshot()
        for (n in notes) out.add(Item("n:${n.key}", n.kind, n.pkg, n.title, n.body, n.whenMs, true, n, null))

        if (AppSettings.sourceEnabled(context, "message") && has(context, Manifest.permission.READ_SMS)) runCatching {
            val seen = notes.filter { it.kind == "message" }.map { it.body.take(24) }.toSet()
            context.contentResolver.query(
                Telephony.Sms.Inbox.CONTENT_URI, arrayOf(Telephony.Sms._ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.READ),
                "${Telephony.Sms.DATE} > ?", arrayOf((now - DAYS_BACK * 86_400_000L).toString()), "${Telephony.Sms.DATE} DESC LIMIT 30",
            )?.use { c ->
                val names = HashMap<String, String>()
                while (c.moveToNext()) {
                    val body = c.getString(2).orEmpty()
                    if (body.take(24) in seen) continue
                    val address = c.getString(1).orEmpty()
                    val who = names.getOrPut(address) { contactName(context, address) ?: address }
                    out.add(Item("s:${c.getLong(0)}", "message", Telephony.Sms.getDefaultSmsPackage(context), who, body, c.getLong(3), c.getInt(4) == 0, null,
                        Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$address"))))
                }
            }
        }

        if (AppSettings.sourceEnabled(context, "call") && has(context, Manifest.permission.READ_CALL_LOG)) runCatching {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI, arrayOf(CallLog.Calls._ID, CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.DATE, CallLog.Calls.IS_READ),
                "${CallLog.Calls.TYPE} = ? AND ${CallLog.Calls.DATE} > ?", arrayOf(CallLog.Calls.MISSED_TYPE.toString(), (now - DAYS_BACK * 86_400_000L).toString()),
                "${CallLog.Calls.DATE} DESC LIMIT 15",
            )?.use { c ->
                while (c.moveToNext()) {
                    val number = c.getString(1).orEmpty()
                    val name = c.getString(2)?.takeIf { it.isNotBlank() } ?: number.ifBlank { "Unknown" }
                    out.add(Item("c:${c.getLong(0)}", "call", null, name, "Missed call", c.getLong(3), c.getInt(4) == 0, null, Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number"))))
                }
            }
        }

        if (AppSettings.sourceEnabled(context, "event") && has(context, Manifest.permission.READ_CALENDAR)) runCatching {
            val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also { ContentUris.appendId(it, now); ContentUris.appendId(it, now + 7 * 86_400_000L) }.build()
            context.contentResolver.query(
                uri, arrayOf(CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.EVENT_LOCATION),
                null, null, "${CalendarContract.Instances.BEGIN} ASC LIMIT 10",
            )?.use { c ->
                while (c.moveToNext()) {
                    out.add(Item("e:${c.getLong(0)}:${c.getLong(2)}", "event", null, c.getString(1).orEmpty().ifBlank { "Event" }, c.getString(3).orEmpty(), c.getLong(2), false, null,
                        Intent(Intent.ACTION_VIEW, Uri.withAppendedPath(CalendarContract.Events.CONTENT_URI, c.getLong(0).toString()))))
                }
            }
        }
        // Upcoming events first (soonest first), then everything else newest first.
        val (future, past) = out.partition { it.kind == "event" && it.whenMs > now }
        return future.sortedBy { it.whenMs } + past.sortedByDescending { it.whenMs }
    }

    private fun contactName(context: Context, number: String): String? {
        if (number.isBlank() || !has(context, Manifest.permission.READ_CONTACTS)) return null
        return runCatching {
            context.contentResolver.query(Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number)), arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull()
    }
}
