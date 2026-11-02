package com.vettid.app.ui

import com.vettid.app.R
import com.vettid.core.data.vault.ChangeCheck
import com.vettid.core.data.vault.ChangeKind
import com.vettid.core.data.vault.FailureKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.File

/** The texts of a reset whose answer was lost (VAULT-MESSAGING 0.48.0, ANDROID-PLAN 0.1.51; drafts shared with the iPhone app). */
class ResetTextsTest {
    /** The one-time notice for each decision of a reset. */
    @Test
    fun theNoticeOfAResetIsItsOwn() {
        val k = ChangeKind.RESET
        assertEquals(R.string.root_reset_applied, changeDecidedText(ChangeCheck.Applied(k)))
        assertEquals(R.string.root_reset_not_applied, changeDecidedText(ChangeCheck.NotApplied(k)))
        assertEquals(R.string.root_reset_not_applied, changeDecidedText(ChangeCheck.Refused(k, FailureKind.BAD_PASSWORD)))
        assertEquals(R.string.root_reset_ended, changeDecidedText(ChangeCheck.Ended(k)))
        // Another phone became the holder: the existing title, as for the other changes.
        assertEquals(com.vettid.core.data.R.string.data_feed_device_replaced, changeDecidedText(ChangeCheck.Moved(k)))
        // The other changes keep theirs.
        assertEquals(R.string.root_change_password_applied, changeDecidedText(ChangeCheck.Applied(ChangeKind.PASSWORD)))
        assertEquals(R.string.root_change_pin_ended, changeDecidedText(ChangeCheck.Ended(ChangeKind.PIN)))
    }

    private fun values(path: String): Map<String, String> {
        val f = listOf(File(path), File("app/$path")).first { it.exists() }
        return Regex("""<string name="([^"]+)">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(f.readText()).associate { it.groupValues[1] to it.groupValues[2] }
    }

    /** The screen's copy and the shell's copy of a draft text are the same words, so that they cannot drift. */
    @Test
    fun theDuplicatedDraftTextsAreEqual() {
        val screen = values("../feature/credential/src/main/res/values/strings_new_credential.xml")
        val shell = values("src/main/res/values/strings.xml")
        for ((a, b) in listOf("credential_new_not_made" to "root_reset_not_applied", "credential_new_ended" to "root_reset_ended")) {
            assertNotNull(a, screen[a])
            assertNotNull(b, shell[b])
            assertEquals("$a = $b", screen[a], shell[b])
        }
    }
}
