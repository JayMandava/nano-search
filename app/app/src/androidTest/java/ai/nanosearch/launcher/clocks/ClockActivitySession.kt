package ai.nanosearch.launcher.clocks

import android.app.Activity
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.widget.FrameLayout
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleCallback
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import java.io.Closeable
import java.util.IdentityHashMap
import java.util.UUID
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Real ActivityManager transitions without waiting for the entire UI queue to become idle.
 * ActivityScenario 1.6.1 calls waitForIdleSync before onActivity/recreate/moveToState/getState/
 * close, outside its lifecycle timeout. A continuously drawing clock or progress indicator
 * can starve that global idle on software-rendered devices. We wait for the relevant actual
 * lifecycle callbacks instead; no lifecycle method is invoked directly by this test helper.
 */
internal class ClockActivitySession<A : Activity> private constructor(private val activityClass: Class<A>) : Closeable {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val monitor = ActivityLifecycleMonitorRegistry.getInstance()
    private val main = Handler(Looper.getMainLooper())
    private val token = "clock-test-${UUID.randomUUID()}"
    private val changed = Object()
    private val stages = IdentityHashMap<Activity, Stage>()
    private var activity: A? = null
    private var cover: ClockTestCoverActivity? = null
    private var closed = false
    private val callback = ActivityLifecycleCallback { observed, stage ->
        val target = observed.intent?.identifier == token && activityClass.isInstance(observed)
        val covering = observed.intent?.identifier == "$token-cover" && observed is ClockTestCoverActivity
        if (target || covering) synchronized(changed) {
            stages[observed] = stage
            if (target && (activity == null || stage != Stage.DESTROYED)) activity = activityClass.cast(observed)
            if (covering) cover = observed as ClockTestCoverActivity
            Log.i("ClockLifecycleTest", "activity=${observed.javaClass.simpleName} instance=${System.identityHashCode(observed)} stage=$stage uptime=${SystemClock.uptimeMillis()}")
            changed.notifyAll()
        }
    }

    val state: Lifecycle.State get() = synchronized(changed) {
        when (stages[activity]) {
            Stage.RESUMED -> Lifecycle.State.RESUMED
            Stage.STARTED, Stage.PAUSED -> Lifecycle.State.STARTED
            Stage.CREATED, Stage.STOPPED -> Lifecycle.State.CREATED
            Stage.DESTROYED -> Lifecycle.State.DESTROYED
            else -> Lifecycle.State.INITIALIZED
        }
    }

    private fun <T> onMain(block: () -> T): T {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Lifecycle test actions run from the instrumentation thread" }
        val task = FutureTask(block)
        check(main.post(task)) { "Could not post a lifecycle test action" }
        try { return task.get(LIFECYCLE_TIMEOUT_MS, TimeUnit.MILLISECONDS) }
        catch (failure: ExecutionException) { throw failure.cause ?: failure }
        catch (failure: TimeoutException) {
            task.cancel(false)
            main.removeCallbacks(task)
            throw failure
        } catch (failure: InterruptedException) {
            task.cancel(false)
            main.removeCallbacks(task)
            throw failure
        }
    }

    fun onActivity(action: (A) -> Unit): ClockActivitySession<A> {
        onMain { action(checkNotNull(synchronized(changed) { activity }) { "No test activity" }) }
        return this
    }

    private fun await(message: String, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + LIFECYCLE_TIMEOUT_MS
        synchronized(changed) {
            while (!predicate()) {
                val remaining = deadline - SystemClock.elapsedRealtime()
                if (remaining <= 0) throw AssertionError("$message; stages=${stages.map { "${it.key.javaClass.simpleName}@${System.identityHashCode(it.key)}=${it.value}" }}")
                changed.wait(remaining)
            }
        }
    }

    fun recreate(): ClockActivitySession<A> {
        val previous = synchronized(changed) { checkNotNull(activity) }
        onMain {
            Log.i("ClockLifecycleTest", "request=recreate instance=${System.identityHashCode(previous)} uptime=${SystemClock.uptimeMillis()}")
            previous.recreate()
        }
        await("The actual recreated activity must resume, and its predecessor must be destroyed") {
            activity !== previous && stages[activity] == Stage.RESUMED && stages[previous] == Stage.DESTROYED
        }
        return this
    }

    fun moveToState(state: Lifecycle.State): ClockActivitySession<A> {
        when (state) {
            Lifecycle.State.CREATED -> {
                onActivity {
                    Log.i("ClockLifecycleTest", "request=cover instance=${System.identityHashCode(it)} uptime=${SystemClock.uptimeMillis()}")
                    it.startActivity(Intent(it, ClockTestCoverActivity::class.java).setIdentifier("$token-cover"))
                }
                await("An opaque activity must actually stop the clock host") {
                    stages[activity] == Stage.STOPPED && stages[cover] == Stage.RESUMED
                }
            }
            Lifecycle.State.RESUMED -> {
                val covering = synchronized(changed) { cover }
                if (covering != null) onMain {
                    Log.i("ClockLifecycleTest", "request=uncover instance=${System.identityHashCode(covering)} uptime=${SystemClock.uptimeMillis()}")
                    covering.finish()
                }
                await("Finishing the cover must actually resume the same clock host") {
                    stages[activity] == Stage.RESUMED && (covering == null || stages[covering] == Stage.DESTROYED)
                }
            }
            Lifecycle.State.DESTROYED -> close()
            else -> error("Unsupported clock test lifecycle state: $state")
        }
        return this
    }

    override fun close() {
        if (closed) return
        try {
            onMain {
                val owned = synchronized(changed) { stages.filterValues { it != Stage.DESTROYED }.keys.toList() }
                // Detach drawing callbacks before finish; do not ask a continuously animated UI
                // to become idle as a prerequisite to stopping it.
                owned.forEach { it.setContentView(FrameLayout(it)); it.finish() }
            }
            await("All test-owned activities must be destroyed during cleanup") { stages.values.all { it == Stage.DESTROYED } }
        } finally {
            onMain { monitor.removeLifecycleCallback(callback) }
            closed = true
        }
    }

    companion object {
        // Same bounded lifecycle budget as ActivityScenario 1.6.1, without its unbounded idle prelude.
        private const val LIFECYCLE_TIMEOUT_MS = 45_000L
        fun <A : Activity> launch(activityClass: Class<A>): ClockActivitySession<A> {
            val session = ClockActivitySession(activityClass)
            try {
                session.onMain {
                    session.monitor.addLifecycleCallback(session.callback)
                    session.instrumentation.targetContext.startActivity(
                        Intent(session.instrumentation.targetContext, activityClass)
                            .setIdentifier(session.token).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
                session.await("The actual test activity must resume") { session.stages[session.activity] == Stage.RESUMED }
                return session
            } catch (failure: Throwable) {
                try { session.close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
                throw failure
            }
        }
    }
}
