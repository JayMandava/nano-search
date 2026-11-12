package ai.nanosearch.launcher

import org.junit.Assert.*
import org.junit.Test

class ModelTransferStateTest {
    @Test fun identicalProgressDoesNotInvalidateComposeState() {
        assertEquals(Transfer.Downloading(64, 128), Transfer.Downloading(64, 128))
        assertEquals(Transfer.Failed("Offline"), Transfer.Failed("Offline"))
    }

    @Test fun realProgressAndStallChangesRemainObservable() {
        assertNotEquals(Transfer.Downloading(64, 128), Transfer.Downloading(65, 128))
        assertNotEquals(Transfer.Downloading(0, 128), Transfer.Downloading(0, 128, stalled = true))
    }
}
