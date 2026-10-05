package com.vettid.core.data.wipe

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The wipe of a replaced phone (owner decision, 2026-10-05) on an app data
 * directory laid out as on a phone, with a fake Keystore: afterwards the state
 * equals a fresh install; an interrupted wipe finishes at the next start.
 */
class LocalWipeTest {
    @get:Rule
    val tmp = TemporaryFolder()

    /** The app's data directory, as the app lays it out. */
    private inner class Phone {
        val data: File = tmp.newFolder("data")
        val noBackup = File(data, "no_backup")
        val files = File(data, "files")
        val cache = File(data, "cache")
        val databases = File(data, "databases")
        val sharedPrefs = File(data, "shared_prefs")
        val keystore = linkedSetOf<String>()
        var notifications = 3
        var prefsCleared = 0

        /** Delete calls that fail (once each), to simulate a crash or a Keystore error midway. */
        val failingKeys = mutableSetOf<String>()

        val targets = object : WipeTargets {
            override val marker = File(noBackup, LocalWipe.MARKER)
            override val dirs = listOf(noBackup, files, cache, databases, sharedPrefs)

            override fun keyAliases(): List<String> = keystore.toList()

            override fun deleteKey(alias: String) {
                check(!failingKeys.remove(alias)) { "keystore busy" }
                keystore.remove(alias)
            }

            override fun clearSharedPreferences() {
                prefsCleared++
                sharedPrefs.listFiles()?.forEach { it.delete() }
            }

            override fun cancelNotifications() {
                notifications = 0
            }
        }

        /** Everything an enrolled app with connections, an account and the app lock keeps. */
        fun enrolled() {
            write(noBackup, "account.bin") // the account record (and the legacy replaced marker)
            write(noBackup, "vault-device.bin") // device state: vault, relay mailbox registration, tokens, session epochs
            write(noBackup, "social.bin") // connections, conversations, approvals, peer declines
            write(noBackup, "member-session.bin") // the account session cookies
            write(noBackup, "app-lock.bin") // the wrapped app-data key
            write(files, "datastore/vettid_preferences.preferences_pb") // theme, app lock, timeout
            write(cache, "okhttp/journal")
            write(databases, "cache.db")
            write(sharedPrefs, "vettid_device_keys.xml") // wrapped identity, KEM and relay seeds
            write(sharedPrefs, "vettid_release_state.xml") // manifest serial and release seen
            write(sharedPrefs, "vettid_devstack_account.xml")
            keystore += listOf(
                "vettid.device_attestation.v1", "vettid.seed_wrap.v1", "vettid.app_data.v1", "vettid.local_files.v1",
                "some.library.key",
            )
        }

        /** A fresh install: no file, no key, no notification, no marker. */
        fun assertFresh() {
            for (d in listOf(noBackup, files, cache, databases, sharedPrefs)) {
                assertTrue("${d.name} not empty: ${d.listFiles()?.map { it.name }}", d.listFiles().isNullOrEmpty())
            }
            assertTrue("keys left: $keystore", keystore.isEmpty())
            assertEquals(0, notifications)
            assertFalse(targets.marker.exists())
        }

        private fun write(dir: File, path: String) {
            val f = File(dir, path)
            f.parentFile.mkdirs()
            f.writeText("state")
        }
    }

    @Test
    fun aWipeLeavesAFreshInstall() = runTest {
        val p = Phone()
        p.enrolled()
        var memory = 0
        val w = LocalWipe(p.targets, hooks = listOf({ memory++ }, { error("a failing hook") }, { memory++ }))
        assertTrue(w.run())
        p.assertFresh()
        assertEquals("every in-memory reset ran", 2, memory)
        assertEquals(1, p.prefsCleared)
        assertFalse(w.pending)
    }

    @Test
    fun theMarkerIsWrittenBeforeAnythingIsDeleted() {
        val p = Phone()
        p.enrolled()
        val w = LocalWipe(p.targets)
        w.begin()
        assertTrue(w.pending)
        assertTrue(File(p.noBackup, "account.bin").exists())
    }

    @Test
    fun aWipeInterruptedByProcessDeathFinishesAtTheNextStart() {
        val p = Phone()
        p.enrolled()
        // The process dies after the marker and part of the erase.
        LocalWipe(p.targets).begin()
        File(p.noBackup, "social.bin").delete()
        p.keystore.remove("vettid.app_data.v1")
        // Next start, before anything reads local state (VettIdApplication.onCreate):
        val next = LocalWipe(p.targets)
        assertTrue(next.resumeIfPending())
        p.assertFresh()
        assertFalse(next.resumeIfPending())
    }

    @Test
    fun aStepThatFailsKeepsTheMarkerAndTheNextStartFinishes() {
        val p = Phone()
        p.enrolled()
        p.failingKeys += "vettid.seed_wrap.v1"
        val w = LocalWipe(p.targets)
        w.begin()
        assertFalse(w.erase())
        // Everything else is gone already; the marker stays.
        assertTrue(w.pending)
        assertFalse(File(p.noBackup, "account.bin").exists())
        assertEquals(setOf("vettid.seed_wrap.v1"), p.keystore)
        assertTrue(LocalWipe(p.targets).resumeIfPending())
        p.assertFresh()
    }

    @Test
    fun withoutAMarkerTheStartTouchesNothing() {
        val p = Phone()
        p.enrolled()
        assertFalse(LocalWipe(p.targets).resumeIfPending())
        assertTrue(File(p.noBackup, "account.bin").exists())
        assertTrue(File(p.sharedPrefs, "vettid_device_keys.xml").exists())
        assertEquals(5, p.keystore.size)
        assertEquals(3, p.notifications)
    }

    @Test
    fun theKnownAliasesAreErasedEvenWhenTheKeystoreDoesNotListThem() {
        val p = Phone()
        val deleted = mutableListOf<String>()
        val t = object : WipeTargets by p.targets {
            override fun keyAliases(): List<String> = emptyList()
            override fun deleteKey(alias: String) {
                deleted += alias
            }
        }
        LocalWipe(t).erase()
        assertEquals(LocalWipe.KNOWN_ALIASES, deleted)
        assertEquals(
            listOf("vettid.device_attestation.v1", "vettid.seed_wrap.v1", "vettid.app_data.v1", "vettid.local_files.v1"),
            LocalWipe.KNOWN_ALIASES,
        )
    }
}
