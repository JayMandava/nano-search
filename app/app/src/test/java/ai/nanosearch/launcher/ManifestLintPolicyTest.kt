package ai.nanosearch.launcher

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The two reviewed permission declarations are the entire lint-exception policy. */
class ManifestLintPolicyTest {
    @Test fun permissionExceptionsCannotExpandToOtherChecksOrElements() {
        val document = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(File("src/main/AndroidManifest.xml"))
        val android = "http://schemas.android.com/apk/res/android"
        val tools = "http://schemas.android.com/tools"
        val expected = mapOf(
            "android.permission.BIND_APPWIDGET" to "ProtectedPermissions",
            "android.permission.QUERY_ALL_PACKAGES" to "QueryAllPackagesPermission",
        )
        val seen = mutableMapOf<String, String>()
        val elements = document.getElementsByTagName("*")
        for (i in 0 until elements.length) {
            val node = elements.item(i) as org.w3c.dom.Element
            if (!node.hasAttributeNS(tools, "ignore")) continue
            assertEquals("No manifest/application/global exception", "uses-permission", node.tagName)
            val permission = node.getAttributeNS(android, "name")
            val issue = node.getAttributeNS(tools, "ignore")
            assertEquals("No extra check IDs or permissions", expected[permission], issue)
            assertNull("No duplicate exception declarations", seen.put(permission, issue))
        }
        assertEquals("Both narrowly reviewed declarations must remain explicit", expected, seen)
    }
}
