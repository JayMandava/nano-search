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
    // Original CC0 demo, published and byte-verified before activation; no commercial-watch claims.
    // A remote feed or mutable branch can never extend this compiled trust list.
    val entries: List<FacePackCatalogEntry> = listOf(
        FacePackCatalogEntry(
            id = "orbit-blue-v1", packId = "orbit-blue", version = 1, name = "Orbit Blue (demo)",
            url = "https://raw.githubusercontent.com/JayMandava/nano-search/19072b6ed38b944142b1aaf7b4f1f3d87a00a3a6/clock-packs/orbit-blue-v1.zip",
            bytes = 14_303,
            sha256 = "f12204deb3d998843c77048be0f7e5803307246d49bba047aa017e8ac1e0363f",
        ),
    )
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
