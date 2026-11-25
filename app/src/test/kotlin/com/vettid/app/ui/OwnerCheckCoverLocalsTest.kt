package com.vettid.app.ui

import com.vettid.feature.onboarding.OwnerCheckMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the shell provides from the check's cover: dialogs and sheets go only once it covers (after the wipe has reached
 * their fields), a result on screen counts as left from its undecided frames on.
 */
class OwnerCheckCoverLocalsTest {
    @Test
    fun theUndecidedFramesAreComingButNotCovered() {
        val undecided = OwnerCheckCover(mode = null, undecided = true, release = {})
        assertFalse(undecided.covered)
        assertTrue(undecided.coming)
        val covering = OwnerCheckCover(mode = OwnerCheckMode.GATED, undecided = false, release = {})
        assertTrue(covering.covered)
        assertTrue(covering.coming)
        val none = OwnerCheckCover(mode = null, undecided = false, release = {})
        assertFalse(none.covered)
        assertFalse(none.coming)
    }
}
