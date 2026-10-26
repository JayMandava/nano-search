package ai.nanosearch.launcher.clocks.packs

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** Real parser/archive/filesystem transactions; only Android fsync/index and native decode are injected.
 * Android decoder/AtomicFile coverage belongs to instrumentation, not a mocked JVM success claim. */
class FacePackStoreTest {
    private val root = Files.createTempDirectory("nano-store-").toFile().canonicalFile
    private val index = TestIndex(File(root, "registry.bin"))
    private val validation = PortableValidation()
    @After fun clean() { root.deleteRecursively() }
    private class TestIndex(private val file: File) : FacePackIndexIO {
        var failBeforeWrite = false
        var failAfterWrite = false
        var readFailure: Throwable? = null
        override fun read(): ByteArray? {
            readFailure?.let { throw it }
            return file.takeIf { it.exists() }?.readBytes()
        }
        override fun write(bytes: ByteArray) {
            if (failBeforeWrite) throw IOException("Simulated disk full before pointer commit")
            val pending = File(file.parentFile, "registry.new")
            FileOutputStream(pending).use { it.write(bytes); it.fd.sync() }
            Files.move(pending.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            if (failAfterWrite) throw IOException("Simulated fsync error after pointer commit")
        }
        override fun syncDirectory(directory: File) { }
    }
    private class PortableValidation : FacePackValidation {
        var decodeCount = 0
        var decodeFailure = false
        var readFailure: Throwable? = null
        var decodeThrowable: Throwable? = null
        override fun extract(zip: File, destination: File, appVersionCode: Int, cancelled: () -> Boolean) = FacePackArchive.validateAndExtract(zip, destination, appVersionCode, cancelled)
        override fun installed(zip: File, payload: File, appVersionCode: Int, digest: String): ValidatedFacePack {
            readFailure?.let { throw it }
            return FacePackArchive.validateInstalled(zip, payload, appVersionCode, digest)
        }
        override fun androidDecode(pack: ValidatedFacePack, cancelled: () -> Boolean) {
            decodeCount++
            decodeThrowable?.let { throw it }
            if (decodeFailure) throw IOException("Simulated platform decoder failure")
        }
    }
    private fun store(fault: (String) -> Unit = {}) = FacePackStore(root, 14, index, validation, fault = fault)
    private fun bytes(version: Int = 1, id: String = "test-original", name: String = "Original test dial") = ArchiveTestFixtures.zip(listOf(
        ArchiveTestFixtures.Entry("manifest.json", PackSpecFixtures.changed("packVersion" to version.toString(), "packId" to PackSpecFixtures.quote(id), "name" to PackSpecFixtures.quote(name)).toByteArray()),
        ArchiveTestFixtures.Entry("LICENSE.txt", ArchiveTestFixtures.license),
    ))
    private fun import(store: FacePackStore, bytes: ByteArray = bytes(), update: Boolean = false, digest: String? = null) = store.importPack({ bytes.inputStream() }, update, digest)
    private fun hash(bytes: ByteArray) = ArchiveTestFixtures.sha256(bytes)
    private fun reject(reason: FacePackFailure, action: () -> Unit) {
        try { action(); fail("Expected $reason") } catch (e: FacePackOperationException) { assertEquals(reason, e.reason) }
    }
    private fun revisions() = root.listFiles()!!.filter { it.name.startsWith("rev-") }
    private fun assertNoStaging() = assertTrue(root.listFiles()!!.none { it.name.startsWith(".stage-") })
    private fun await(message: String, predicate: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (!predicate() && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue(message, predicate())
    }
    @Test fun initializationIsExplicitAndImportPublishesImmutableValidatedRevision() {
        val store = store(); assertFalse(store.snapshot().ready); store.initialize(); assertTrue(store.snapshot().ready)
        val pack = import(store)
        assertEquals(FacePackSource.IMPORTED, pack.source); assertTrue(pack.id.startsWith("pack-import-")); assertEquals(hash(bytes()), pack.digest)
        assertEquals(1L, store.snapshot().generation); assertEquals(1, validation.decodeCount)
        assertTrue(File(pack.directory, "manifest.json").isFile); assertTrue(File(pack.directory.parentFile, "source.zip").isFile)
        assertNoStaging(); assertEquals(1, revisions().size)
        try { (store.snapshot().packs as MutableList).clear(); fail("Snapshot is mutable") } catch (_: UnsupportedOperationException) { }
    }
    @Test fun identicalReimportIsIdempotentAndDoesNotSelectOrIncrementGeneration() {
        val store = store(); val first = import(store); val second = import(store)
        assertEquals(first, second); assertEquals(1L, store.snapshot().generation); assertEquals(1, revisions().size); assertNoStaging()
    }
    @Test fun explicitUpdateBindsReviewedDigestKeepsStableIdAndRejectsDowngradeOrConflict() {
        val store = store(); val first = import(store); val candidate = bytes(2)
        try { import(store, candidate); fail("Needs confirmation") } catch (e: FacePackUpdateRequiredException) {
            assertEquals(first.id, e.existingId); assertEquals(2, e.version); assertEquals(hash(candidate), e.digest)
        }
        assertEquals(first, store.find(first.id)); assertEquals(1, revisions().size)
        reject(FacePackFailure.VERIFICATION) { import(store, candidate, true, "0".repeat(64)) }
        val updated = import(store, candidate, true, hash(candidate))
        assertEquals(first.id, updated.id); assertEquals(2, updated.spec.packVersion); assertEquals(2L, store.snapshot().generation)
        reject(FacePackFailure.CONFLICT) { import(store, bytes(), true, hash(bytes())) }
        reject(FacePackFailure.CONFLICT) { import(store, bytes(2, name = "Changed bytes"), true, hash(bytes(2, name = "Changed bytes"))) }
        assertEquals(updated, store.find(first.id)); assertNoStaging()
    }
    @Test fun providerChangingAfterConfirmationCannotReplaceReviewedPack() {
        val store = store(); val first = import(store)
        reject(FacePackFailure.VERIFICATION) { import(store, bytes(2, name = "Different candidate"), true, hash(bytes(2))) }
        assertEquals(first, store.find(first.id)); assertNoStaging()
    }
    @Test fun rendererLeaseKeepsOldRevisionAliveAcrossUpdateUntilReleased() {
        val store = store(); val first = import(store); val lease = store.acquire(first.id)!!
        val candidate = bytes(2); val updated = import(store, candidate, true, hash(candidate))
        assertEquals(first, lease.pack); assertTrue(first.directory.isDirectory); assertEquals(2, revisions().size)
        assertEquals(updated, store.acquire(first.id)!!.also { it.close() }.pack)
        lease.close(); lease.close()
        await("Old revision should be collected after last lease", { !first.directory.exists() })
        assertTrue(updated.directory.isDirectory)
    }
    @Test fun removalCommitsMissingResolutionBeforeCleanupAndReimportUsesFreshIdentity() {
        val store = store(); val first = import(store); val lease = store.acquire(first.id)!!
        var callback = false
        store.remove(first.id) {
            assertNull(store.find(first.id)); assertTrue(first.directory.exists()); callback = true
        }
        assertTrue(callback); assertNull(store.acquire(first.id)); assertTrue(first.directory.exists())
        val reinstalled = import(store)
        assertNotEquals(first.id, reinstalled.id); assertNull(store.find(first.id)); assertEquals(1, store.snapshot().packs.size)
        lease.close(); await("Removed leased revision released", { !first.directory.exists() })
        reject(FacePackFailure.CONFLICT) { store.remove("steeldial") }
    }
    @Test fun invalidArchiveOrNativeDecodeFailureNeverPublishesAndOldVersionSurvives() {
        val store = store(); val first = import(store)
        try { import(store, "not a zip".toByteArray()); fail("Bad ZIP") } catch (_: IOException) { }
        validation.decodeFailure = true
        try { import(store, bytes(2), true, hash(bytes(2))); fail("Decoder failure") } catch (_: IOException) { }
        assertEquals(first, store.find(first.id)); assertEquals(1, revisions().size); assertNoStaging()
    }
    @Test fun copyLimitsAreActualBytesAndCancellationClosesProviderAndCleansStaging() {
        val store = store(); val first = import(store)
        reject(FacePackFailure.INVALID_PACK) { import(store, ByteArray(FacePackLimits.MAX_ZIP_BYTES.toInt() + 1)) }
        var closed = false; var cancelled = false
        val stream = object : java.io.ByteArrayInputStream(bytes()) { override fun close() { closed = true; super.close() } }
        reject(FacePackFailure.CANCELLED) { store.importPack({ stream }, cancelled = { cancelled }, progress = { cancelled = true }) }
        assertTrue(closed); assertNoStaging(); assertEquals(first, store.find(first.id))
    }
    @Test fun cancelledBlockedProviderOpenReceivesAbortSignal() {
        val store = store(); val cancelled = AtomicBoolean(); val started = CountDownLatch(1); val aborted = CountDownLatch(1)
        val canceller = thread { assertTrue(started.await(2, TimeUnit.SECONDS)); cancelled.set(true) }
        reject(FacePackFailure.CANCELLED) {
            store.importPack({ started.countDown(); if (!aborted.await(3, TimeUnit.SECONDS)) error("No provider abort"); throw IOException("Provider canceled") }, cancelled = cancelled::get, abortInput = { aborted.countDown() })
        }
        canceller.join(); assertNoStaging(); assertTrue(store.snapshot().packs.isEmpty())
    }
    @Test fun startupRevalidatesPayloadQuarantinesCorruptionAndRetryRequiresActualRepair() {
        val store = store(); val first = import(store); val manifest = File(first.directory, "manifest.json"); val original = manifest.readBytes()
        manifest.writeText("corrupted")
        val restored = store(); restored.initialize()
        assertNull(restored.find(first.id)); assertEquals(first.id, restored.snapshot().unavailable.single().id)
        assertTrue(first.directory.exists())
        try { restored.retry(first.id); fail("Corrupt retry must fail") } catch (_: IOException) { }
        manifest.writeBytes(original)
        assertEquals(first.id, restored.retry(first.id).id); assertTrue(restored.snapshot().unavailable.isEmpty())
    }
    @Test fun startupRemovesOnlyProvenOrphansAndLeavesSymlinkTargetUntouched() {
        val first = import(store()); val orphan = File(root, "rev-${"0".repeat(32)}").apply { mkdir(); resolve("stale").writeText("old") }
        val stage = File(root, ".stage-orphan").apply { mkdir(); resolve("partial").writeText("partial") }
        val outside = Files.createTempDirectory("nano-sentinel-").toFile().apply { resolve("sentinel").writeText("keep") }
        try {
            Files.createSymbolicLink(File(root, ".stage-link").toPath(), outside.toPath())
            val restored = store(); restored.initialize()
            assertEquals(first.id, restored.snapshot().packs.single().id); assertFalse(orphan.exists()); assertFalse(stage.exists())
            assertEquals("keep", File(outside, "sentinel").readText())
        } finally { outside.deleteRecursively() }
    }
    @Test fun corruptRegistryFailsClosedAndPreservesAllRecoverableRevisions() {
        val first = import(store()); File(root, "registry.bin").writeBytes(byteArrayOf(1, 2, 3))
        val restored = store(); restored.initialize()
        assertTrue(restored.snapshot().ready); assertNotNull(restored.snapshot().error); assertTrue(restored.snapshot().packs.isEmpty())
        assertTrue(first.directory.exists()); reject(FacePackFailure.REGISTRY) { import(restored) }
    }
    @Test fun eachInstallCrashBoundaryRecoversOldOrCompleteNewRevision() {
        for (boundary in listOf("before-revision-move", "after-revision-move", "before-index-write", "after-index-write")) {
            root.listFiles()!!.forEach { it.deleteRecursively() }
            val first = import(store()); val candidate = bytes(2)
            val crashing = store { if (it == boundary) throw IOException("Simulated crash: $boundary") }
            try { import(crashing, candidate, true, hash(candidate)); fail("Crash not injected") } catch (_: IOException) { }
            val recovered = store(); recovered.initialize(); val active = recovered.snapshot().packs.single()
            assertEquals(first.id, active.id); assertEquals(if (boundary == "after-index-write") 2 else 1, active.spec.packVersion)
            assertTrue(active.directory.isDirectory); assertEquals(1, revisions().size); assertNoStaging()
        }
    }
    @Test fun uncertainPointerWriteFailurePreservesNewAndOldUntilRestartResolvesActualCommit() {
        for (afterWrite in listOf(false, true)) {
            root.listFiles()!!.forEach { it.deleteRecursively() }; index.failBeforeWrite = false; index.failAfterWrite = false
            val store = store(); val first = import(store); val candidate = bytes(2)
            index.failBeforeWrite = !afterWrite; index.failAfterWrite = afterWrite
            try { import(store, candidate, true, hash(candidate)); fail("Pointer failure not injected") } catch (_: IOException) { }
            assertEquals(first, store.find(first.id)); assertEquals(2, revisions().size); assertNotNull(store.snapshot().error)
            reject(FacePackFailure.REGISTRY) { store.remove(first.id) }
            index.failBeforeWrite = false; index.failAfterWrite = false
            val recovered = store(); recovered.initialize()
            assertEquals(if (afterWrite) 2 else 1, recovered.snapshot().packs.single().spec.packVersion)
            assertEquals(1, revisions().size)
        }
    }
    @Test fun quotasCountRetainedAndTemporaryContentBeforeCopyStarts() {
        val store = store(); val first = import(store)
        val occupied = File(root, "retained-other-data")
        RandomAccessFile(occupied, "rw").use { it.setLength(FacePackLimits.MAX_STORAGE_BYTES - FacePackLimits.MAX_ZIP_BYTES - FacePackLimits.MAX_EXPANDED_BYTES) }
        var opened = false
        reject(FacePackFailure.STORAGE) { store.importPack({ opened = true; bytes().inputStream() }) }
        assertFalse(opened); assertEquals(first, store.find(first.id)); assertNoStaging()
    }
    @Test fun competingInstallIsRejectedWithoutDisturbingFirstCopy() {
        val store = store(); store.initialize(); val opened = CountDownLatch(1); val release = CountDownLatch(1)
        var failure: Throwable? = null
        val worker = thread { try { store.importPack({ opened.countDown(); release.await(3, TimeUnit.SECONDS); bytes().inputStream() }) } catch (e: Throwable) { failure = e } }
        assertTrue(opened.await(2, TimeUnit.SECONDS))
        reject(FacePackFailure.BUSY) { import(store) }
        release.countDown(); worker.join(3000); assertNull(failure); assertFalse(worker.isAlive)
        assertEquals(1, store.snapshot().packs.size); assertNoStaging()
    }
    @Test fun incompatiblePackHasTypedErrorAndNeverChangesInstalledCollection() {
        val store = store(); val first = import(store)
        val incompatible = ArchiveTestFixtures.zip(listOf(
            ArchiveTestFixtures.Entry("manifest.json", PackSpecFixtures.changed("minAppVersionCode" to "999").toByteArray()),
            ArchiveTestFixtures.Entry("LICENSE.txt", ArchiveTestFixtures.license),
        ))
        reject(FacePackFailure.INCOMPATIBLE) { import(store, incompatible) }
        assertEquals(first, store.find(first.id)); assertNoStaging()
    }
    @Test fun cancellationDuringFinishingReportsActualCommittedResultNotFalseRollback() {
        val store = store(); var cancelled = false
        val installed = store.importPack({ bytes().inputStream() }, cancelled = { cancelled }, progress = {
            if (it.stage == FacePackStage.INSTALLING) cancelled = true
        })
        assertTrue(cancelled); assertEquals(installed, store.find(installed.id)); assertNoStaging()
        val restored = store(); restored.initialize(); assertEquals(installed.id, restored.snapshot().packs.single().id)
    }
    @Test fun quarantineWaitsForWriterInsteadOfDroppingTheFailure() {
        val store = store(); val first = import(store)
        val opened = CountDownLatch(1); val release = CountDownLatch(1)
        var failure: Throwable? = null
        val worker = thread { try { store.importPack({ opened.countDown(); release.await(3, TimeUnit.SECONDS); bytes(id = "second-original").inputStream() }) } catch (e: Throwable) { failure = e } }
        assertTrue(opened.await(2, TimeUnit.SECONDS))
        store.quarantine(first.id, first.digest)
        release.countDown(); worker.join(3000)
        assertNull(failure)
        await("Quarantine should not be dropped when writer is busy", { store.snapshot().unavailable.any { it.id == first.id } })
        assertNull(store.find(first.id)); assertEquals("second-original", store.snapshot().packs.single().spec.packId)
        assertTrue(first.directory.isDirectory)
    }
    @Test fun collectionCountQuotaIncludesEveryInstalledIdentity() {
        val store = store()
        repeat(FacePackLimits.MAX_PACKS) { import(store, bytes(id = "original-$it")) }
        reject(FacePackFailure.STORAGE) { import(store, bytes(id = "one-too-many")) }
        assertEquals(FacePackLimits.MAX_PACKS, store.snapshot().packs.size); assertNoStaging()
    }
    @Test fun transientStartupReadFailureDoesNotWriteQuarantineAndRetryRestoresThePack() {
        val first = import(store()); val pointer = index.read()!!
        validation.readFailure = IOException("Temporary private storage read failure")
        val restored = store(); restored.initialize()
        assertTrue(restored.snapshot().ready); assertNotNull(restored.snapshot().error)
        assertTrue(restored.snapshot().packs.isEmpty()); assertEquals(first.id, restored.snapshot().unavailable.single().id)
        assertArrayEquals(pointer, index.read()); assertTrue(first.directory.isDirectory)
        validation.readFailure = null
        assertEquals(first.id, restored.retry(first.id).id)
        assertTrue(restored.snapshot().unavailable.isEmpty()); assertNull(restored.snapshot().error)
    }
    @Test fun nativeDecodeOutOfMemoryIsReadyRetryableAndNeverDurablyQuarantined() {
        val first = import(store()); val pointer = index.read()!!
        validation.decodeThrowable = OutOfMemoryError("Simulated bounded native allocation failure")
        val restored = store(); restored.initialize()
        assertTrue(restored.snapshot().ready); assertEquals(first.id, restored.snapshot().unavailable.single().id)
        assertArrayEquals(pointer, index.read()); assertTrue(first.directory.isDirectory)
        reject(FacePackFailure.RESOURCE) { restored.retry(first.id) }
        assertArrayEquals(pointer, index.read())
        validation.decodeThrowable = null
        assertEquals(first.id, restored.retry(first.id).id); assertNull(restored.snapshot().error)
    }
    @Test fun typedResourceFailureIsNotMistakenForInvalidBytes() {
        val first = import(store()); val pointer = index.read()!!
        validation.decodeThrowable = FacePackResourceException("Not enough memory", OutOfMemoryError("synthetic"))
        val restored = store(); restored.initialize()
        assertTrue(restored.snapshot().ready); assertEquals(first.id, restored.snapshot().unavailable.single().id)
        assertArrayEquals(pointer, index.read())
        reject(FacePackFailure.RESOURCE) { restored.retry(first.id) }
        validation.decodeThrowable = null
        // A new process also retries naturally: no persistent quarantine flag was written.
        val nextProcess = store(); nextProcess.initialize()
        assertEquals(first.id, nextProcess.snapshot().packs.single().id); assertTrue(nextProcess.snapshot().unavailable.isEmpty())
        assertArrayEquals(pointer, index.read())
    }
    @Test fun transientIndexFailureAndUnexpectedOomNeverLeaveAsyncLoadingStuck() {
        val first = import(store()); val pointer = index.read()!!
        for (failure in listOf(IOException("Temporary registry read failure"), OutOfMemoryError("Temporary index allocation failure"))) {
            index.readFailure = failure
            val restored = store(); restored.requestLoad()
            await("Initialization must reach a ready error state", { restored.snapshot().ready })
            assertNotNull(restored.snapshot().error); assertTrue(first.directory.isDirectory)
            index.readFailure = null
            await("A later async load must retry instead of retaining loadRequested", {
                restored.requestLoad(); restored.snapshot().packs.any { it.id == first.id }
            })
            assertNull(restored.snapshot().error); assertArrayEquals(pointer, index.read())
        }
    }
}
