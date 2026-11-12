package ai.nanosearch.launcher

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.widget.ImageView
import java.util.concurrent.Executor
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class AsyncIconLoaderTest {
    private class Queue : Executor {
        private val tasks = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { tasks.addLast(command) }
        fun next() = tasks.removeFirst().run()
        val size get() = tasks.size
    }
    private fun image() = ImageView(RuntimeEnvironment.getApplication())

    @Test fun bindingDoesNotLoadOnTheUiThreadAndRecycledCellsRejectOldResults() {
        val worker = Queue()
        val main = Queue()
        var loads = 0
        val loader = AsyncIconLoader({ key -> loads++; ColorDrawable(if (key == "a") Color.RED else Color.BLUE) }, worker, { main.execute(it) })
        val target = image()
        loader.bind(target, "a")
        loader.bind(target, "b")
        assertEquals(0, loads)
        assertNull(target.drawable)
        worker.next(); main.next()
        assertNull(target.drawable)
        worker.next(); main.next()
        assertEquals(Color.BLUE, (target.drawable as ColorDrawable).color)
    }

    @Test fun repeatedAndSharedBindingsDeduplicateWorkAndUseIndependentDrawables() {
        val worker = Queue()
        val main = Queue()
        val loader = AsyncIconLoader({ ColorDrawable(Color.GREEN) }, worker, { main.execute(it) })
        val first = image()
        val second = image()
        loader.bind(first, "a"); loader.bind(first, "a"); loader.bind(second, "a")
        assertEquals(1, worker.size)
        worker.next(); main.next()
        assertNotSame(first.drawable, second.drawable)
        val cached = image()
        loader.bind(cached, "a")
        assertEquals(0, worker.size)
        assertEquals(Color.GREEN, (cached.drawable as ColorDrawable).color)
        assertNotSame(first.drawable, cached.drawable)
    }

    @Test fun clearingIconsRejectsOldWorkWithoutDiscardingANewRequestForTheSameApp() {
        val worker = Queue()
        val main = Queue()
        var color = Color.RED
        val loader = AsyncIconLoader({ ColorDrawable(color) }, worker, { main.execute(it) })
        val target = image()
        loader.bind(target, "a")
        worker.next()
        loader.clear()
        color = Color.BLUE
        loader.bind(target, "a")
        main.next()
        assertNull(target.drawable)
        worker.next(); main.next()
        assertEquals(Color.BLUE, (target.drawable as ColorDrawable).color)
    }

    @Test fun missingOrRemovedAppsDoNotCrashOrRebindAnOldImage() {
        val worker = Queue()
        val main = Queue()
        val loader = AsyncIconLoader({ error("App removed") }, worker, { main.execute(it) })
        val target = image()
        target.setImageDrawable(ColorDrawable(Color.RED))
        loader.bind(target, "removed")
        assertNull(target.drawable)
        worker.next(); main.next()
        assertNull(target.drawable)
    }
}
