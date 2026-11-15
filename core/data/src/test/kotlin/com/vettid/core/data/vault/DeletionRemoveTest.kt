package com.vettid.core.data.vault

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The confirmed "Remove from this phone" wipes only for the deletion it was confirmed for, still up and past its bound. */
class DeletionRemoveTest {
    private val min = 60_000L
    private val api = DeletionRemove.Seen(null, DeletedApiMarker("v", 0, 12 * min, "vault_deleted"))
    private val own = DeletionRemove.Seen(DeletingMarker("v", 0, 10 * min), null)
    private val none = DeletionRemove.Seen(null, null)
    private val slowApi = AppPhase.Deleting(slow = true, apiOnly = true, removeFromMs = 12 * min)

    @Test
    fun theSameDeletionPastItsBoundWipes() {
        assertTrue(DeletionRemove.wipes(api, api, deleting = true, phase = slowApi))
        assertTrue(DeletionRemove.wipes(own, own, deleting = true, phase = AppPhase.Deleting(slow = true)))
    }

    /** The vault's word erased meanwhile (its notice stays), or a status listed the vault again: nothing. */
    @Test
    fun aStateThatEndedWipesNothing() {
        assertFalse(DeletionRemove.wipes(api, none, deleting = false, phase = AppPhase.SignedOut))
        assertFalse(DeletionRemove.wipes(api, none, deleting = false, phase = AppPhase.Locked))
        assertFalse(DeletionRemove.wipes(api, api, deleting = false, phase = AppPhase.Locked))
    }

    /** A clear, then at once a new 410: the new deletion has its own bound first. */
    @Test
    fun aNewDeletionBehindItWipesNothing() {
        val again = DeletionRemove.Seen(null, DeletedApiMarker("v", 5 * min, 17 * min, "vault_deleted"))
        assertFalse(DeletionRemove.wipes(api, again, deleting = true, phase = AppPhase.Deleting(apiOnly = true, removeFromMs = 17 * min)))
        assertFalse(DeletionRemove.wipes(api, again, deleting = true, phase = slowApi))
    }

    /** Not past the bound (Remove is not offered then): nothing. */
    @Test
    fun beforeTheBoundWipesNothing() {
        assertFalse(DeletionRemove.wipes(api, api, deleting = true, phase = AppPhase.Deleting(apiOnly = true, removeFromMs = 12 * min)))
    }

    /** No deletion held when it was called: nothing, whatever is up later. */
    @Test
    fun noDeletionAtTheCallWipesNothing() {
        assertFalse(DeletionRemove.wipes(none, none, deleting = true, phase = AppPhase.Deleting(slow = true)))
        assertFalse(DeletionRemove.wipes(none, api, deleting = true, phase = slowApi))
    }
}
