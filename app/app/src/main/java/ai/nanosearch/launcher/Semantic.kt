package ai.nanosearch.launcher

import android.util.Log

/**
 * Ranks photos against a phrase using their fingerprints. Cosine similarity of unit vectors is a dot product; scores from this model
 * run about 0.2-0.3 for a good match and 0.05-0.17 for unrelated photos, so there is a small absolute floor and a margin below the best hit.
 */
object Semantic {
    // Real matches score 0.15-0.27 for this model; the best score for something that is not in the library is around 0.14.
    private const val MIN_SCORE = 0.145f
    private const val MARGIN = 0.05f
    // A plain word typed without "photos" is only turned into a photo search when the match is clear.
    private const val STRICT_MIN = 0.19f
    private const val STRICT_MARGIN = 0.04f

    /** Null when the model or the fingerprints are not available yet. */
    fun rank(index: SearchIndex, content: String, hits: List<SearchIndex.PhotoHit>, strict: Boolean = false): List<SearchIndex.PhotoHit>? {
        val clip = Services.clip
        if (!clip.available) return null
        val vectors = index.photoVectors()
        if (vectors.isEmpty()) return null
        val t0 = System.nanoTime()
        val query = clip.encodeText("a photo of $content") ?: return null
        Services.clipUsed()
        val scored = hits.mapNotNull { h -> vectors[h.id]?.let { v -> h to dot(query, v) } }
        if (scored.isEmpty()) return emptyList()
        val best = scored.maxOf { it.second }
        val floor = if (strict) maxOf(STRICT_MIN, best - STRICT_MARGIN) else maxOf(MIN_SCORE, best - MARGIN)
        val kept = scored.filter { it.second >= floor }.sortedByDescending { it.second }
        Log.i("nanosearch", "semantic '$content': best %.3f, kept %d of %d scored (%d candidates, %d fingerprints) in %d ms".format(best, kept.size, scored.size, hits.size, vectors.size, (System.nanoTime() - t0) / 1_000_000))
        return kept.map { it.first }
    }

    private fun dot(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        for (i in a.indices) s += a[i] * b[i]
        return s
    }
}
