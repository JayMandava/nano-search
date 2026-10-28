package ai.nanosearch.launcher

import android.app.Application
import java.io.RandomAccessFile
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** DownloadManager is shadowed: no network or model weights are downloaded. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE, application = Application::class)
class ModelChoiceTest {
    private val gemma get() = ModelCatalog.all.single { it.id == "answer-gemma4-e2b" }
    private val qwen get() = ModelCatalog.all.single { it.id == "answer-qwen35-2b" }
    @Before fun setup() {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("nano", 0).edit().clear().commit()
        ModelStore.init(app)
        ModelStore.status.clear()
    }
    @After fun cleanup() {
        ModelStore.cancel(gemma)
        ModelStore.cancel(qwen)
        ModelStore.dir.deleteRecursively()
        ModelStore.status.clear()
    }
    private fun installQwenFixture() {
        // Sparse metadata-only fixture: length checks without allocating/copying model weights.
        RandomAccessFile(ModelStore.file(qwen.parts.single()), "rw").use { it.setLength(qwen.bytes) }
        ModelStore.select(qwen)
    }
    private fun requestGemma() {
        val p = gemma.parts.single()
        val tinyRequest = ModelEntry(gemma.id, gemma.slot, gemma.name, gemma.note, gemma.license,
            listOf(Part(p.file, p.url, p.sha256, 4)))
        ModelStore.download(tinyRequest, true)
    }
    @Test fun gemmaDownloadIsTheSingleDisplayedChoiceWhileQwenRemainsUsableUntilVerified() {
        installQwenFixture()
        assertEquals(qwen.id, ModelStore.displayChoice(Slot.ANSWER).id)
        requestGemma()
        assertTrue(ModelStore.active(gemma))
        assertFalse(ModelStore.active(qwen))
        assertEquals(gemma.id, ModelStore.displayChoice(Slot.ANSWER).id)
        assertEquals(qwen.id, ModelStore.selected(Slot.ANSWER).id)
        assertEquals(1, ModelStore.entries(Slot.ANSWER).count { it.id == ModelStore.displayChoice(Slot.ANSWER).id })
        assertEquals(gemma.id, RuntimeEnvironment.getApplication().getSharedPreferences("nano", 0).getString("sel_ANSWER", null))
        ModelStore.cancel(gemma)
        assertEquals(qwen.id, ModelStore.displayChoice(Slot.ANSWER).id)
    }
    @Test fun choosingOrDownloadingForAnsweringCannotChangeTheParserPreference() {
        installQwenFixture()
        val parser = ModelCatalog.all.single { it.id == "parser-qwen35-0.8b" }
        ModelStore.select(parser)
        requestGemma()
        assertEquals(parser.id, RuntimeEnvironment.getApplication().getSharedPreferences("nano", 0).getString("sel_PARSER", null))
        assertEquals(gemma.id, ModelStore.displayChoice(Slot.ANSWER).id)
    }
    @Test fun failedDownloadFallsBackWithoutSelectingTwoRows() {
        installQwenFixture(); requestGemma()
        ModelStore.status[gemma.file] = Transfer.Failed("Fixture failure")
        assertEquals(qwen.id, ModelStore.displayChoice(Slot.ANSWER).id)
        assertEquals(qwen.id, ModelStore.selected(Slot.ANSWER).id)
    }
    @Test fun catalogIdsAreUniqueAndOnlyTheSameQwenWeightsShareTransferIdentity() {
        assertEquals(ModelCatalog.all.size, ModelCatalog.all.map { it.id }.toSet().size)
        assertNotEquals(gemma.file, qwen.file)
        assertEquals(qwen.file, ModelCatalog.all.single { it.id == "parser-qwen35-2b" }.file)
        assertNotEquals(qwen.id, ModelCatalog.all.single { it.id == "parser-qwen35-2b" }.id)
        for (sameFile in ModelCatalog.all.flatMap { it.parts }.groupBy { it.file }.values) {
            assertEquals(1, sameFile.map { it.sha256 to it.bytes }.toSet().size)
        }
    }
}
