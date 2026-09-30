package ai.nanosearch.launcher

import android.Manifest
import android.content.ComponentName
import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.os.Bundle
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import android.provider.CalendarContract
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import java.text.DateFormat
import java.util.Date

/**
 * Collectors that read one kind of on-device content into [Item]s. Each returns null when its permission
 * is missing, so the caller keeps whatever was indexed before instead of wiping it.
 * Everything stays on the device: rows go into the app-private index and nowhere else.
 */
object Indexers {
    private const val MAX_MESSAGES = 6000
    private const val MAX_CALLS = 400
    private const val MAX_EVENTS = 3000
    private const val MAX_FILES = 40000
    private const val MAX_PHOTOS = 30000


    /** Providers reject "LIMIT n" inside the sort order on current Android; the limit goes in the query args. */
    private fun ContentResolver.queryLimited(uri: Uri, projection: Array<String>, selection: String?, sort: String, limit: Int): Cursor? =
        query(uri, projection, Bundle().apply {
            selection?.let { putString(ContentResolver.QUERY_ARG_SQL_SELECTION, it) }
            putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, sort)
            putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
        }, null)

    private fun Context.granted(permission: String) = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    fun apps(context: Context): List<Item> {
        val launch = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return context.packageManager.queryIntentActivities(launch, 0).map {
            Item("app", it.loadLabel(context.packageManager).toString(),
                ComponentName(it.activityInfo.packageName, it.activityInfo.name).flattenToString())
        }
    }

    fun contacts(context: Context): List<Item>? {
        if (!context.granted(Manifest.permission.READ_CONTACTS)) return null
        val numbers = HashMap<Long, StringBuilder>()
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.CONTACT_ID, ContactsContract.CommonDataKinds.Phone.NUMBER),
            null, null, null
        )?.use { c ->
            while (c.moveToNext()) numbers.getOrPut(c.getLong(0)) { StringBuilder() }.append(c.getString(1) ?: "").append(' ')
        }
        val out = ArrayList<Item>()
        context.contentResolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.LOOKUP_KEY, ContactsContract.Contacts.DISPLAY_NAME_PRIMARY),
            null, null, null
        )?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(2) ?: continue
                val id = c.getLong(0)
                out.add(Item("contact", name, ContactsContract.Contacts.getLookupUri(id, c.getString(1)).toString(),
                    body = numbers[id]?.toString() ?: ""))
            }
        }
        return out
    }

    fun messages(context: Context): List<Item>? {
        if (!context.granted(Manifest.permission.READ_SMS)) return null
        val names = HashMap<String, String?>()
        fun nameFor(address: String): String? = names.getOrPut(address) {
            if (!context.granted(Manifest.permission.READ_CONTACTS)) return@getOrPut null
            context.contentResolver.query(
                Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(address)),
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null
            )?.use { if (it.moveToFirst()) it.getString(0) else null }
        }
        val fmt = DateFormat.getDateInstance(DateFormat.MEDIUM)
        val out = ArrayList<Item>()
        context.contentResolver.queryLimited(
            Uri.parse("content://sms"), arrayOf("_id", "address", "body", "date"), null, "date DESC", MAX_MESSAGES
        )?.use { c ->
            while (c.moveToNext()) {
                val address = c.getString(1) ?: continue
                val text = (c.getString(2) ?: "").replace(Regex("\\s+"), " ").trim()
                if (text.isEmpty()) continue
                out.add(Item("message", nameFor(address) ?: address, "$address|${c.getLong(0)}",
                    sub = "${fmt.format(Date(c.getLong(3)))} · ${text.take(90)}", body = text))
            }
        }
        return out
    }

    fun calls(context: Context): List<Item>? {
        if (!context.granted(Manifest.permission.READ_CALL_LOG)) return null
        val fmt = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        val out = ArrayList<Item>()
        context.contentResolver.queryLimited(
            CallLog.Calls.CONTENT_URI,
            arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.DATE, CallLog.Calls.TYPE, CallLog.Calls._ID),
            null, "${CallLog.Calls.DATE} DESC", MAX_CALLS
        )?.use { c ->
            while (c.moveToNext()) {
                val number = c.getString(0) ?: continue
                val kind = when (c.getInt(3)) {
                    CallLog.Calls.MISSED_TYPE -> "Missed call"
                    CallLog.Calls.OUTGOING_TYPE -> "Outgoing call"
                    else -> "Incoming call"
                }
                out.add(Item("call", c.getString(1)?.takeIf { it.isNotBlank() } ?: number, "$number|${c.getLong(4)}",
                    sub = "$kind · ${fmt.format(Date(c.getLong(2)))}", body = number))
            }
        }
        return out
    }

    fun events(context: Context): List<Item>? {
        if (!context.granted(Manifest.permission.READ_CALENDAR)) return null
        val fmt = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        val out = ArrayList<Item>()
        context.contentResolver.queryLimited(
            CalendarContract.Events.CONTENT_URI,
            arrayOf(CalendarContract.Events._ID, CalendarContract.Events.TITLE, CalendarContract.Events.DESCRIPTION,
                CalendarContract.Events.DTSTART, CalendarContract.Events.EVENT_LOCATION),
            "${CalendarContract.Events.DELETED} = 0", "${CalendarContract.Events.DTSTART} DESC", MAX_EVENTS
        )?.use { c ->
            while (c.moveToNext()) {
                val title = c.getString(1)?.takeIf { it.isNotBlank() } ?: continue
                val where = c.getString(4)?.takeIf { it.isNotBlank() }
                out.add(Item("event", title, c.getLong(0).toString(),
                    sub = fmt.format(Date(c.getLong(3))) + (where?.let { " · $it" } ?: ""),
                    body = "${c.getString(2) ?: ""} ${where ?: ""}".trim()))
            }
        }
        return out
    }

    /** Media only, unless the user has granted all-files access, in which case documents and downloads appear too. */
    fun files(context: Context): List<Item>? {
        val hasMedia = context.granted(Manifest.permission.READ_MEDIA_IMAGES) || context.granted(Manifest.permission.READ_MEDIA_VIDEO) ||
            context.granted(Manifest.permission.READ_MEDIA_AUDIO)
        if (!hasMedia && !Environment.isExternalStorageManager()) return null
        val out = ArrayList<Item>()
        context.contentResolver.queryLimited(
            MediaStore.Files.getContentUri("external"),
            arrayOf(MediaStore.Files.FileColumns._ID, MediaStore.Files.FileColumns.DISPLAY_NAME,
                MediaStore.Files.FileColumns.RELATIVE_PATH, MediaStore.Files.FileColumns.MIME_TYPE),
            "${MediaStore.Files.FileColumns.SIZE} > 0 AND ${MediaStore.Files.FileColumns.MIME_TYPE} IS NOT NULL " +
                "AND ${MediaStore.Files.FileColumns.DISPLAY_NAME} NOT LIKE '.%' " +
                "AND ${MediaStore.Files.FileColumns.RELATIVE_PATH} NOT LIKE 'Android/%' " +
                "AND ${MediaStore.Files.FileColumns.MEDIA_TYPE} != ${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE}", // images are indexed as photos
            "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC", MAX_FILES
        )?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(1) ?: continue
                val folder = (c.getString(2) ?: "").trimEnd('/')
                out.add(Item("file", name, "${c.getLong(0)}|${c.getString(3)}", sub = folder.ifEmpty { "Storage" }, body = folder.replace('/', ' ')))
            }
        }
        return out
    }

    /**
     * Every photo with its date taken and, when Android lets us see it, where it was taken (EXIF GPS mapped to a city, region and
     * country by [Gazetteer]). Only photos that are new or changed have their EXIF read; the rest come from [cache].
     */
    fun photos(context: Context, cache: Map<Long, SearchIndex.PhotoRow>): List<SearchIndex.PhotoRow>? {
        val hasImages = context.granted(Manifest.permission.READ_MEDIA_IMAGES) || Environment.isExternalStorageManager()
        if (!hasImages) return null
        val hasLocation = context.granted(Manifest.permission.ACCESS_MEDIA_LOCATION)
        val base = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val out = ArrayList<SearchIndex.PhotoRow>()
        context.contentResolver.queryLimited(
            base,
            arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.RELATIVE_PATH,
                MediaStore.Images.Media.MIME_TYPE, MediaStore.Images.Media.DATE_TAKEN, MediaStore.Images.Media.DATE_MODIFIED),
            "${MediaStore.Images.Media.SIZE} > 0", "${MediaStore.Images.Media.DATE_MODIFIED} DESC", MAX_PHOTOS
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val modified = c.getLong(5)
                val storeTaken = c.getLong(4).takeIf { it > 0 } ?: (modified * 1000)
                val known = cache[id]
                if (known != null && known.modified == modified && (known.locChecked || !hasLocation)) {
                    out.add(SearchIndex.PhotoRow(id, modified, known.taken, c.getString(1) ?: "", c.getString(3) ?: "image/*", known.folder, known.lat, known.lon, known.place, known.locChecked))
                    continue
                }
                // New or changed: read the EXIF. The date is always readable; the GPS position only with ACCESS_MEDIA_LOCATION.
                var lat: Double? = null
                var lon: Double? = null
                var taken = storeTaken
                runCatching {
                    val plain = android.content.ContentUris.withAppendedId(base, id)
                    val uri = if (hasLocation) MediaStore.setRequireOriginal(plain) else plain
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        val exif = android.media.ExifInterface(stream)
                        val ll = FloatArray(2)
                        if (hasLocation && exif.getLatLong(ll)) { lat = ll[0].toDouble(); lon = ll[1].toDouble() }
                        val raw = exif.getAttribute(android.media.ExifInterface.TAG_DATETIME_ORIGINAL) ?: exif.getAttribute(android.media.ExifInterface.TAG_DATETIME)
                        raw?.let { java.text.SimpleDateFormat("yyyy:MM:dd HH:mm:ss", java.util.Locale.US).parse(it)?.time }?.let { taken = it }
                    }
                }
                val place = if (lat != null && lon != null) Gazetteer.nearest(context, lat!!, lon!!)?.toString() ?: "" else ""
                out.add(SearchIndex.PhotoRow(id, modified, taken, c.getString(1) ?: "", c.getString(3) ?: "image/*",
                    (c.getString(2) ?: "").trimEnd('/'), lat, lon, place, hasLocation))
            }
        }
        return out
    }

    /** The searchable side of each photo: words for its date and place, so typing "goa" or "june 2024" finds it too. */
    fun photoItems(rows: List<SearchIndex.PhotoRow>): List<Item> {
        val date = DateFormat.getDateInstance(DateFormat.MEDIUM)
        val month = java.text.SimpleDateFormat("MMMM yyyy EEEE", java.util.Locale.ENGLISH)
        return rows.map { r ->
            Item("photo", r.name, "${r.id}|${r.mime}",
                sub = date.format(Date(r.taken)) + if (r.place.isNotEmpty()) " · ${r.place}" else "",
                body = "photo picture image ${month.format(Date(r.taken))} ${r.place} ${r.folder.replace('/', ' ')}")
        }
    }

    /** System settings pages, so "wifi" or "battery" jump straight to them. */
    fun settings(): List<Item> = listOf(
        Triple("Wi-Fi", Settings.ACTION_WIFI_SETTINGS, "wifi wireless network internet"),
        Triple("Bluetooth", Settings.ACTION_BLUETOOTH_SETTINGS, "pair headphones devices"),
        Triple("Display", Settings.ACTION_DISPLAY_SETTINGS, "brightness screen timeout dark theme font size"),
        Triple("Sound", Settings.ACTION_SOUND_SETTINGS, "volume ringtone vibration silent"),
        Triple("Battery", Intent.ACTION_POWER_USAGE_SUMMARY, "power usage saver charging"),
        Triple("Storage", Settings.ACTION_INTERNAL_STORAGE_SETTINGS, "space memory free clean"),
        Triple("Apps", Settings.ACTION_APPLICATION_SETTINGS, "installed permissions uninstall default"),
        Triple("Location", Settings.ACTION_LOCATION_SOURCE_SETTINGS, "gps"),
        Triple("Airplane mode", Settings.ACTION_AIRPLANE_MODE_SETTINGS, "flight"),
        Triple("Mobile network", Settings.ACTION_DATA_ROAMING_SETTINGS, "data sim roaming"),
        Triple("Security", Settings.ACTION_SECURITY_SETTINGS, "lock screen fingerprint password"),
        Triple("Accessibility", Settings.ACTION_ACCESSIBILITY_SETTINGS, "talkback magnification"),
        Triple("Date and time", Settings.ACTION_DATE_SETTINGS, "timezone clock"),
        Triple("Language", Settings.ACTION_LOCALE_SETTINGS, "keyboard input"),
        Triple("Developer options", Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS, "usb debugging adb"),
    ).map { (title, action, words) -> Item("setting", title, action, sub = "Settings", body = words) }
}
