// Test data: long literal rows are clearer than wrapped ones.
@file:Suppress("MaxLineLength")

package com.vettid.core.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The on-phone service is a foreground service of type `specialUse` with its declared subtype (ANDROID-PLAN 0.1.23,
 * Notification modes 3, §9 question 11), never dataSync or remoteMessaging; the boot receiver hears the boot and
 * an app update.
 */
class ServiceManifestTest {
    private val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        .newDocumentBuilder().parse(File("src/main/AndroidManifest.xml"))

    private fun elements(tag: String) = doc.getElementsByTagName(tag).let { l -> (0 until l.length).map { l.item(it) as Element } }

    @Test
    fun specialUseWithItsSubtype() {
        val service = elements("service").single { it.getAttributeNS(ANDROID, "name") == ".VaultConnectionService" }
        assertEquals("specialUse", service.getAttributeNS(ANDROID, "foregroundServiceType"))
        assertEquals("false", service.getAttributeNS(ANDROID, "exported"))
        val p = elements("property").single()
        assertEquals("android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE", p.getAttributeNS(ANDROID, "name"))
        assertTrue(p.getAttributeNS(ANDROID, "value").contains("end-to-end encrypted"))
        val perms = elements("uses-permission").map { it.getAttributeNS(ANDROID, "name") }
        assertTrue(perms.containsAll(listOf("android.permission.FOREGROUND_SERVICE_SPECIAL_USE", "android.permission.RECEIVE_BOOT_COMPLETED")))
        assertTrue(perms.none { it.contains("DATA_SYNC") || it.contains("REMOTE_MESSAGING") || it.contains("REQUEST_IGNORE_BATTERY") })
    }

    @Test
    fun bootAndUpdateStartIt() {
        val actions = elements("action").map { it.getAttributeNS(ANDROID, "name") }
        assertEquals(listOf("android.intent.action.BOOT_COMPLETED", "android.intent.action.MY_PACKAGE_REPLACED"), actions)
    }

    private companion object {
        const val ANDROID = "http://schemas.android.com/apk/res/android"
    }
}
