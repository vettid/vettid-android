package com.vettid.core.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Foreground location only (ANDROID-PLAN §4 "Location", question 24): the module's manifest asks for the coarse and fine permissions
 * and the two foreground-service ones, never for background location, and declares the service with type `location`. The same
 * rule is held over every build's merged manifest by `:app:check<Variant>BackgroundLocation`.
 */
class LocationManifestTest {
    private val ns = "http://schemas.android.com/apk/res/android"
    private val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder()
        .parse(File("src/main/AndroidManifest.xml"))

    private fun names(tag: String): List<String> =
        doc.getElementsByTagName(tag).let { l -> (0 until l.length).map { (l.item(it) as Element).getAttributeNS(ns, "name") } }

    @Test
    fun foregroundLocationIsAskedForAndBackgroundLocationNever() {
        val asked = names("uses-permission")
        assertTrue(
            asked.containsAll(
                listOf(
                    "android.permission.ACCESS_COARSE_LOCATION",
                    "android.permission.ACCESS_FINE_LOCATION",
                    "android.permission.FOREGROUND_SERVICE",
                    "android.permission.FOREGROUND_SERVICE_LOCATION",
                ),
            ),
        )
        assertFalse(asked.any { it.contains("BACKGROUND_LOCATION") })
        assertFalse(names("uses-permission-sdk-23").any { it.contains("BACKGROUND_LOCATION") })
    }

    @Test
    fun theServiceIsALocationForegroundServiceNotExported() {
        val service = doc.getElementsByTagName("service").item(0) as Element
        assertEquals(".LocationService", service.getAttributeNS(ns, "name"))
        assertEquals("location", service.getAttributeNS(ns, "foregroundServiceType"))
        assertEquals("false", service.getAttributeNS(ns, "exported"))
        assertEquals(1, doc.getElementsByTagName("service").length)
    }

    @Test
    fun aPhoneWithoutGpsIsNotExcluded() {
        val features = doc.getElementsByTagName("uses-feature")
        val required = (0 until features.length).map { features.item(it) as Element }.associate {
            it.getAttributeNS(ns, "name") to it.getAttributeNS(ns, "required")
        }
        assertEquals("false", required["android.hardware.location"])
        assertEquals("false", required["android.hardware.location.gps"])
    }
}
