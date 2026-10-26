package ai.nanosearch.launcher.clocks

import ai.nanosearch.launcher.clocks.packs.FacePackStore
import ai.nanosearch.launcher.clocks.packs.InstalledFacePack
import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

/** Public clock catalogue; store loading is asynchronous and never blocks a frame. */
object ClockFaceRegistry {
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    @Volatile private var store: FacePackStore? = null
    private val subscriptions = java.util.IdentityHashMap<FacePackStore, AutoCloseable>()
    private val main by lazy { Handler(Looper.getMainLooper()) }

    fun ensure(context: Context): FacePackStore {
        val value = FacePackStore.get(context.applicationContext)
        synchronized(this) {
            if (!subscriptions.containsKey(value)) subscriptions[value] = value.observe { changed() }
            // Production has one app store. Resolve by context even when isolated test contexts coexist.
            store = value
        }
        return value
    }

    fun definitions(): List<ClockFaceDefinition> = store?.snapshot()?.packs.orEmpty().map {
        ClockFaceDefinition(it.id, it.spec.name, 300, "${it.spec.author} · ${it.spec.license}")
    }

    fun installed(context: Context, id: String): InstalledFacePack? = ensure(context).find(id)

    fun identity(context: Context, id: String): String {
        if (!ClockFaces.isPackId(id)) return id
        val state = ensure(context).snapshot()
        return "$id|${state.generation}|${state.packs.firstOrNull { it.id == id }?.digest ?: "missing"}"
    }

    fun observe(context: Context, listener: () -> Unit): AutoCloseable {
        ensure(context)
        listeners.add(listener)
        return AutoCloseable { listeners.remove(listener) }
    }

    internal fun changed() {
        main.post { listeners.forEach { listener -> runCatching(listener) } }
    }
}
