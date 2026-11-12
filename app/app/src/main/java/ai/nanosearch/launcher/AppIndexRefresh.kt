package ai.nanosearch.launcher

/** The hidden state belongs to the indexed snapshot, not a previous activity start. */
internal object AppIndexRefresh {
    fun needsRebuild(appsChanged: Boolean, appCount: Int, currentHiddenKey: String, indexedHiddenKey: String?): Boolean =
        appsChanged || appCount == 0 || currentHiddenKey != indexedHiddenKey
}
