package ai.nanosearch.launcher

import android.content.Context

/** Notification-only projection: never queries contacts, SMS, call logs, calendar or the search index. */
internal object StandaloneHubData {
    fun load(context: Context): List<HubData.Item> {
        if (!HubStore.allowed(context)) return emptyList()
        return select(HubStore.snapshot(), HubStore.mutedApps(context), HubStore.pinned(context), HubStore.snoozedIds(context))
    }

    internal fun select(notes: List<HubStore.Note>, muted: Set<String>, pinned: Set<String>, snoozed: Set<String>): List<HubData.Item> =
        notes.asSequence()
            .filter { it.pkg !in muted && "n:${it.key}" !in snoozed }
            .map { HubData.Item("n:${it.key}", "app", it.pkg, it.title, it.body, it.whenMs, true, it, null) }
            .sortedWith(compareByDescending<HubData.Item> { it.id in pinned }.thenByDescending { it.whenMs })
            .toList()
}
