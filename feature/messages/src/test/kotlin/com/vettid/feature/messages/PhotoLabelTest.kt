package com.vettid.feature.messages

import org.junit.Assert.assertEquals
import org.junit.Test

/** A photo message's spoken label leaves out an empty caption and an absent state (no stray ". ."). */
class PhotoLabelTest {
    @Test
    fun withoutCaption() {
        assertEquals("Photo you sent. Delivered", photoLabel("Photo you sent", "", "Delivered"))
        assertEquals("Photo from Ann", photoLabel("Photo from Ann", "", ""))
        assertEquals("Photo from Ann", photoLabel("Photo from Ann", null, null))
        assertEquals("Photo from Ann", photoLabel("Photo from Ann", "   ", null))
    }

    @Test
    fun withCaption() {
        assertEquals("Photo you sent. At the lake. Delivered", photoLabel("Photo you sent", "At the lake", "Delivered"))
        assertEquals("Photo from Ann. At the lake", photoLabel("Photo from Ann", "At the lake", null))
    }
}
