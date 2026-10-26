package ai.nanosearch.launcher.clocks

import ai.nanosearch.launcher.clocks.packs.FacePackStore
import ai.nanosearch.launcher.clocks.packs.PackTestFixtures
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.os.StrictMode
import android.os.strictmode.Violation
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.Calendar
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PackRendererIntegrationTest {
    private lateinit var fixture: PackIntegrationFixture
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    @Before fun setUp() { fixture = PackIntegrationFixture() }
    @After fun tearDown() { fixture.clean() }

    private class InspectableClockView(context: Context, faceId: String) : ClockFaceView(context, faceId) {
        private val fixedEpoch = Calendar.getInstance().apply { clear(); set(2020, Calendar.JANUARY, 1, 0, 0, 0) }.timeInMillis
        override fun drawFace(canvas: Canvas, bounds: RectF, epochMillis: Long, motion: Boolean) {
            super.drawFace(canvas, bounds, fixedEpoch, false)
        }
        fun currentRenderer(): ClockFaceRenderer = renderer
        fun releaseForTest() { renderer.release() }
    }

    @Test fun sameIdUpdateRefreshesExistingViewRendererPixelsAndAccessibleName() {
        val initial = fixture.install()
        ClockFaceRegistry.ensure(fixture.context)
        assertTrue(ClockFaces.all.any { it.id == initial.id && it.label == "Fixture dial" })
        val firstIdentity = ClockFaceRegistry.identity(fixture.context, initial.id)
        lateinit var view: InspectableClockView
        instrumentation.runOnMainSync { view = InspectableClockView(fixture.context, initial.id) }
        try {
            val beforeRenderer = renderer(view)
            assertEquals(initial.id, beforeRenderer.id)
            val before = paint(view)
            try { assertEquals(0xff124578.toInt(), before.getPixel(190, 190)) } finally { before.recycle() }
            val updated = fixture.install(fixture.bytes(2, "Updated fixture", "#FF963A2E"), update = true)
            assertEquals(initial.id, updated.id)
            assertNotEquals(initial.digest, updated.digest)
            assertNotEquals(firstIdentity, ClockFaceRegistry.identity(fixture.context, initial.id))
            instrumentation.runOnMainSync { view.faceId = initial.id }
            val afterRenderer = renderer(view)
            assertNotSame("Same-ID replacement must not retain stale art", beforeRenderer, afterRenderer)
            val after = paint(view)
            try { assertEquals(0xff963a2e.toInt(), after.getPixel(190, 190)) } finally { after.recycle() }
            instrumentation.runOnMainSync {
                assertEquals(initial.id, view.faceId)
                assertTrue(view.contentDescription.toString().startsWith("Updated fixture,"))
                assertFalse("A detached preview must not schedule frames", view.isFrameScheduled)
            }
        } finally { instrumentation.runOnMainSync { view.releaseForTest() } }
    }

    @Test fun quarantineFallsBackInTheExistingViewAndSuccessfulRetryRestoresTheSameSelection() {
        val pack = fixture.install()
        lateinit var view: InspectableClockView
        instrumentation.runOnMainSync { view = InspectableClockView(fixture.context, pack.id) }
        try {
            paint(view).recycle()
            val initialIdentity = ClockFaceRegistry.identity(fixture.context, pack.id)
            fixture.store.quarantine(pack.id, pack.digest)
            PackIntegrationFixture.await("Quarantine must remove the face from the live registry") {
                fixture.store.find(pack.id) == null && fixture.store.snapshot().unavailable.any { it.id == pack.id }
            }
            instrumentation.runOnMainSync { view.faceId = pack.id }
            assertEquals(ClockFaces.STEEL_ID, renderer(view).id)
            assertNotEquals(initialIdentity, ClockFaceRegistry.identity(fixture.context, pack.id))
            paint(view).recycle()
            instrumentation.runOnMainSync {
                assertEquals("The unavailable binding must remain recoverable", pack.id, view.faceId)
                assertTrue(view.contentDescription.toString().startsWith("Unavailable face — Steel Dial,"))
            }
            val recovered = fixture.store.retry(pack.id)
            assertEquals(pack.id, recovered.id)
            assertEquals(pack.digest, recovered.digest)
            instrumentation.runOnMainSync { view.faceId = pack.id }
            assertEquals(pack.id, renderer(view).id)
            val image = paint(view)
            try { assertEquals(0xff124578.toInt(), image.getPixel(190, 190)) } finally { image.recycle() }
            instrumentation.runOnMainSync { assertTrue(view.contentDescription.toString().startsWith("Fixture dial,")) }
        } finally { instrumentation.runOnMainSync { view.releaseForTest() } }
    }

    @Test fun missingPackBindingUsesSteelWithoutLosingTheSelectedInternalIdOrThrowing() {
        val absent = "pack-import-${"a".repeat(32)}"
        lateinit var view: InspectableClockView
        instrumentation.runOnMainSync { view = InspectableClockView(fixture.context, absent) }
        try {
            assertEquals(ClockFaces.STEEL_ID, renderer(view).id)
            paint(view).recycle()
            instrumentation.runOnMainSync {
                assertEquals(absent, view.faceId)
                assertTrue(view.contentDescription.toString().startsWith("Unavailable face — Steel Dial,"))
                assertFalse(view.isFrameScheduled)
            }
        } finally { instrumentation.runOnMainSync { view.releaseForTest() } }
    }

    @Test fun actualAtomicFileSurvivesUpdateAndRemovalAcrossFreshStoreInstances() {
        val first = fixture.install()
        val second = fixture.install(fixture.bytes(2, "Durable updated dial", "#FF805126"), update = true)
        assertEquals(first.id, second.id)
        val root = File(fixture.context.noBackupFilesDir, "clock-face-packs-v1")
        assertTrue(File(root, "registry.bin").isFile)
        val reloaded = FacePackStore(root, 14).also { it.initialize() }
        assertNull(reloaded.snapshot().error)
        assertEquals(listOf(second.id), reloaded.snapshot().packs.map { it.id })
        assertEquals(second.digest, reloaded.find(second.id)?.digest)
        assertEquals(2, reloaded.find(second.id)?.spec?.packVersion)
        reloaded.remove(second.id)
        val afterRemoval = FacePackStore(root, 14).also { it.initialize() }
        assertNull(afterRemoval.snapshot().error)
        assertTrue(afterRemoval.snapshot().packs.isEmpty())
        val newInstall = afterRemoval.importPack({ fixture.bytes().inputStream() })
        assertNotEquals(second.id, newInstall.id)
        assertTrue(newInstall.directory.canonicalPath.startsWith(fixture.context.noBackupFilesDir.canonicalPath + File.separator))
    }

    @Test fun actualPlatformPngCanBeInstalledAndRenderedAndCorruptionCannotRecover() {
        val png = PackTestFixtures.png(64, 64)
        val hash = PackIntegrationFixture.sha256(png)
        val manifest = PackTestFixtures.manifest
            .replace("\"requires\":[\"analog-v1\"]", "\"requires\":[\"analog-v1\",\"png-v1\"]")
            .replace("\"font\":\"system-sans\"", "\"font\":\"system-sans\",\"textureAsset\":\"texture\"")
            .replace("\"assets\":[]", "\"assets\":[{\"key\":\"texture\",\"path\":\"assets/texture.png\",\"sha256\":\"$hash\"}]")
        val bytes = PackTestFixtures.zip(listOf(
            PackTestFixtures.Entry("manifest.json", manifest.toByteArray()),
            PackTestFixtures.Entry("LICENSE.txt", "Original integration fixture, CC0-1.0".toByteArray()),
            PackTestFixtures.Entry("assets/texture.png", png),
        ))
        val pack = fixture.install(bytes)
        val midnight = Calendar.getInstance().apply { clear(); set(2020, Calendar.JANUARY, 1, 0, 0, 0) }.timeInMillis
        val preview = ClockFaces.preview(fixture.context, pack.id, 256, midnight)
        try { assertEquals(0xff345678.toInt(), preview.getPixel(190, 190)) } finally { preview.recycle() }
        File(pack.directory, "assets/texture.png").writeBytes(byteArrayOf(0, 1, 2, 3))
        val root = File(fixture.context.noBackupFilesDir, "clock-face-packs-v1")
        val reloaded = FacePackStore(root, 14).also { it.initialize() }
        assertNull("A corrupt pack is quarantined, not a broken whole registry", reloaded.snapshot().error)
        assertNull(reloaded.find(pack.id))
        assertTrue(reloaded.snapshot().unavailable.any { it.id == pack.id })
    }

    @Test fun preparedPackDrawDoesNotReadOrWriteDiskOnTheMainThread() {
        val pack = fixture.install()
        lateinit var view: InspectableClockView
        instrumentation.runOnMainSync { view = InspectableClockView(fixture.context, pack.id) }
        try {
            paint(view).recycle() // Complete worker decoding and framework text/font initialization first.
            val violations = CopyOnWriteArrayList<Violation>()
            val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
            try {
                instrumentation.runOnMainSync {
                    val previous = StrictMode.getThreadPolicy()
                    StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder()
                        .detectDiskReads().detectDiskWrites()
                        .penaltyListener(Executor { it.run() }) { violations += it }.build())
                    try { repeat(3) { view.draw(Canvas(bitmap)) } }
                    finally { StrictMode.setThreadPolicy(previous) }
                }
                assertTrue("Prepared clock frames must not perform disk I/O: $violations", violations.isEmpty())
            } finally { bitmap.recycle() }
        } finally { instrumentation.runOnMainSync { view.releaseForTest() } }
    }

    private fun renderer(view: InspectableClockView): ClockFaceRenderer {
        lateinit var current: ClockFaceRenderer
        instrumentation.runOnMainSync { current = view.currentRenderer() }
        return current
    }

    private fun paint(view: InspectableClockView): Bitmap {
        (renderer(view) as? DataClockFaceRenderer)?.awaitAssets()
        val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        instrumentation.runOnMainSync { view.layout(0, 0, 256, 256); view.draw(Canvas(bitmap)) }
        return bitmap
    }
}
