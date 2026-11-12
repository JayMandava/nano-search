package ai.nanosearch.launcher

import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/** Main-thread bindings; package lookup, pack parsing and bitmap drawing run on one worker. */
internal class AsyncIconLoader(
    private val load: (String) -> Drawable?,
    private val executor: Executor = worker,
    private val deliver: ((() -> Unit) -> Unit) = { main.post(it) },
) {
    private class Request(val key: String, val generation: Int)
    private val cache = LruCache<String, Drawable.ConstantState>(128)
    private val bindings = WeakHashMap<ImageView, Request>()
    private val pending = mutableMapOf<String, MutableList<WeakReference<ImageView>>>()
    private var generation = 0

    fun bind(view: ImageView, key: String) {
        val request = Request(key, generation)
        bindings[view] = request
        val cached = cache.get(key)
        view.setImageDrawable(cached?.newDrawable(view.resources)?.mutate())
        if (cached != null) return
        pending[key]?.let { targets ->
            // A repeated bind during layout must not accumulate the same target indefinitely.
            if (targets.none { it.get() === view }) targets.add(WeakReference(view))
            return
        }
        pending[key] = mutableListOf(WeakReference(view))
        executor.execute {
            // Cache states, not the Drawable instance that an ImageView mutates with bounds/callbacks.
            val drawable = runCatching { load(key) }.getOrNull()
            deliver {
                if (request.generation == generation) {
                    val targets = pending.remove(key).orEmpty()
                    drawable?.constantState?.let { cache.put(key, it) }
                    targets.forEach { target ->
                        val image = target.get()
                        val binding = image?.let { bindings[it] }
                        if (image != null && binding?.key == key && binding.generation == generation) {
                            image.setImageDrawable(drawable?.constantState?.newDrawable(image.resources)?.mutate() ?: drawable)
                        }
                    }
                }
            }
        }
    }

    fun clear() {
        generation++
        cache.evictAll()
        pending.clear()
        bindings.clear()
    }

    companion object {
        private val main = Handler(Looper.getMainLooper())
        private val worker = Executors.newSingleThreadExecutor { task ->
            Thread({ android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); task.run() }, "launcher-icons")
        }
    }
}
