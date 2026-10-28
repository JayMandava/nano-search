package ai.nanosearch.launcher

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ManifestCompatibilityTest {
    @Test fun optionalPhoneSearchDoesNotRequireTelephonyHardware() {
        val xml = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(File("src/main/AndroidManifest.xml"))
        val android = "http://schemas.android.com/apk/res/android"
        val features = xml.getElementsByTagName("uses-feature")
        val telephony = (0 until features.length).map { features.item(it) as org.w3c.dom.Element }
            .single { it.getAttributeNS(android, "name") == "android.hardware.telephony" }
        assertEquals("false", telephony.getAttributeNS(android, "required"))
        val permissions = xml.getElementsByTagName("uses-permission")
        val names = (0 until permissions.length).map {
            (permissions.item(it) as org.w3c.dom.Element).getAttributeNS(android, "name")
        }.toSet()
        assertTrue("Optional SMS search must remain available", "android.permission.READ_SMS" in names)
        assertTrue("Optional call search must remain available", "android.permission.READ_CALL_LOG" in names)
    }
}
