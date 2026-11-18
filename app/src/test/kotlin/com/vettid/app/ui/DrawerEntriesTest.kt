package com.vettid.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which entries the drawer lists ([shownInDrawer]): the drawer itself exists only while the vault is open and not gated. */
class DrawerEntriesTest {
    @Test
    fun theLocationEntryIsAlwaysListed() {
        assertTrue(shownInDrawer(TopLevelDestination.Location.key, ShellBadges()))
        assertTrue(shownInDrawer(TopLevelDestination.Location.key, ShellBadges(votesShown = true, walletShown = true)))
    }

    @Test
    fun votesAndWalletKeepTheirOwnRules() {
        assertFalse(shownInDrawer(TopLevelDestination.Votes.key, ShellBadges()))
        assertTrue(shownInDrawer(TopLevelDestination.Votes.key, ShellBadges(votesShown = true)))
        assertFalse(shownInDrawer(TopLevelDestination.Wallet.key, ShellBadges()))
        assertTrue(shownInDrawer(TopLevelDestination.Wallet.key, ShellBadges(walletShown = true)))
    }

    @Test
    fun everyOtherEntryIsListed() {
        TopLevelDestination.entries
            .filter { it != TopLevelDestination.Votes && it != TopLevelDestination.Wallet }
            .forEach { assertTrue(it.name, shownInDrawer(it.key, ShellBadges())) }
    }
}
