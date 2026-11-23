// Texts: one line each.
@file:Suppress("MaxLineLength")

package com.vettid.feature.approvals

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** ANDROID-PLAN 0.1.137 AA8's texts, word for word (owner-approved, GC #16443): a changed one is the owner's to approve. */
@RunWith(RobolectricTestRunner::class)
class AgentWayTextsTest {
    @Test
    fun theTextsAreThePlans() {
        val r = ApplicationProvider.getApplicationContext<android.content.Context>().resources
        mapOf(
            R.string.approvals_run_title to "%1${"$"}s wants to run a program with %2${"$"}s",
            R.string.approvals_run_summary to "%1${"$"}s, %2${"$"}s, %3${"$"}s in all",
            R.string.approvals_way_field to "Field: %1${"$"}s",
            R.string.approvals_run_program to "Program",
            R.string.approvals_run_fingerprint to "Fingerprint: %1${"$"}s",
            R.string.approvals_run_asked_as to "Asked for as:",
            R.string.approvals_run_arguments to "Arguments",
            R.string.approvals_run_no_arguments to "No arguments",
            R.string.approvals_run_secret_file to "(the secret file's path)",
            R.string.approvals_run_runs_in to "Runs in",
            R.string.approvals_run_new_folder to "A new, empty folder of its own",
            R.string.approvals_run_secret_given to "Secret given as",
            R.string.approvals_run_how_env to "variable %1${"$"}s",
            R.string.approvals_run_how_stdin to "standard input",
            R.string.approvals_run_how_file to "a temporary file, its path in variable %1${"$"}s",
            R.string.approvals_run_other_variables to "Other variables",
            R.string.approvals_run_none to "None",
            R.string.approvals_run_warn_shell to "This program can run other programs or code given to it, so the command can do anything with your secret. Read every argument.",
            R.string.approvals_run_warn_inline_code to "This runs code given on the command line. It can do anything with your secret.",
            R.string.approvals_run_warn_unbound to "Any program could be asked for. Approve only a command you recognize.",
            R.string.approvals_run_warn_unrecorded to "First run of this program with this item. Fingerprint: %1${"$"}s. If you approve, later runs of this exact file may run without asking, as this item's settings allow.",
            R.string.approvals_run_warn_changed to "This program has changed since you approved it. Before: %1${"$"}s. Now: %2${"$"}s. If you did not update it, deny. If you approve, this file's fingerprint replaces the recorded one, and later runs of it may run without asking, as this item's settings allow.",
            R.string.approvals_run_warn_moved to "This program moved to %1${"$"}s. It was approved at %2${"$"}s. Before: %3${"$"}s. Now: %4${"$"}s. If you did not move or update it, deny. If you approve, this file's fingerprint replaces the recorded one, and later runs of it may run without asking, as this item's settings allow.",
            R.string.approvals_run_moved_to to "moved to %1${"$"}s",
            R.string.approvals_run_approved_before to "Approved before",
            R.string.approvals_run_now to "Now",
            R.string.approvals_run_batch_file to "This is a Windows batch file. Its fingerprint covers this file only, not the programs it starts (for an npm tool, node and its packages).",
            R.string.approvals_run_runs_commands to "This program can run other commands given in its arguments. Read every argument.",
            R.string.approvals_way_approved to "Approved.",
            R.string.approvals_run_folder_read_only to "Folder: %1${'$'}s · Read only",
            R.string.approvals_run_folder_can_change to "Folder: %1${'$'}s · Can change files",
            R.string.approvals_run_warn_member_folder to "This runs in a folder of yours. The agent can change what is in it, and the program runs what it finds there.",
            R.string.approvals_run_copied_in to "Copied in, read only:",
            R.string.approvals_run_copied_back to "Copied back to %1${'$'}s when it ends:",
            R.string.approvals_run_warn_member_files to "Files saved to %1${'$'}s are new copies; nothing there is overwritten. A file that held the secret is changed before it is copied back.",
            R.string.approvals_run_pin_recorded to "This program's fingerprint is now recorded for %1${"$"}s.",
            R.string.approvals_proxy_title to "%1${"$"}s wants to use %2${"$"}s with %3${"$"}s for %4${"$"}s.",
            R.string.approvals_proxy_body to "The agent will not see it; the key is sent only to %1${"$"}s.",
            R.string.approvals_proxy_sent_as to "Sent as: %1${"$"}s: %2${"$"}s••••••••",
            R.string.approvals_proxy_ends_sooner to "It may end sooner, when %1${"$"}s's access ends.",
            R.string.approvals_way_scroll_hint to "Scroll to the end to approve.",
            R.string.approvals_way_obscured to "Another app is drawing over VettID, so Approve ignores taps. Close that app, then try again.",
            R.string.approvals_way_referred to "Not done yet: something changed while you were deciding, so you are asked again.",
            R.string.approvals_run_asked_again to "Asked again. This item's settings or its recorded program changed while you were deciding. Check the details again.",
            R.string.approvals_proxy_asked_again to "Asked again. This item's API settings changed while you were deciding. Check the details again.",
            R.string.approvals_run_program_not_allowed to "Approved, but this item no longer allows that program, so nothing was done.",
            R.string.approvals_run_env_not_allowed to "Approved, but this program may no longer receive those variables, so nothing was done.",
            R.string.approvals_run_cwd_not_allowed to "Approved, but that folder or file is no longer allowed for this item, so nothing was done.",
            R.string.approvals_run_not_found to "Approved, but the item or its field is gone, so the program was not run.",
            R.string.approvals_proxy_not_found to "Approved, but the item or its field is gone, so the key was not sent.",
            R.string.approvals_way_forbidden to "Approved, but your vault refused it, so nothing was done. %1${"$"}s may no longer have a permission that covers it, may be paused, or this request was asked again too many times.",
            R.string.approvals_way_expired to "This request expired, so nothing was done.",
        ).forEach { (id, text) -> assertEquals(text, r.getString(id)) }
    }
}
