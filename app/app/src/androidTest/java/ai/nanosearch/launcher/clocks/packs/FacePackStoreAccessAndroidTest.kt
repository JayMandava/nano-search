package ai.nanosearch.launcher.clocks.packs

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class FacePackStoreAccessAndroidTest {
    private class CountingContext(base: Context, private val files: File) : ContextWrapper(base) {
        var directoryReads = 0
        override fun getApplicationContext(): Context = this
        override fun getNoBackupFilesDir(): File { directoryReads++; return files }
    }

    @Test fun repeatedStoreAccessNeverInvokesDirectoryAccessorAndIsolatedContextsStayIsolated() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(target.cacheDir, "pack-access-${UUID.randomUUID()}").apply { mkdirs() }
        val first = CountingContext(target, File(root, "first").apply { mkdirs() })
        val alias = CountingContext(target, File(root, "first"))
        val separate = CountingContext(target, File(root, "second").apply { mkdirs() })
        val a = FacePackStore.get(first)
        val b = FacePackStore.get(alias)
        val c = FacePackStore.get(separate)
        try {
            assertSame(a, b); assertNotSame(a, c)
            assertEquals(1, first.directoryReads); assertEquals(1, alias.directoryReads); assertEquals(1, separate.directoryReads)
            repeat(1000) {
                assertSame(a, FacePackStore.get(first)); assertSame(a, FacePackStore.get(alias)); assertSame(c, FacePackStore.get(separate))
            }
            assertEquals(1, first.directoryReads); assertEquals(1, alias.directoryReads); assertEquals(1, separate.directoryReads)
        } finally {
            // Await finite background initialization before deleting only this disposable fixture.
            val deadline = System.nanoTime() + 5_000_000_000L
            while ((!a.snapshot().ready || !c.snapshot().ready) && System.nanoTime() < deadline) Thread.sleep(10)
            assertTrue(a.snapshot().ready); assertTrue(c.snapshot().ready)
            root.deleteRecursively()
        }
    }
}
