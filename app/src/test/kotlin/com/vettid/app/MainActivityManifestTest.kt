package com.vettid.app

import org.junit.Assert.assertEquals
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The share sheet starts its target with FLAG_ACTIVITY_NEW_DOCUMENT and MULTIPLE_TASK. MainActivity is a single
 * task, so that a shared canary manifest reaches the running activity (onNewIntent) instead of a second instance
 * (in a task of its own or the sharing app's), with the first one's dialog left behind in the background.
 */
class MainActivityManifestTest {
    @Test
    fun mainActivityIsASingleTask() {
        val f = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val doc = f.newDocumentBuilder().parse(File("src/main/AndroidManifest.xml"))
        val activities = doc.getElementsByTagName("activity")
        val main = (0 until activities.length).map { activities.item(it) as Element }
            .single { it.getAttributeNS(ANDROID, "name") == ".MainActivity" }
        assertEquals("singleTask", main.getAttributeNS(ANDROID, "launchMode"))
    }

    private companion object {
        const val ANDROID = "http://schemas.android.com/apk/res/android"
    }
}
