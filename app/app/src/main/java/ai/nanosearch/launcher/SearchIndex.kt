package ai.nanosearch.launcher

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * One searchable thing on the device. [kind] is app, contact, message, call, event, file or setting.
 * [key] is whatever [MainActivity] needs to open it, [sub] is the second line shown under the title,
 * and [body] is extra text that is searchable but not shown (message text, file path, phone numbers).
 */
data class Item(val kind: String, val title: String, val key: String, val sub: String = "", val body: String = "")

/**
 * On-device search index. One FTS4 table holds everything, distinguished by [Item.kind]. Queries never
 * touch a model: prefix-match FTS4 first (title matches before body matches), substring LIKE as a fallback
 * for mid-word matches. (Android's bundled SQLite has no FTS5.)
 */
class SearchIndex(context: Context) : SQLiteOpenHelper(context, "index.db", null, 6) {

    override fun onConfigure(db: SQLiteDatabase) {
        // Readers must not block while a large re-index (thousands of messages and files) is writing.
        db.enableWriteAheadLogging()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE VIRTUAL TABLE items USING fts4(title, body, kind, key, sub, " +
                "notindexed=kind, notindexed=key, notindexed=sub, tokenize=unicode61, prefix=\"2,3\")"
        )
        // Photos also live in a plain table: date ranges ("last September") are numeric filters that full-text search cannot do,
        // and it doubles as a cache so a photo's EXIF is only read once.
        db.execSQL(
            "CREATE TABLE photos(id INTEGER PRIMARY KEY, modified INTEGER, taken INTEGER, name TEXT, mime TEXT, folder TEXT, " +
                "lat REAL, lon REAL, place TEXT, place_lc TEXT, loc_checked INTEGER)"
        )
        db.execSQL("CREATE INDEX photos_taken ON photos(taken)")
        // One 512-number "fingerprint" per photo (float32), from the image model; see ClipEngine.
        db.execSQL("CREATE TABLE photo_vec(id INTEGER PRIMARY KEY, modified INTEGER, vec BLOB)")
        // Which photos have been read for text (whether or not they had any); the text itself is searchable as kind "phototext".
        db.execSQL("CREATE TABLE photo_ocr(id INTEGER PRIMARY KEY, modified INTEGER, has_text INTEGER)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS items")
        db.execSQL("DROP TABLE IF EXISTS photos")
        db.execSQL("DROP TABLE IF EXISTS photo_vec")
        db.execSQL("DROP TABLE IF EXISTS photo_ocr")
        onCreate(db)
    }

    /** Words from app and contact names, for typo-tolerant matching. Rebuilt lazily after the index changes. */
    @Volatile private var vocabulary: Set<String>? = null

    /** Replace every row of [kind] with [items] in one transaction. */
    fun replaceKind(kind: String, items: List<Item>) {
        vocabulary = null
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("items", "kind = ?", arrayOf(kind))
            val stmt = db.compileStatement("INSERT INTO items(title, body, kind, key, sub) VALUES (?, ?, ?, ?, ?)")
            for (item in items) {
                stmt.bindString(1, item.title)
                stmt.bindString(2, item.body)
                stmt.bindString(3, item.kind)
                stmt.bindString(4, item.key)
                stmt.bindString(5, item.sub)
                stmt.executeInsert()
                stmt.clearBindings()
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    class PhotoRow(
        val id: Long, val modified: Long, val taken: Long, val name: String, val mime: String, val folder: String,
        val lat: Double?, val lon: Double?, val place: String, val locChecked: Boolean,
    )

    /** What is already known about each photo, so unchanged ones are not re-read. */
    fun photoCache(): Map<Long, PhotoRow> = readableDatabase.rawQuery(
        "SELECT id, modified, taken, name, mime, folder, lat, lon, place, loc_checked FROM photos", null
    ).use { c ->
        val out = HashMap<Long, PhotoRow>(c.count)
        while (c.moveToNext()) out[c.getLong(0)] = PhotoRow(
            c.getLong(0), c.getLong(1), c.getLong(2), c.getString(3), c.getString(4), c.getString(5),
            if (c.isNull(6)) null else c.getDouble(6), if (c.isNull(7)) null else c.getDouble(7), c.getString(8) ?: "", c.getInt(9) == 1
        )
        out
    }

    fun replacePhotos(rows: List<PhotoRow>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("photos", null, null)
            val stmt = db.compileStatement("INSERT INTO photos VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
            for (r in rows) {
                stmt.bindLong(1, r.id); stmt.bindLong(2, r.modified); stmt.bindLong(3, r.taken)
                stmt.bindString(4, r.name); stmt.bindString(5, r.mime); stmt.bindString(6, r.folder)
                if (r.lat != null && r.lon != null) { stmt.bindDouble(7, r.lat); stmt.bindDouble(8, r.lon) } else { stmt.bindNull(7); stmt.bindNull(8) }
                stmt.bindString(9, r.place); stmt.bindString(10, r.place.lowercase()); stmt.bindLong(11, if (r.locChecked) 1 else 0)
                stmt.executeInsert()
                stmt.clearBindings()
            }
            db.execSQL("DELETE FROM photo_vec WHERE id NOT IN (SELECT id FROM photos)")
            db.execSQL("DELETE FROM photo_ocr WHERE id NOT IN (SELECT id FROM photos)")
            db.execSQL("DELETE FROM items WHERE kind = 'phototext' AND CAST(substr(key, 1, instr(key, '|') - 1) AS INTEGER) NOT IN (SELECT id FROM photos)")
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        vectors = null
    }

    // ---- photo fingerprints (semantic search)

    @Volatile private var vectors: HashMap<Long, FloatArray>? = null

    /** Photos with no fingerprint yet (or a stale one), newest first, as [id, modified] pairs. */
    fun pendingPhotos(limit: Int): List<LongArray> = readableDatabase.rawQuery(
        "SELECT p.id, p.modified FROM photos p LEFT JOIN photo_vec v ON v.id = p.id WHERE v.id IS NULL OR v.modified != p.modified ORDER BY p.taken DESC LIMIT ?",
        arrayOf(limit.toString())
    ).use { c ->
        val out = ArrayList<LongArray>(c.count)
        while (c.moveToNext()) out.add(longArrayOf(c.getLong(0), c.getLong(1)))
        out
    }

    fun vectorCount(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM photo_vec", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    fun putVectors(batch: List<Triple<Long, Long, FloatArray>>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val stmt = db.compileStatement("INSERT OR REPLACE INTO photo_vec VALUES (?, ?, ?)")
            for ((id, modified, vec) in batch) {
                val bytes = java.nio.ByteBuffer.allocate(vec.size * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).also { it.asFloatBuffer().put(vec) }.array()
                stmt.bindLong(1, id); stmt.bindLong(2, modified); stmt.bindBlob(3, bytes)
                stmt.executeInsert()
                stmt.clearBindings()
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        vectors?.let { cache -> for ((id, _, vec) in batch) cache[id] = vec } // keep a loaded cache current without re-reading it
    }

    /** All fingerprints by photo id, loaded once and kept until the photo table is rebuilt. */
    fun photoVectors(): Map<Long, FloatArray> = vectors ?: synchronized(this) {
        vectors ?: readableDatabase.rawQuery("SELECT id, vec FROM photo_vec", null).use { c ->
            val map = HashMap<Long, FloatArray>(c.count * 2)
            while (c.moveToNext()) {
                val b = java.nio.ByteBuffer.wrap(c.getBlob(1)).order(java.nio.ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                map[c.getLong(0)] = FloatArray(b.remaining()).also { b.get(it) }
            }
            map
        }.also { vectors = it }
    }

    class OcrTask(val id: Long, val modified: Long, val name: String, val mime: String, val taken: Long, val place: String, val screenshot: Boolean)

    /** Photos not yet read for text: screenshots first (they are nearly all text), then newest first. */
    fun pendingOcr(limit: Int): List<OcrTask> = readableDatabase.rawQuery(
        "SELECT p.id, p.modified, p.name, p.mime, p.taken, p.place, " +
            "(CASE WHEN lower(p.folder) LIKE '%screenshot%' OR lower(p.name) LIKE '%screenshot%' THEN 1 ELSE 0 END) " +
            "FROM photos p LEFT JOIN photo_ocr o ON o.id = p.id " +
            "WHERE o.id IS NULL OR o.modified != p.modified " +
            "ORDER BY (CASE WHEN lower(p.folder) LIKE '%screenshot%' OR lower(p.name) LIKE '%screenshot%' THEN 0 ELSE 1 END), p.taken DESC LIMIT ?",
        arrayOf(limit.toString())
    ).use { c ->
        val out = ArrayList<OcrTask>(c.count)
        while (c.moveToNext()) out.add(OcrTask(c.getLong(0), c.getLong(1), c.getString(2), c.getString(3), c.getLong(4), c.getString(5) ?: "", c.getInt(6) == 1))
        out
    }

    /** Forget all text read from photos, so the next run reads them again (used when the reader or its text format improves). */
    fun clearOcr() {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("DELETE FROM photo_ocr")
            db.execSQL("DELETE FROM items WHERE kind = 'phototext'")
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun ocrDone(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM photo_ocr", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }
    fun ocrWithText(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM photo_ocr WHERE has_text = 1", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    /** Record that a photo was read; a non-empty [text] also becomes searchable. */
    fun putOcr(t: OcrTask, text: String) {
        val db = writableDatabase
        val key = "${t.id}|${t.mime}"
        db.beginTransaction()
        try {
            db.execSQL("INSERT OR REPLACE INTO photo_ocr VALUES (?, ?, ?)", arrayOf(t.id, t.modified, if (text.isBlank()) 0 else 1))
            db.execSQL("DELETE FROM items WHERE kind = 'phototext' AND key = ?", arrayOf(key))
            if (text.isNotBlank()) {
                val date = java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(t.taken))
                val snippet = text.replace(Regex("\\s+"), " ").take(90)
                // The text is indexed twice: as read, and with the joining punctuation removed, so "wifi" finds "Wi-Fi".
                val joined = text.replace(Regex("(?<=[\\p{L}\\p{N}])[-_'.](?=[\\p{L}\\p{N}])"), "")
                db.execSQL("INSERT INTO items(title, body, kind, key, sub) VALUES (?, ?, 'phototext', ?, ?)", arrayOf(t.name, text + "\n" + joined, key, "$date · $snippet"))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Photos among [hits] whose text contains every word of [content] ("wifi password", "invoice"). */
    private fun ocrMatches(content: String, hits: List<PhotoHit>): List<PhotoHit> {
        val words = content.lowercase().split(NON_WORD).filter { it.length >= 2 }
        if (words.isEmpty() || hits.isEmpty()) return emptyList()
        val ids = HashSet<Long>()
        readableDatabase.rawQuery(
            "SELECT key FROM items WHERE items MATCH ? AND kind = 'phototext' LIMIT 3000", arrayOf(words.joinToString(" ") { "$it*" })
        ).use { c -> while (c.moveToNext()) c.getString(0).substringBefore('|').toLongOrNull()?.let { ids.add(it) } }
        return hits.filter { it.id in ids }
    }

    class PhotoHit(val id: Long, val name: String, val mime: String, val taken: Long, val place: String)

    /** Is [text] a place that some indexed photo was taken in ("goa", "new york")? Then "photos of goa" means the place, not the subject. */
    private fun placeKnown(text: String): Boolean {
        val words = text.split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) return false
        val where = words.joinToString(" AND ") { "place_lc LIKE ?" }
        return readableDatabase.rawQuery("SELECT 1 FROM photos WHERE $where LIMIT 1", words.map { "%$it%" }.toTypedArray()).use { it.moveToFirst() }
    }

    private fun photoHits(from: Long?, to: Long?, place: String?, screenshots: Boolean = false): List<PhotoHit> {
        val where = StringBuilder("1 = 1")
        val args = ArrayList<String>()
        from?.let { where.append(" AND taken >= ?"); args.add(it.toString()) }
        to?.let { where.append(" AND taken < ?"); args.add(it.toString()) }
        place?.split(' ')?.filter { it.isNotEmpty() }?.forEach { where.append(" AND place_lc LIKE ?"); args.add("%$it%") }
        if (screenshots) where.append(" AND (lower(folder) LIKE '%screenshot%' OR lower(name) LIKE '%screenshot%')")
        return readableDatabase.rawQuery("SELECT id, name, mime, taken, place FROM photos WHERE $where ORDER BY taken DESC LIMIT 30000", args.toTypedArray()).use { c ->
            val out = ArrayList<PhotoHit>()
            while (c.moveToNext()) out.add(PhotoHit(c.getLong(0), c.getString(1), c.getString(2), c.getLong(3), c.getString(4) ?: ""))
            out
        }
    }

    /**
     * Date and place filter the photos table; if the request also says what is in the picture ("beach photos from Goa last week"),
     * the survivors are ranked by how well their fingerprints match that phrase. Returns nothing when the request needs the image
     * model and it (or the fingerprints) is not ready, so ordinary search still gets its turn.
     */
    fun searchPhotos(q: PhotoQuery.Q, limit: Int): List<Item> {
        var place = q.place
        var content = q.content
        if (place == null && content != null && placeKnown(content)) { place = content; content = null }
        val hits = photoHits(q.from, q.to, place, q.screenshots)
        if (content == null) return hits.take(limit).map { photoItem(it) }
        // Photos whose own text contains the words come first ("screenshots with wifi password"); the image model adds what merely looks like it,
        // and is held to a stricter cutoff once there are text matches, so a good exact hit is not buried under vague ones.
        val textHits = ocrMatches(content, hits)
        // Screenshots all look alike to the image model, so once some screenshots contain the words there is nothing more to add.
        if (q.screenshots && textHits.isNotEmpty()) return textHits.take(limit).map { photoItem(it) }
        val rest = hits.filter { h -> textHits.none { it.id == h.id } }
        val seen = Semantic.rank(this, content, rest, strict = textHits.isNotEmpty())
        if (seen == null && textHits.isEmpty()) return emptyList()
        return (textHits + (seen ?: emptyList())).take(limit).map { photoItem(it) }
    }

    private fun photoItem(h: PhotoHit): Item {
        val date = java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM)
        return Item("photo", h.name, "${h.id}|${h.mime}", sub = date.format(java.util.Date(h.taken)) + if (h.place.isNotEmpty()) " · ${h.place}" else "")
    }

    /** A short query ("beach") that matched nothing else may still describe a photo; only clear matches are shown. */
    private fun semanticFallback(query: String, tokens: List<String>): List<Item> {
        if (tokens.size > 3 || tokens.any { it.length < 3 }) return emptyList()
        val ranked = Semantic.rank(this, query.trim().lowercase(), photoHits(null, null, null), strict = true) ?: return emptyList()
        return ranked.take(12).map { photoItem(it) }
    }

    fun count(kind: String): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM items WHERE kind = ?", arrayOf(kind)).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    /** [kinds] restricts the search; null searches everything. An empty [query] lists apps alphabetically. */
    fun search(query: String, limit: Int = 60, kinds: List<String>? = null, semantic: Boolean = true): List<Item> {
        val db = readableDatabase
        val tokens = query.lowercase().split(NON_WORD).filter { it.isNotEmpty() }

        if (tokens.isEmpty()) {
            return db.rawQuery(
                "SELECT kind, title, key, sub FROM items WHERE kind = 'app' ORDER BY title COLLATE NOCASE LIMIT ?",
                arrayOf(limit.toString())
            ).use { it.toItems() }
        }

        // "photos from Goa last September": answered from the photos table by date and place, newest first.
        // (A request about what is in the pictures waits for [semantic] = true: the image model is not run on half-typed words.)
        PhotoQuery.parse(query)?.let { pq ->
            if (pq.content == null || semantic) {
                val photos = searchPhotos(pq, limit)
                if (photos.isNotEmpty()) return photos
            }
        }

        val searchKinds = kinds ?: ALL_KINDS
        val results = LinkedHashMap<String, Item>()
        val titleMatch = tokens.joinToString(" ") { "title:$it*" }
        val anyMatch = tokens.joinToString(" ") { "$it*" }
        val like = "%" + query.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"

        // Per kind, so a huge kind (thousands of messages or files) cannot crowd out apps and contacts.
        for (kind in searchKinds) {
            for (match in listOf(titleMatch, anyMatch)) {
                db.rawQuery(
                    "SELECT kind, title, key, sub FROM items WHERE items MATCH ? AND kind = ? LIMIT ?",
                    arrayOf(match, kind, PER_KIND.toString())
                ).use { c -> c.toItems().forEach { results.putIfAbsent(dedupeKey(it), it) } }
            }
            db.rawQuery(
                "SELECT kind, title, key, sub FROM items WHERE title LIKE ? ESCAPE '\\' AND kind = ? LIMIT ?",
                arrayOf(like, kind, PER_KIND.toString())
            ).use { c -> c.toItems().forEach { results.putIfAbsent(dedupeKey(it), it) } }
        }
        showMatchingPassages(results, anyMatch)
        if (results.isEmpty()) {
            val near = fuzzy(tokens, limit, kinds)
            if (near.isNotEmpty()) return near
            // A request that was clearly about photos ("screenshots yesterday") and found none must not be padded with look-alikes.
            return if (semantic && kinds == null && PhotoQuery.parse(query) == null) semanticFallback(query, tokens) else emptyList()
        }
        return results.values.sortedWith(rankBy(query.trim().lowercase(), tokens)).take(limit)
    }

    /**
     * Nothing matched exactly: retry with the closest real words, so "prya" finds Priya and a misheard "nanaya" finds Ananya.
     * Only names and app titles are considered, and only words of four or more letters (short words are too ambiguous).
     */
    private fun fuzzy(tokens: List<String>, limit: Int, kinds: List<String>?): List<Item> {
        val vocab = vocabulary ?: readableDatabase.rawQuery("SELECT title FROM items WHERE kind IN ('app', 'contact')", null).use { c ->
            val words = HashSet<String>()
            while (c.moveToNext()) c.getString(0).lowercase().split(NON_WORD).filterTo(words) { it.length >= 3 }
            words
        }.also { vocabulary = it }
        val fixed = tokens.map { t ->
            if (t.length < 4 || t in vocab) t else {
                val allowed = if (t.length >= 7) 2 else 1
                vocab.filter { kotlin.math.abs(it.length - t.length) <= allowed && editDistance(it, t) <= allowed }
                    .minWithOrNull(compareBy<String>({ editDistance(it, t) }, { if (it[0] == t[0]) 0 else 1 }, { kotlin.math.abs(it.length - t.length) }, { it })) ?: t
            }
        }
        if (fixed == tokens) return emptyList()
        return search(fixed.joinToString(" "), limit, kinds)
    }

    /** Damerau-Levenshtein distance: a swapped pair of letters ("nanya" / "ananya") counts as one edit. */
    private fun editDistance(a: String, b: String): Int {
        val d = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) d[i][0] = i
        for (j in 0..b.length) d[0][j] = j
        for (i in 1..a.length) for (j in 1..b.length) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + cost)
            if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) d[i][j] = minOf(d[i][j], d[i - 2][j - 2] + 1)
        }
        return d[a.length][b.length]
    }

    /** Title starts with the query, then a word in the title starts with it, then substring, then body-only matches. */
    private fun rankBy(query: String, tokens: List<String>) =
        compareBy<Item>({ tier(it.title.lowercase(), query, tokens) }, { KIND_ORDER[it.kind] ?: 9 }, { it.title.length }, { it.title.lowercase() })

    private fun tier(title: String, query: String, tokens: List<String>): Int = when {
        title.startsWith(query) -> 0
        title.split(NON_WORD).any { it.startsWith(tokens.first()) } -> 1
        title.contains(tokens.first()) -> 2
        else -> 3
    }

    /** For photos found by their text, replace the stored opening of the text with the passage that matched, in context. */
    private fun showMatchingPassages(results: MutableMap<String, Item>, match: String) {
        val db = readableDatabase
        for ((k, item) in results.entries.toList()) {
            if (item.kind != "phototext") continue
            val passage = db.rawQuery("SELECT snippet(items, '', '', ' … ', 1, 12) FROM items WHERE items MATCH ? AND kind = 'phototext' AND key = ?", arrayOf(match, item.key))
                .use { if (it.moveToFirst()) it.getString(0) else null }?.replace(Regex("\\s+"), " ")?.trim()
            if (!passage.isNullOrBlank()) results[k] = item.copy(sub = item.sub.substringBefore(" · ") + " · " + passage)
        }
    }

    /** A photo found by its metadata and the same photo found by its text are one result. */
    private fun dedupeKey(i: Item) = (if (i.kind == "phototext") "photo" else i.kind) + ":" + i.key

    private fun Cursor.toItems(): List<Item> {
        val out = ArrayList<Item>(count)
        while (moveToNext()) out.add(Item(getString(0), getString(1), getString(2), getString(3) ?: ""))
        return out
    }

    companion object {
        private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")
        private const val PER_KIND = 40
        val ALL_KINDS = listOf("app", "contact", "setting", "event", "photo", "phototext", "file", "message", "call")
        private val KIND_ORDER = ALL_KINDS.withIndex().associate { it.value to it.index }
    }
}
