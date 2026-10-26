package ai.nanosearch.launcher

import ai.nanosearch.launcher.clocks.packs.FacePackFailure
import ai.nanosearch.launcher.clocks.packs.FacePackOperationException
import org.junit.Assert.*
import org.junit.Test

class ClockFaceUiMessagesTest {
    @Test fun collectionIsInternalAndDebugProviderNeverEntersMainManifest() {
        val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val main = factory.newDocumentBuilder().parse(java.io.File("src/main/AndroidManifest.xml"))
        val ns = "http://schemas.android.com/apk/res/android"
        val activities = main.getElementsByTagName("activity")
        val collection = (0 until activities.length).map { activities.item(it) as org.w3c.dom.Element }
            .single { it.getAttributeNS(ns, "name") == ".ClockFacesActivity" }
        assertEquals("false", collection.getAttributeNS(ns, "exported"))
        assertFalse("Rotation must be supported", collection.hasAttributeNS(ns, "screenOrientation"))
        assertFalse(java.io.File("src/main/AndroidManifest.xml").readText().contains("ClockFaceBlockingProvider"))
        val debug = factory.newDocumentBuilder().parse(java.io.File("src/debug/AndroidManifest.xml"))
        val providers = debug.getElementsByTagName("provider")
        val testProvider = (0 until providers.length).map { providers.item(it) as org.w3c.dom.Element }
            .single { it.getAttributeNS(ns, "name").endsWith("ClockFaceBlockingProvider") }
        assertEquals("false", testProvider.getAttributeNS(ns, "exported"))
        assertEquals("false", testProvider.getAttributeNS(ns, "grantUriPermissions"))
    }

    @Test fun everyFailureCategoryHasSafeActionableText() {
        FacePackFailure.entries.forEach { reason ->
            val message = safeFaceFailure(FacePackOperationException(reason, "secret /private/user/key?token=private"))
            assertTrue(reason.name, message.isNotBlank())
            assertFalse(message.contains("secret"))
            assertFalse(message.contains("/private"))
            assertFalse(message.contains("token="))
        }
    }
    @Test fun arbitraryProviderExceptionDoesNotLeakPathsOrTokens() {
        val message = safeFaceFailure(java.io.IOException("/private/provider/a?token=credential"))
        assertFalse(message.contains("/private"))
        assertFalse(message.contains("credential"))
        assertTrue(message.contains("Try again"))
    }
    @Test fun expiredDocumentGrantAsksForSelectionAgain() {
        val message = safeFaceFailure(SecurityException("content://private-provider/document"))
        assertTrue(message.contains("Choose the file again"))
        assertFalse(message.contains("content://"))
    }
    @Test fun failureCategoriesDoNotCollapseOfflineAndVerification() {
        assertNotEquals(safeFaceFailure(FacePackOperationException(FacePackFailure.OFFLINE, "")),
            safeFaceFailure(FacePackOperationException(FacePackFailure.VERIFICATION, "")))
        assertTrue(safeFaceFailure(FacePackOperationException(FacePackFailure.INCOMPATIBLE, "")).contains("newer Nano"))
    }
}
