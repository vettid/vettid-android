package com.vettid.core.data.prefs

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * A6 audit P4: a corrupt preferences file used to make every read of the preferences throw, so the app lock never
 * left "pending" and the app stayed on its start screen until its data was cleared. The file holds no key and
 * nothing of the vault, so it is replaced by an empty one, and the member is told once.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PreferencesCorruptionTest {
    @get:Rule
    val dir = TemporaryFolder()

    private fun file(name: String) = File(dir.root, "$name.preferences_pb")

    @Test
    fun aCorruptFileIsReplacedByTheDefaultsAndTheMemberIsToldUntilTheyHaveSeenIt() = runTest {
        val f = file("corrupt").apply { writeBytes(ByteArray(64) { 0xFF.toByte() }) }
        val repo = DataStorePreferencesRepository(PreferencesFiles.open(f, backgroundScope))
        val p = repo.preferences.first()
        assertTrue("told", p.wasReset)
        assertEquals(AppPreferences(wasReset = true), p)
        assertFalse("the app lock is off after a reset", p.appLockEnabled)
        // The app works on: a preference can be set again, and the notice stays until it is dismissed.
        repo.setTheme(ThemePreference.DARK)
        assertEquals(ThemePreference.DARK, repo.preferences.first().theme)
        assertTrue(repo.preferences.first().wasReset)
        repo.acknowledgeReset()
        assertFalse(repo.preferences.first().wasReset)
        assertEquals(ThemePreference.DARK, repo.preferences.first().theme)
    }

    @Test
    fun aHealthyFileIsNotTouchedAndSaysNothing() = runTest {
        val f = file("healthy")
        val first = DataStorePreferencesRepository(PreferencesFiles.open(f, backgroundScope))
        first.setAppLockTimeout(AppLockTimeout.ONE_HOUR)
        val p = first.preferences.first()
        assertEquals(AppLockTimeout.ONE_HOUR, p.appLockTimeout)
        assertFalse(p.wasReset)
    }

    @Test
    fun aFileThatCannotBeReadAtAllReadsAsTheDefaultsWithoutThrowing() = runTest {
        // A directory where the file should be: an I/O error, not a corrupt file. Nothing is replaced.
        val f = file("unreadable").apply { mkdirs() }
        val repo = DataStorePreferencesRepository(PreferencesFiles.open(f, backgroundScope))
        assertEquals(AppPreferences(), repo.preferences.first())
        assertTrue(f.isDirectory)
    }
}
