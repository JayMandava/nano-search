package ai.nanosearch.launcher

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiManifestTest {
    @Test fun declaresReadOnlyWifiPermission() {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val manifest = factory.newDocumentBuilder().parse(File("src/main/AndroidManifest.xml"))
        val nodes = manifest.getElementsByTagName("uses-permission")
        val permissions = (0 until nodes.length).map {
            nodes.item(it).attributes.getNamedItemNS("http://schemas.android.com/apk/res/android", "name").nodeValue
        }
        assertTrue("The Wi-Fi status read requires ACCESS_WIFI_STATE", "android.permission.ACCESS_WIFI_STATE" in permissions)
        assertFalse("The frame must not control networking", "android.permission.CHANGE_WIFI_STATE" in permissions)
    }
}
