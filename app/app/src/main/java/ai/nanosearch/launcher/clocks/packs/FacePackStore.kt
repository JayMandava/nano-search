package ai.nanosearch.launcher.clocks.packs

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.system.Os
import android.system.OsConstants
import android.util.AtomicFile
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.SimpleFileVisitor
import java.nio.file.FileVisitResult
import java.nio.file.attribute.BasicFileAttributes
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

enum class FacePackSource { IMPORTED, CURATED }

data class InstalledFacePack(
    val id: String,
    val source: FacePackSource,
    val spec: FacePackSpec,
    val digest: String,
    /** Validated payload only; archive/source and mutable registry are outside this directory. */
    val directory: File,
    val payloadBytes: Long,
    val licenseText: String,
)

data class UnavailableFacePack(val id: String, val name: String, val source: FacePackSource, val reason: String)
data class FacePackSnapshot(
    val generation: Long,
    val packs: List<InstalledFacePack>,
    val ready: Boolean,
    val error: String? = null,
    val unavailable: List<UnavailableFacePack> = emptyList(),
)

class FacePackLease internal constructor(val pack: InstalledFacePack, private val release: () -> Unit) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    override fun close() { if (closed.compareAndSet(false, true)) release() }
}

/** Injectable filesystem boundary: production uses Android AtomicFile, tests can inject faulting writers. */
internal interface FacePackIndexIO {
    fun read(): ByteArray?
    fun write(bytes: ByteArray)
    fun syncDirectory(directory: File)
}

internal class AndroidFacePackIndexIO(file: File) : FacePackIndexIO {
    private val atomic = AtomicFile(file)
    override fun read(): ByteArray? {
        if (!atomic.baseFile.exists() && !File(atomic.baseFile.path + ".bak").exists()) return null
        return atomic.openRead().use { input ->
            val bytes = input.readNBytes(MAX_INDEX_BYTES + 1)
            if (bytes.size > MAX_INDEX_BYTES) throw IOException("Clock face registry exceeds its limit")
            bytes
        }
    }
    override fun write(bytes: ByteArray) {
        require(bytes.size <= MAX_INDEX_BYTES)
        var stream: FileOutputStream? = null
        try {
            stream = atomic.startWrite()
            stream.write(bytes)
            // AtomicFile logs some platform failures instead of throwing. Force the data ourselves
            // and verify its published bytes, so an unsuccessful rename is never reported as saved.
            stream.fd.sync()
            atomic.finishWrite(stream)
            stream = null
            val published = atomic.openRead().use { it.readNBytes(MAX_INDEX_BYTES + 1) }
            if (!published.contentEquals(bytes)) throw IOException("Clock face registry publication could not be verified")
            syncDirectory(atomic.baseFile.parentFile!!)
        } catch (failure: Throwable) {
            stream?.let { runCatching { atomic.failWrite(it) }.exceptionOrNull()?.let(failure::addSuppressed) }
            throw failure
        }
    }
    override fun syncDirectory(directory: File) {
        val before = Os.lstat(directory.path)
        if (!OsConstants.S_ISDIR(before.st_mode)) throw IOException("Invalid face storage directory")
        val fd = Os.open(directory.path, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW, 0)
        try {
            val opened = Os.fstat(fd)
            if (!OsConstants.S_ISDIR(opened.st_mode) || before.st_dev != opened.st_dev || before.st_ino != opened.st_ino) {
                throw IOException("Face storage directory changed while saving")
            }
            Os.fsync(fd)
        } finally { Os.close(fd) }
    }
}

internal interface FacePackValidation {
    fun extract(zip: File, destination: File, appVersionCode: Int, cancelled: () -> Boolean): ValidatedFacePack
    fun installed(zip: File, payload: File, appVersionCode: Int, digest: String): ValidatedFacePack
    fun androidDecode(pack: ValidatedFacePack, cancelled: () -> Boolean)
}

internal object AndroidFacePackValidation : FacePackValidation {
    override fun extract(zip: File, destination: File, appVersionCode: Int, cancelled: () -> Boolean) =
        FacePackArchive.validateAndExtract(zip, destination, appVersionCode, cancelled)
    override fun installed(zip: File, payload: File, appVersionCode: Int, digest: String) =
        FacePackArchive.validateInstalled(zip, payload, appVersionCode, digest)
    override fun androidDecode(pack: ValidatedFacePack, cancelled: () -> Boolean) {
        FacePackArchive.validateAndroidImages(pack, cancelled)
        ai.nanosearch.launcher.clocks.DataClockFaceRenderer.validate(pack, cancelled)
    }
}

/**
 * Single foreground writer; immutable revision directories and one durable AtomicFile pointer.
 * Rendering reads only the volatile immutable snapshot. All decoding, validation, copying and GC
 * execute on workers. The root is noBackupFilesDir: restored widget bindings safely resolve missing
 * pack IDs to Steel, and imports need reimport rather than leaking assets into a device backup.
 */
class FacePackStore internal constructor(
    private val root: File,
    private val appVersionCode: Int,
    private val index: FacePackIndexIO = AndroidFacePackIndexIO(File(root, "registry.bin")),
    private val validation: FacePackValidation = AndroidFacePackValidation,
    private val downloader: FacePackDownloader = FacePackDownloader(),
    private val catalog: List<FacePackCatalogEntry> = FacePackCatalog.entries,
    private val notifyOnMain: ((() -> Unit) -> Unit) = { it() },
    private val fault: (String) -> Unit = {},
) {
    private data class Row(
        val id: String, val source: FacePackSource, val logicalId: String, val version: Int,
        val name: String, val digest: String, val revision: String, val quarantined: Boolean = false,
    )
    private data class Registry(val generation: Long, val rows: List<Row>, val removed: List<String>)

    private val operationLock = ReentrantLock()
    private val stateLock = Any()
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "clock-face-store").apply { isDaemon = true } }
    private val loadRequested = AtomicBoolean(false)
    private val leases = mutableMapOf<String, Int>()
    // Retryable runtime availability is deliberately not written into the durable trust registry.
    private val transientUnavailable = mutableSetOf<String>()
    @Volatile private var current = FacePackSnapshot(0, emptyList(), false)
    private var registry = Registry(0, emptyList(), emptyList())
    @Volatile private var initialized = false
    @Volatile private var registryHealthy = true

    fun snapshot(): FacePackSnapshot = current
    fun find(id: String): InstalledFacePack? = current.packs.firstOrNull { it.id == id }
    fun observe(listener: () -> Unit): AutoCloseable {
        listeners += listener
        notifyOnMain { if (listener in listeners) runCatching(listener) }
        requestLoad()
        return AutoCloseable { listeners -= listener }
    }
    fun requestLoad() {
        if (!initialized && loadRequested.compareAndSet(false, true)) worker.execute {
            try { initialize() } finally { loadRequested.set(false) }
        }
    }

    /** Background-only. An unreadable registry fails closed and preserves files for recovery. */
    fun initialize() {
        if (initialized) return
        operationLock.withLock {
            if (initialized) return
            try {
                ensureRoot()
                val persisted = index.read()?.let { bytes ->
                    try { decodeRegistry(bytes) }
                    catch (e: IOException) { throw FacePackException("Invalid installed face registry", e) }
                } ?: Registry(0, emptyList(), emptyList())
                val loaded = mutableListOf<InstalledFacePack>()
                val unavailable = mutableSetOf<String>()
                var changed = false
                val rows = persisted.rows.map { row ->
                    if (row.quarantined) row else try {
                        val pack = validateRow(row)
                        loaded += pack
                        row
                    } catch (_: FacePackException) {
                        changed = true
                        row.copy(quarantined = true)
                    } catch (_: Exception) {
                        unavailable += row.id
                        row
                    } catch (_: OutOfMemoryError) {
                        // Defensive boundary in addition to the bounded native decoder wrapper.
                        unavailable += row.id
                        row
                    }
                }
                val next = Registry(persisted.generation + if (changed) 1 else 0, rows, persisted.removed)
                if (changed) index.write(encodeRegistry(next))
                synchronized(stateLock) {
                    registry = next
                    transientUnavailable.clear(); transientUnavailable += unavailable
                    publishLocked(loaded)
                }
                registryHealthy = true
                initialized = true
                runCatching { collectGarbage() }
            } catch (_: FacePackException) {
                failInitialization(retryable = false)
            } catch (_: Exception) {
                failInitialization(retryable = true)
            } catch (_: OutOfMemoryError) {
                failInitialization(retryable = true)
            }
            notifyListeners()
        }
    }

    private fun failInitialization(retryable: Boolean) {
        registryHealthy = false
        // A fresh initialize/requestLoad may retry a transient index/root failure. Corrupt registry
        // bytes fail closed until restart/recovery; neither case is left permanently "loading".
        initialized = !retryable
        synchronized(stateLock) {
            current = FacePackSnapshot(current.generation + 1, emptyList(), true,
                if (retryable) "Installed faces are temporarily unavailable. Files were preserved; retry when memory and storage are available."
                else "Installed faces could not be loaded. Existing files were preserved; restart before importing.")
        }
    }

    fun acquire(id: String): FacePackLease? = synchronized(stateLock) {
        val pack = current.packs.firstOrNull { it.id == id } ?: return@synchronized null
        val revision = pack.directory.parentFile!!.name
        leases[revision] = (leases[revision] ?: 0) + 1
        FacePackLease(pack) {
            synchronized(stateLock) {
                val n = (leases[revision] ?: 1) - 1
                if (n == 0) leases.remove(revision) else leases[revision] = n
            }
            worker.execute { operationLock.withLock { if (registryHealthy && initialized) runCatching { collectGarbage() } } }
        }
    }

    /** Serialize explicit selection against pointer replacement/removal; never call with slow work. */
    fun <T> withAvailable(id: String, selection: (InstalledFacePack) -> T): T = synchronized(stateLock) {
        val pack = current.packs.firstOrNull { it.id == id }
            ?: throw FacePackOperationException(FacePackFailure.CONFLICT, "This face is no longer available")
        selection(pack)
    }

    fun importPack(
        openInput: () -> InputStream,
        allowUpdate: Boolean = false,
        expectedUpdateDigest: String? = null,
        cancelled: () -> Boolean = { false },
        progress: (FacePackProgress) -> Unit = {},
        abortInput: () -> Unit = {},
    ): InstalledFacePack = install(FacePackSource.IMPORTED, null, allowUpdate, expectedUpdateDigest, cancelled, progress) { zip ->
        copyProvider(openInput, zip, cancelled, progress, abortInput)
    }

    fun downloadPack(
        entry: FacePackCatalogEntry,
        allowUpdate: Boolean = false,
        cancelled: () -> Boolean = { false },
        progress: (FacePackProgress) -> Unit = {},
    ): InstalledFacePack {
        if (entry !in catalog) throw FacePackOperationException(FacePackFailure.UNSUPPORTED_SOURCE, "This face is not in the app's trusted catalog")
        return install(FacePackSource.CURATED, entry, allowUpdate, entry.sha256, cancelled, progress) { zip ->
            downloader.download(entry, zip, cancelled, progress)
        }
    }

    private fun install(
        source: FacePackSource, catalogEntry: FacePackCatalogEntry?, allowUpdate: Boolean,
        expectedUpdateDigest: String?, cancelled: () -> Boolean,
        progress: (FacePackProgress) -> Unit, receive: (File) -> Unit,
    ): InstalledFacePack = foreground {
        checkCancel(cancelled)
        collectGarbage()
        reserveStaging()
        val staging = File(root, ".stage-${uuid()}")
        Files.createDirectory(staging.toPath())
        var moved: File? = null
        var committed = false
        try {
            val zip = File(staging, "source.zip")
            receive(zip)
            checkCancel(cancelled)
            report(progress, FacePackProgress(FacePackStage.CHECKING, zip.length(), zip.length()))
            val checked = validation.extract(zip, File(staging, "payload"), appVersionCode, cancelled)
            validation.androidDecode(checked, cancelled)
            if (catalogEntry != null && (checked.digest != catalogEntry.sha256 || checked.spec.packId != catalogEntry.packId || checked.spec.packVersion != catalogEntry.version)) {
                throw FacePackOperationException(FacePackFailure.VERIFICATION, "Pack identity does not match the trusted catalog")
            }
            val logicalId = catalogEntry?.id ?: checked.spec.packId
            val old = synchronized(stateLock) { registry.rows.firstOrNull { it.source == source && it.logicalId == logicalId } }
            if (old != null) {
                if (old.digest == checked.digest && old.version == checked.spec.packVersion && !old.quarantined) {
                    return@foreground find(old.id) ?: throw FacePackOperationException(FacePackFailure.CONFLICT, "Installed face changed")
                }
                if (checked.spec.packVersion <= old.version) throw FacePackOperationException(FacePackFailure.CONFLICT,
                    if (checked.spec.packVersion < old.version) "Older pack versions cannot replace an installed face" else "This version has different content and cannot replace the installed face")
                if (!allowUpdate) throw FacePackUpdateRequiredException(old.id, checked.spec.name, checked.spec.packVersion, checked.digest)
                if (expectedUpdateDigest != checked.digest) throw FacePackOperationException(FacePackFailure.VERIFICATION, "The pack changed after update confirmation; review it again")
            } else if (synchronized(stateLock) { registry.rows.size } >= FacePackLimits.MAX_PACKS) {
                throw FacePackOperationException(FacePackFailure.STORAGE, "The face collection is full; remove a face first")
            }
            checkCancel(cancelled)
            if (treeBytes(root) > FacePackLimits.MAX_STORAGE_BYTES - INDEX_RESERVE_BYTES) throw FacePackOperationException(FacePackFailure.STORAGE, "The face collection does not have enough storage")
            syncTree(staging)
            val revision = "rev-${uuid()}"
            val destination = File(root, revision)
            fault("before-revision-move")
            Files.move(staging.toPath(), destination.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE)
            moved = destination
            index.syncDirectory(root)
            fault("after-revision-move")
            checkCancel(cancelled)
            val id = old?.id ?: "pack-${if (source == FacePackSource.IMPORTED) "import" else "curated"}-${uuid()}"
            val row = Row(id, source, logicalId, checked.spec.packVersion, checked.spec.name, checked.digest, revision)
            val pack = InstalledFacePack(id, source, checked.spec, checked.digest, File(destination, "payload"), checked.payloadBytes, checked.licenseText)
            report(progress, FacePackProgress(FacePackStage.INSTALLING))
            synchronized(stateLock) {
                val next = Registry(nextGeneration(), registry.rows.filterNot { it.id == id } + row, registry.removed)
                fault("before-index-write")
                writePointer(next)
                committed = true // From this point cancellation cannot truthfully undo the published install.
                fault("after-index-write")
                registry = next
                publishLocked(current.packs.filterNot { it.id == id } + pack)
            }
            notifyListeners()
            runCatching { collectGarbage() }
            pack
        } finally {
            if (staging.exists()) runCatching { safeDelete(staging) }
            // A failed pointer write may have reached its atomic rename before an fsync error.
            // Never delete the new immutable revision on that uncertain path. Recovery reads
            // the real pointer and GC removes only proven orphans. No active file is overwritten.
            if (!committed && moved != null && registryHealthy) runCatching { collectGarbage() }
        }
    }

    /** Registry removal is durable before preferences or file deletion; old IDs never get reused. */
    fun remove(id: String, afterCommit: () -> Unit = {}) = foreground {
        if (!isPackId(id)) throw FacePackOperationException(FacePackFailure.CONFLICT, "Built-in faces cannot be removed")
        synchronized(stateLock) {
            if (registry.rows.any { it.id == id }) {
                val removed = (registry.removed + id).takeLast(128)
                val next = Registry(nextGeneration(), registry.rows.filterNot { it.id == id }, removed)
                writePointer(next)
                registry = next
                publishLocked(current.packs.filterNot { it.id == id })
            }
        }
        notifyListeners()
        // Even if destination preference cleanup fails, old references now resolve to Steel, not
        // to an orphan revision, and a reimport gets a new ID. Never pretend two files are atomic.
        runCatching(afterCommit)
        runCatching { collectGarbage() }
    }

    fun retry(id: String): InstalledFacePack = foreground {
        val row = synchronized(stateLock) { registry.rows.firstOrNull { it.id == id } }
            ?: throw FacePackOperationException(FacePackFailure.CONFLICT, "This face is no longer installed")
        val pack = validateRow(row)
        synchronized(stateLock) {
            val next = Registry(nextGeneration(), registry.rows.map { if (it.id == id) it.copy(quarantined = false) else it }, registry.removed)
            writePointer(next); registry = next
            publishLocked(current.packs.filterNot { it.id == id } + pack)
        }
        notifyListeners()
        pack
    }

    fun quarantine(id: String, digest: String) {
        worker.execute { operationLock.withLock { runCatching {
            foreground {
                synchronized(stateLock) {
                    val row = registry.rows.firstOrNull { it.id == id && it.digest == digest && !it.quarantined } ?: return@foreground
                    val next = Registry(nextGeneration(), registry.rows.map { if (it.id == row.id) it.copy(quarantined = true) else it }, registry.removed)
                    writePointer(next); registry = next
                    publishLocked(current.packs.filterNot { it.id == id })
                }
                notifyListeners()
            }
        } } }
    }

    private fun validateRow(row: Row): InstalledFacePack {
        val revision = File(root, row.revision)
        if (Files.isSymbolicLink(revision.toPath()) || revision.canonicalFile.parentFile != root.canonicalFile) throw FacePackException("Invalid revision location")
        val checked = validation.installed(File(revision, "source.zip"), File(revision, "payload"), appVersionCode, row.digest)
        validation.androidDecode(checked) { false }
        if (checked.spec.packVersion != row.version || checked.spec.name != row.name ||
            (row.source == FacePackSource.IMPORTED && checked.spec.packId != row.logicalId)) throw FacePackException("Pack registry mismatch")
        // Removed catalog entries do not uninstall a previously trusted private immutable revision.
        catalog.firstOrNull { row.source == FacePackSource.CURATED && it.id == row.logicalId && it.version == row.version }?.let {
            if (it.sha256 != row.digest || it.packId != checked.spec.packId) throw FacePackException("Catalog identity mismatch")
        }
        return InstalledFacePack(row.id, row.source, checked.spec, checked.digest, checked.directory, checked.payloadBytes, checked.licenseText)
    }

    private fun <T> foreground(block: () -> T): T {
        initialize()
        if (!registryHealthy) throw FacePackOperationException(FacePackFailure.REGISTRY, "Installed faces are unavailable; restart before changing the collection")
        if (!operationLock.tryLock()) throw FacePackOperationException(FacePackFailure.BUSY, "Another face operation is in progress")
        return try { block() }
        catch (e: FacePackException) {
            throw FacePackOperationException(if (e.incompatible) FacePackFailure.INCOMPATIBLE else FacePackFailure.INVALID_PACK,
                if (e.incompatible) "This face requires a newer Nano version" else "This file is not a valid supported clock face pack", e)
        }
        catch (e: java.io.InterruptedIOException) {
            throw FacePackOperationException(FacePackFailure.CANCELLED, "Face operation cancelled", e)
        }
        catch (e: FacePackResourceException) {
            throw FacePackOperationException(FacePackFailure.RESOURCE, "Not enough memory to load this face right now; retry later", e)
        }
        catch (e: OutOfMemoryError) {
            throw FacePackOperationException(FacePackFailure.RESOURCE, "Not enough memory to load this face right now; retry later", e)
        }
        catch (e: FacePackOperationException) { throw e }
        catch (e: IOException) {
            when {
                !registryHealthy -> throw FacePackOperationException(FacePackFailure.REGISTRY, "Saving could not finish; restart to recover the preserved collection", e)
                facePackStorageFailure(e) -> throw FacePackOperationException(FacePackFailure.STORAGE, "Not enough space to safely save this face", e)
                else -> throw e
            }
        }
        catch (e: android.system.ErrnoException) {
            throw FacePackOperationException(if (registryHealthy) FacePackFailure.STORAGE else FacePackFailure.REGISTRY,
                "Face storage could not finish saving; existing files were preserved", e)
        }
        finally { operationLock.unlock() }
    }

    private fun publishLocked(packs: List<InstalledFacePack>) {
        transientUnavailable.retainAll(registry.rows.map { it.id }.toSet())
        transientUnavailable.removeAll(packs.map { it.id }.toSet())
        current = FacePackSnapshot(registry.generation,
            Collections.unmodifiableList(packs.sortedBy { it.spec.name.lowercase() }), true,
            if (transientUnavailable.isEmpty()) null else "Some faces are temporarily unavailable. Retry when memory and storage are available.",
            Collections.unmodifiableList(registry.rows.filter { it.quarantined || it.id in transientUnavailable }.map {
                UnavailableFacePack(it.id, it.name, it.source,
                    if (it.quarantined) "Face files are invalid or incompatible; retry or remove and reimport"
                    else "Temporarily unavailable; retry. Files and trust state were preserved.")
            }))
    }
    private fun nextGeneration(): Long {
        if (registry.generation == Long.MAX_VALUE) throw IOException("Face registry generation exhausted")
        return registry.generation + 1
    }
    private fun writePointer(next: Registry) {
        try { index.write(encodeRegistry(next)) }
        catch (failure: Throwable) {
            // Stop mutation/GC until a fresh process reloads whichever complete pointer won.
            registryHealthy = false
            current = current.copy(error = "The collection could not finish saving. Files were preserved; restart to recover.")
            notifyListeners()
            throw failure
        }
    }
    private fun notifyListeners() { notifyOnMain { listeners.forEach { runCatching(it) } } }
    private fun ensureRoot() {
        if (Files.isSymbolicLink(root.toPath())) throw IOException("Invalid face storage root")
        Files.createDirectories(root.toPath())
        if (!Files.isDirectory(root.toPath(), LinkOption.NOFOLLOW_LINKS)) throw IOException("Invalid face storage root")
    }
    private fun reserveStaging() {
        val reserve = FacePackLimits.MAX_ZIP_BYTES + FacePackLimits.MAX_EXPANDED_BYTES + INDEX_RESERVE_BYTES
        if (treeBytes(root) > FacePackLimits.MAX_STORAGE_BYTES - reserve || root.usableSpace < reserve + 1024 * 1024) {
            throw FacePackOperationException(FacePackFailure.STORAGE, "Not enough space to safely install a face; keep 16 MiB free in the collection")
        }
    }
    private fun collectGarbage() {
        val keep = synchronized(stateLock) { registry.rows.map { it.revision }.toSet() + leases.keys }
        root.listFiles()?.forEach { file ->
            if ((REVISION.matches(file.name) && file.name !in keep) || file.name.startsWith(".stage-")) safeDelete(file)
        }
    }
    private fun syncTree(directory: File) {
        Files.walkFileTree(directory.toPath(), object : SimpleFileVisitor<java.nio.file.Path>() {
            override fun visitFile(file: java.nio.file.Path, attrs: BasicFileAttributes): FileVisitResult {
                if (!attrs.isRegularFile) throw IOException("Invalid face file")
                FileOutputStream(file.toFile(), true).use { it.fd.sync() }
                return FileVisitResult.CONTINUE
            }
            override fun postVisitDirectory(dir: java.nio.file.Path, error: IOException?): FileVisitResult {
                if (error != null) throw error
                index.syncDirectory(dir.toFile()); return FileVisitResult.CONTINUE
            }
        })
    }
    private fun safeDelete(file: File) {
        if (file.parentFile?.canonicalFile != root.canonicalFile) throw IOException("Invalid cleanup location")
        Files.walkFileTree(file.toPath(), object : SimpleFileVisitor<java.nio.file.Path>() {
            override fun visitFile(path: java.nio.file.Path, attrs: BasicFileAttributes): FileVisitResult { Files.deleteIfExists(path); return FileVisitResult.CONTINUE }
            override fun postVisitDirectory(path: java.nio.file.Path, error: IOException?): FileVisitResult {
                if (error != null) throw error
                Files.deleteIfExists(path); return FileVisitResult.CONTINUE
            }
        })
    }
    private fun treeBytes(directory: File): Long {
        var total = 0L
        Files.walkFileTree(directory.toPath(), object : SimpleFileVisitor<java.nio.file.Path>() {
            override fun visitFile(file: java.nio.file.Path, attrs: BasicFileAttributes): FileVisitResult {
                if (!attrs.isRegularFile) throw IOException("Invalid file in face storage")
                total = Math.addExact(total, attrs.size())
                return FileVisitResult.CONTINUE
            }
        })
        return total
    }

    private fun copyProvider(open: () -> InputStream, destination: File, cancelled: () -> Boolean, progress: (FacePackProgress) -> Unit, abortInput: () -> Unit) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120)
        val input = AtomicReference<InputStream?>()
        val timedOut = AtomicBoolean(false)
        val watcher = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "face-import-cancel").apply { isDaemon = true } }
        val watch = watcher.scheduleWithFixedDelay({
            if (System.nanoTime() >= deadline) timedOut.set(true)
            if (timedOut.get() || cancelled()) { runCatching(abortInput); runCatching { input.get()?.close() } }
        }, 0, 50, TimeUnit.MILLISECONDS)
        try {
            checkCancel(cancelled)
            if (!destination.createNewFile()) throw IOException("Could not create private import snapshot")
            val source = open()
            input.set(source)
            source.use {
                FileOutputStream(destination, false).use { out ->
                    var count = 0L
                    val buffer = ByteArray(8192)
                    while (true) {
                        checkCancel(cancelled)
                        if (timedOut.get()) throw FacePackOperationException(FacePackFailure.TIMEOUT, "Import timed out")
                        val n = source.read(buffer)
                        if (n < 0) break
                        if (n == 0) continue
                        count += n
                        if (count > FacePackLimits.MAX_ZIP_BYTES) throw FacePackOperationException(FacePackFailure.INVALID_PACK, "Face packs must be no larger than 4 MiB")
                        out.write(buffer, 0, n)
                        report(progress, FacePackProgress(FacePackStage.COPYING, count))
                    }
                    if (count == 0L) throw FacePackOperationException(FacePackFailure.INVALID_PACK, "The selected file is empty")
                    out.fd.sync()
                }
            }
            checkCancel(cancelled)
            if (timedOut.get()) throw FacePackOperationException(FacePackFailure.TIMEOUT, "Import timed out")
        } catch (e: IOException) {
            if (cancelled()) throw FacePackOperationException(FacePackFailure.CANCELLED, "Import cancelled", e)
            if (timedOut.get()) throw FacePackOperationException(FacePackFailure.TIMEOUT, "Import timed out", e)
            throw e
        } finally { input.getAndSet(null)?.let { runCatching { it.close() } }; watch.cancel(true); watcher.shutdownNow() }
    }
    private fun checkCancel(cancelled: () -> Boolean) {
        if (cancelled() || Thread.currentThread().isInterrupted) throw FacePackOperationException(FacePackFailure.CANCELLED, "Face operation cancelled")
    }
    private fun report(callback: (FacePackProgress) -> Unit, value: FacePackProgress) { runCatching { callback(value) } }

    private fun encodeRegistry(value: Registry): ByteArray = ByteArrayOutputStream().also { buffer ->
        DataOutputStream(buffer).use { out ->
            out.writeInt(INDEX_MAGIC); out.writeInt(1); out.writeLong(value.generation); out.writeInt(value.rows.size)
            value.rows.forEach { row ->
                out.writeUTF(row.id); out.writeInt(row.source.ordinal); out.writeUTF(row.logicalId); out.writeInt(row.version)
                out.writeUTF(row.name); out.writeUTF(row.digest); out.writeUTF(row.revision); out.writeBoolean(row.quarantined)
            }
            out.writeInt(value.removed.size); value.removed.forEach(out::writeUTF)
        }
    }.toByteArray().also { if (it.size > MAX_INDEX_BYTES) throw IOException("Registry exceeds its limit") }

    private fun decodeRegistry(bytes: ByteArray): Registry = DataInputStream(ByteArrayInputStream(bytes)).use { input ->
        if (bytes.size > MAX_INDEX_BYTES || input.readInt() != INDEX_MAGIC || input.readInt() != 1) throw IOException("Unsupported registry")
        val generation = input.readLong(); if (generation < 0) throw IOException("Invalid registry generation")
        val count = input.readInt(); if (count !in 0..FacePackLimits.MAX_PACKS) throw IOException("Invalid registry count")
        val rows = (0 until count).map {
            val id = input.readUTF(); val sourceOrdinal = input.readInt()
            if (sourceOrdinal !in FacePackSource.entries.indices) throw IOException("Invalid registry source")
            val row = Row(id, FacePackSource.entries[sourceOrdinal], input.readUTF(), input.readInt(), input.readUTF(), input.readUTF(), input.readUTF(), input.readBoolean())
            if (!isPackId(row.id) || !row.id.startsWith("pack-${if (row.source == FacePackSource.IMPORTED) "import" else "curated"}-") || !Regex("[a-z][a-z0-9-]{0,63}").matches(row.logicalId) || row.version < 1 ||
                row.name.isBlank() || row.name.length > 120 || row.name.any { it.isISOControl() || it.code in 0x202a..0x202e || it.code in 0x2066..0x2069 } ||
                !Regex("[a-f0-9]{64}").matches(row.digest) || !REVISION.matches(row.revision)) throw IOException("Invalid registry row")
            row
        }
        if (rows.map { it.id }.toSet().size != rows.size || rows.map { it.source to it.logicalId }.toSet().size != rows.size || rows.map { it.revision }.toSet().size != rows.size) throw IOException("Duplicate registry row")
        val removedCount = input.readInt(); if (removedCount !in 0..128) throw IOException("Invalid removal history")
        val removed = (0 until removedCount).map { input.readUTF().also { if (!isPackId(it)) throw IOException("Invalid removed ID") } }
        if (input.read() != -1 || rows.any { it.id in removed }) throw IOException("Invalid registry trailing data")
        Registry(generation, rows, removed)
    }

    companion object {
        private val contexts = java.util.IdentityHashMap<Context, FacePackStore>()
        private val instances = mutableMapOf<String, FacePackStore>()
        private val REVISION = Regex("rev-[a-f0-9]{32}")
        fun isPackId(id: String): Boolean = Regex("pack-(import|curated)-[a-f0-9]{32}").matches(id)
        private fun uuid() = UUID.randomUUID().toString().replace("-", "")
        fun get(context: Context): FacePackStore {
            val app = context.applicationContext
            return synchronized(instances) {
                // ContextImpl.noBackupFilesDir itself may stat/create its directory. Check the
                // application-context identity cache before even invoking that accessor on frames.
                contexts[app]?.let { return@synchronized it }
                val noBackup = app.noBackupFilesDir
                val root = File(noBackup, "clock-face-packs-v1")
                val store = instances.getOrPut(root.absolutePath) {
                    // Cache lookup is purely lexical; canonicalization/stat must never occur on
                    // repeated registry/frame lookups. Resolve trusted OS aliases on first use only.
                    val canonicalRoot = File(noBackup.canonicalFile, "clock-face-packs-v1")
                    instances[canonicalRoot.absolutePath]?.let { return@getOrPut it }
                    val version = app.packageManager.getPackageInfo(app.packageName, 0).longVersionCode.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                    val handler = Handler(Looper.getMainLooper())
                    FacePackStore(canonicalRoot, version, notifyOnMain = { handler.post(it) }).also {
                        instances[canonicalRoot.absolutePath] = it
                        it.requestLoad()
                    }
                }
                contexts[app] = store
                store
            }
        }
    }
}

private const val INDEX_MAGIC = 0x4e465031
private const val MAX_INDEX_BYTES = 64 * 1024
private const val INDEX_RESERVE_BYTES = 256L * 1024
