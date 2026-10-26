package ai.nanosearch.launcher.clocks.packs

/** A digest-pinned entry compiled into the signed app, never parsed from an unsigned remote feed. */
data class FacePackCatalogEntry(
    val id: String,
    val packId: String,
    val version: Int,
    val name: String,
    val url: String,
    val bytes: Long,
    val sha256: String,
)

object FacePackCatalog {
    // Deliberately empty until original packs have been reviewed and actually published.
    // Import remains available. Never substitute placeholder URLs or silently trust a remote JSON feed.
    val entries: List<FacePackCatalogEntry> = emptyList()
}

enum class FacePackStage { COPYING, DOWNLOADING, CHECKING, INSTALLING }
data class FacePackProgress(val stage: FacePackStage, val bytes: Long = 0, val totalBytes: Long? = null)

enum class FacePackFailure {
    BUSY, CANCELLED, TIMEOUT, OFFLINE, SOURCE_UNAVAILABLE, UNSUPPORTED_SOURCE,
    STORAGE, RESOURCE, INVALID_PACK, INCOMPATIBLE, VERIFICATION, CONFLICT, REGISTRY,
}

class FacePackOperationException(val reason: FacePackFailure, message: String, cause: Throwable? = null) :
    java.io.IOException(message, cause)

class FacePackUpdateRequiredException(
    val existingId: String,
    val name: String,
    val version: Int,
    val digest: String,
) : java.io.IOException("An imported face with this identity is already installed. Confirm the update first.")

/** Keep platform errors typed; never parse localized exception text or display private paths. */
internal fun facePackStorageFailure(error: Throwable): Boolean = generateSequence(error) { it.cause }
    .take(8).any { cause ->
        cause is android.system.ErrnoException &&
            cause.errno in setOf(android.system.OsConstants.ENOSPC, android.system.OsConstants.EDQUOT)
    }
