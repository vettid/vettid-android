// Test data: long literal rows are clearer than wrapped ones.
@file:Suppress("MaxLineLength")

package com.vettid.feature.devices

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** AA8 "How a string is shown", the fingerprints and the program advice (Robolectric: the Unicode properties). */
@RunWith(RobolectricTestRunner::class)
class ItemWaysTextTest {
    private fun cp(vararg c: Int) = String(c, 0, c.size)

    @Test
    fun plainAsciiIsOneRun() {
        assertEquals(listOf(ShownPiece.Text("/usr/bin/git", true)), ShownString.pieces("/usr/bin/git"))
        assertFalse(ShownString.lookalike(ShownString.pieces("git")))
        assertEquals(emptyList<ShownPiece>(), ShownString.pieces(""))
    }

    @Test
    fun spacesAreMarked() {
        assertEquals(
            listOf(ShownPiece.Space, ShownPiece.Text("a", true), ShownPiece.Space, ShownPiece.Space),
            ShownString.pieces(" a  "),
        )
    }

    /** Every class AA8 names is escaped: controls, formats (bidi), separators, ignorables, other blanks, the braille blank. */
    @Test
    fun escapes() {
        for (c in listOf(0x01, 0x7F, 0x202E, 0x200B, 0x2066, 0x2028, 0x2029, 0x00A0, 0x3000, 0x3164, 0x115F, 0xFE0F, 0x2800, 0xE000, 0xFEFF)) {
            val p = ShownString.pieces("a" + cp(c) + "b")
            assertEquals("U+%04X".format(c), listOf(ShownPiece.Text("a", true), ShownPiece.Escape(c), ShownPiece.Text("b", true)), p)
        }
        assertEquals("U+202E", ShownPiece.Escape(0x202E).label)
        assertEquals("U+E0041", ShownPiece.Escape(0xE0041).label)
    }

    /** A combining mark at the start, after a separator, an escape or a space is escaped; after a letter it is shown. */
    @Test
    fun combiningMarksAtAnEdge() {
        val acute = 0x0301
        assertEquals(ShownPiece.Escape(acute), ShownString.pieces(cp(acute) + "a").first())
        assertEquals(ShownPiece.Escape(acute), ShownString.pieces("/" + cp(acute))[1])
        assertEquals(ShownPiece.Escape(acute), ShownString.pieces("\\" + cp(acute))[1])
        assertEquals(ShownPiece.Escape(acute), ShownString.pieces(" " + cp(acute))[1])
        assertEquals(ShownPiece.Escape(acute), ShownString.pieces(cp(0x200B, acute))[1])
        // After a letter it stays with it, highlighted (not printable ASCII).
        assertEquals(listOf(ShownPiece.Text("e", true), ShownPiece.Text(cp(acute), false)), ShownString.pieces("e" + cp(acute)))
    }

    /** Right-to-left letters and look-alikes are shown in their order, highlighted, with the look-alike line. */
    @Test
    fun lookAlikesAndRightToLeft() {
        val cyrillicA = cp(0x0430) // looks like "a"
        val p = ShownString.pieces("b" + cyrillicA + "sh")
        assertEquals(listOf(ShownPiece.Text("b", true), ShownPiece.Text(cyrillicA, false), ShownPiece.Text("sh", true)), p)
        assertTrue(ShownString.lookalike(p))
        val hebrew = cp(0x05D0, 0x05D1)
        assertEquals(listOf(ShownPiece.Text(hebrew, false)), ShownString.pieces(hebrew))
    }

    @Test
    fun fingerprints() {
        val sha = "1a2b3c4d5e6f7a8b" + "0".repeat(48)
        assertEquals("1a2b 3c4d 5e6f 7a8b", Fingerprints.short(sha))
        assertEquals(16, Fingerprints.full(sha).split(" ").size)
    }

    /** vettid/vettid.org#636: compared as the `shell` flag compares (any case, endings and trailing dots and spaces). */
    @Test
    fun programsThatRunCommands() {
        for (p in listOf("git", "/usr/bin/git", "GIT.EXE", "C:/Program Files/Git/cmd/git.exe", "npm.cmd", "npm.cmd.", "pip3.12", "pip3", "docker", "gh", "zip.exe.cmd", "\u212Aubectl")) {
            assertTrue(p, ProgramAdvice.runsCommands(p))
        }
        for (p in listOf("gitk", "python", "pipx", "bash", ".cmd", "pip-audit", "/usr/bin/curl")) {
            assertFalse(p, ProgramAdvice.runsCommands(p))
        }
    }

    @Test
    fun batchFiles() {
        for (p in listOf("C:/tools/run.cmd", "RUN.BAT", "x.cmd. ", "C:\\a\\b.Bat")) assertTrue(p, ProgramAdvice.batchFile(p))
        for (p in listOf("run.exe", "cmd", "/usr/bin/batch")) assertFalse(p, ProgramAdvice.batchFile(p))
    }

    /** The plan's texts, word for word (owner-approved 2026-11-22, GC #16443); a changed text is the owner's to approve. */
    @Test
    fun thePlansWordsAreUnchanged() {
        val r = ApplicationProvider.getApplicationContext<Context>().resources
        val texts = listOf(
            R.string.ways_items_title to "Items it may use",
            R.string.ways_item_gone to "An item you no longer have",
            R.string.ways_short_use to "Use without seeing it",
            R.string.ways_short_run to "Run programs",
            R.string.ways_short_proxy to "Send to an API",
            R.string.ways_short_reveal to "May be shown",
            R.string.ways_short_join to " · ",
            R.string.ways_use to "Use without seeing it",
            R.string.ways_use_note to "Your vault uses the value for the agent and gives it only the result.",
            R.string.ways_run to "Run programs with it",
            R.string.ways_proxy to "Send it to an API for the agent",
            R.string.ways_reveal to "May be shown to agents",
            R.string.ways_reveal_note to "Off: the agent can use it without seeing it. On: the agent's app may keep the value in its history.",
            R.string.ways_run_off_body_folders to "The programs you listed, their variables, their recorded fingerprints and the folders runs may use are removed.",
            R.string.ways_run_off_body to "The programs you listed, their variables and their recorded fingerprints are removed.",
            R.string.ways_proxy_off_body to "The hosts and the header you set are removed.",
            R.string.ways_reveal_on_body to "You are asked each time before it sees the value. Once you approve, the value goes to the agent's app, which may keep it in its history or logs. It may also reach the AI service that the agent's app uses. VettID can't remove it from any of these places.",
            R.string.ways_programs_title to "Programs it may run",
            R.string.ways_programs_none to "No programs listed. The agent may ask to run any program, and every run asks you.",
            R.string.ways_program_hint to "A full path, such as /usr/bin/git or C:\\Program Files\\Git\\cmd\\git.exe, or a program name the connector finds on its own search path, such as git (on Windows, git.exe).",
            R.string.ways_program_not_path to "Give a full path or a program name.",
            R.string.ways_program_characters to "This can't hold a control or invisible character.",
            R.string.ways_program_too_long to "At most 4,096 bytes.",
            R.string.ways_program_parts to "A path can't hold an empty part, . or .., or a colon after the drive.",
            R.string.ways_program_network to "A network path (\\\\server\\…) can't be used.",
            R.string.ways_program_tilde to "Remove the ~ from the program's name; on Windows, give its full long name.",
            R.string.ways_program_duplicate to "This program is already in the list.",
            R.string.ways_program_too_many to "You can add at most 16 programs.",
            R.string.ways_variables_none to "None. The agent can't give this program any other variables.",
            R.string.ways_variable_note to "The agent can then give this program any value it chooses in this variable.",
            R.string.ways_variable_form to "Capital letters, digits and _ only, not starting with a digit, at most 64.",
            R.string.ways_variable_too_many to "At most 32 variables.",
            R.string.ways_change_program_note to "Changing the program forgets its fingerprint.",
            R.string.ways_pin_none to "No fingerprint yet. The first run asks you, and approving it records that file.",
            R.string.ways_pin_forgotten to "Fingerprint forgotten when you save",
            R.string.ways_each to "Ask me before each run",
            R.string.ways_each_needs_program to "List a program to turn this off.",
            R.string.ways_each_off_help to "A run of a listed program goes ahead without asking when its file matches the recorded fingerprint. A first or changed program, and runs in your folders or with files, still ask you every time. Shells, interpreters and launchers your vault recognizes by name still ask every time. Other programs that can run commands, such as git or npm, do not.",
            R.string.ways_batch_file to "This is a Windows batch file. Its fingerprint covers this file only, not the programs it starts (for an npm tool, node and its packages).",
            R.string.ways_hosts_title to "Hosts it may be sent to",
            R.string.ways_host_hint to "A host name, such as api.example.com, with :port if it is not 443.",
            R.string.ways_host_ip to "An IP address can't be used; give the host's name.",
            R.string.ways_host_local to "localhost and names without a dot can't be used.",
            R.string.ways_header_bad to "This header can't carry the key.",
            R.string.ways_prefix_bad to "At most 32 plain characters: letters, digits, spaces and punctuation.",
            R.string.ways_sent_note to "The key is sent only to these hosts, over a verified connection. The agent is not given the key.",
            R.string.ways_saved to "Saved",
            R.string.ways_locked_again to "Your credential locked again before the settings were saved. Try again.",
            R.string.ways_conflict to "Another device changed these settings. Here are the current ones.",
            R.string.ways_alarm to "Settings can't be widened while a credential alarm is open",
            R.string.ways_read_only to "This item has settings this version of VettID can't show. Update VettID to change them.",
            R.string.ways_read_only_locked to "Your vault asked for your credential password for this change, so it was not saved. Update VettID to change these settings.",
            R.string.ways_changed_elsewhere to "Another device changed these settings.",
            R.string.ways_show_current to "Show the current ones",
            R.string.ways_string_empty to "(empty)",
            R.string.ways_string_lookalike to "Some characters here may look like others.",
        )
        for ((id, want) in texts) assertEquals(want, r.getString(id))
        assertEquals("How Claude may use GitHub token", r.getString(R.string.ways_title, "Claude", "GitHub token"))
        assertEquals("Let Claude see GitHub token?", r.getString(R.string.ways_reveal_on_title, "Claude", "GitHub token"))
        assertEquals("Sent as: Authorization: Bearer ••••••••", r.getString(R.string.ways_sent_as, "Authorization", "Bearer "))
        assertEquals(
            "Letting Claude do more with GitHub token needs your credential password, so only you can do it. Enter your credential password.",
            r.getString(R.string.ways_password_body, "Claude", "GitHub token"),
        )
        assertEquals("These settings could not be saved. Your vault answered: bad env.", r.getString(R.string.ways_bad_request, "bad env"))
        assertEquals(
            "These settings could not be saved: your vault refused one of them. Check the programs, the variables, the folders, the hosts, the header name and the prefix.",
            r.getString(R.string.ways_bad_request_fallback),
        )
        assertEquals(
            "git can run other commands given in its arguments, so the agent could use it to run any command with your secret. Asking before each run is safer for it.",
            r.getString(R.string.ways_runs_commands, "git"),
        )
    }

    /** AA7 "Saving" (0.1.138): 1 to 200 bytes of printable ASCII are shown as they are; anything else is not. */
    @Test
    fun theVaultsReasonIsShownOnlyWhenPrintableAscii() {
        assertEquals("run.programs[0].env[1]: VETTID_IN is reserved", VaultReason.shown("run.programs[0].env[1]: VETTID_IN is reserved"))
        assertEquals("x".repeat(200), VaultReason.shown("x".repeat(200)))
        assertEquals(" ~", VaultReason.shown(" ~"))
        for (bad in listOf(null, "", "x".repeat(201), "a\tb", "a\nb", "caf\u00e9", "a\u007fb")) {
            assertEquals(bad, null, VaultReason.shown(bad))
        }
    }

    /** The string rule inside a sentence: isolated and forced left to right, escapes as markers, spaces marked. */
    @Test
    fun theInlineFormOfTheStringRule() {
        val rlo = "\u202E"
        assertEquals("\u2066\u202Dgit\u202C\u2069", ShownString.inline("git", "(empty)"))
        assertEquals("\u2066\u202Dg⟨U+202E⟩it·x\u202C\u2069", ShownString.inline("g" + rlo + "it x", "(empty)"))
    }

    /** Nothing is highlighted inline, so every character outside printable ASCII is a marker, a literal one included. */
    @Test
    fun theInlineFormMarksEverythingOutsideAscii() {
        fun inner(s: String) = ShownString.inline(s, "(empty)").removePrefix("\u2066\u202D").removeSuffix("\u202C\u2069")
        assertEquals("g⟨U+0456⟩t", inner("g\u0456t"))
        assertEquals("a⟨U+00B7⟩b", inner("a\u00B7b"))
        assertEquals("⟨U+27E8⟩U+202E⟨U+27E9⟩", inner("\u27E8U+202E\u27E9"))
        assertEquals("caf⟨U+00E9⟩", inner("caf\u00E9"))
        assertEquals("⟨(empty)⟩", inner(""))
    }
}
