package ai.nanosearch.launcher.clocks

import ai.nanosearch.launcher.clocks.packs.FacePackStore
import ai.nanosearch.launcher.clocks.packs.InstalledFacePack
import ai.nanosearch.launcher.clocks.packs.PackTestFixtures
import android.content.Context
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import org.junit.Assert.*

/** All pack/preferences/photo mutations stay under a unique, disposable fixture context. */
internal class PackIntegrationContext(base: Context) : IsolatedClockContext(base, "pack-integration-${UUID.randomUUID()}") {
    private val isolatedNoBackup = File(filesDir, "no-backup").apply { mkdirs() }
    override fun getNoBackupFilesDir(): File = isolatedNoBackup
}

internal class PackIntegrationFixture {
    val target: Context = InstrumentationRegistry.getInstrumentation().targetContext
    val context = PackIntegrationContext(target)
    private val targetPreferencesBefore = target.getSharedPreferences("nano", Context.MODE_PRIVATE).all.toMap()
    private val targetPackFilesBefore = targetPackFiles()
    val preferences = context.getSharedPreferences("nano", Context.MODE_PRIVATE)
    val store = FacePackStore.get(context).also { it.initialize() }

    init {
        assertTrue("Real registry initialization must finish successfully", store.snapshot().ready)
        assertNull(store.snapshot().error)
        assertNotEquals(target.noBackupFilesDir.canonicalPath, context.noBackupFilesDir.canonicalPath)
    }

    fun bytes(version: Int = 1, name: String = "Fixture dial", color: String = "#FF124578"): ByteArray {
        val manifest = PackTestFixtures.manifest
            .replace("\"packVersion\":1", "\"packVersion\":$version")
            .replace("Original test dial", name)
            .replace("#FF101820", color)
            .replace("\"ticks\":\"minute\"", "\"ticks\":\"none\"")
            .replace("\"numerals\":\"quarters\"", "\"numerals\":\"none\"")
        return PackTestFixtures.zip(listOf(
            PackTestFixtures.Entry("manifest.json", manifest.toByteArray(Charsets.UTF_8)),
            PackTestFixtures.Entry("LICENSE.txt", "Original Nano integration fixture. CC0-1.0. No third-party artwork.".toByteArray()),
        ))
    }

    fun install(bytes: ByteArray = bytes(), update: Boolean = false): InstalledFacePack {
        val pack = store.importPack({ bytes.inputStream() }, allowUpdate = update,
            expectedUpdateDigest = if (update) sha256(bytes) else null)
        assertTrue("A pack must never escape the isolated no-backup directory",
            pack.directory.canonicalPath.startsWith(context.noBackupFilesDir.canonicalPath + File.separator))
        assertFalse(pack.directory.canonicalPath.startsWith(target.noBackupFilesDir.canonicalPath + File.separator))
        return pack
    }

    fun seedHome(): Pair<String, String> {
        val first = "b:${ClockFaces.CARBON_ID}:stable-chronograph-one"
        val second = "b:${ClockFaces.STEEL_ID}:stable-clock-two"
        assertTrue(preferences.edit().putString("pages", "0,work")
            .putString("pagename_work", "Work")
            .putString("widgets", "$first,42").putString("widgets_work", second)
            .putString("wsize_$first", "240,260").putString("wpos_$first", "11,29")
            .putString("wsize_$second", "180,210").putString("wpos_$second", "71,93")
            .putString("lockclock_face", ClockFaces.CARBON_ID)
            .putString("lockclock_bg", "photo").putString("lockclock_pos", "low")
            .putBoolean("clocks_reduced_motion", true).putString("unrelated", "retain me").commit())
        return first to second
    }

    fun clean() {
        // Observers/leases must be closed by each test before fixture deletion.
        context.clean()
        assertEquals("Pack tests must not change the actual app's preferences", targetPreferencesBefore,
            target.getSharedPreferences("nano", Context.MODE_PRIVATE).all.toMap())
        assertEquals("Pack tests must not write the actual app's pack directory", targetPackFilesBefore, targetPackFiles())
    }

    private fun targetPackFiles(): Map<String, String> {
        val root = File(target.noBackupFilesDir, "clock-face-packs-v1")
        if (!root.exists()) return emptyMap()
        return root.walkTopDown().filter { it.isFile }.associate { it.relativeTo(root).path to sha256(it.readBytes()) }
    }

    companion object {
        fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        fun await(message: String, condition: () -> Boolean) {
            val deadline = SystemClock.uptimeMillis() + 30_000
            while (!condition() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(20)
            assertTrue(message, condition())
        }
    }
}
