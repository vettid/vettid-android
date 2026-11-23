package com.vettid.core.data.devices

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** AA9's folder rules, as VAULT-MESSAGING 0.93.2 §10.11 "Member folders for runs" has them. */
class RunFoldersTest {
    private fun cp(vararg c: Int) = String(c, 0, c.size)

    private val bs = "\\"

    @Test
    fun eachRefusalHasItsProblem() {
        val cases = mapOf(
            "" to FolderProblem.NOT_FULL_PATH,
            "relative/path" to FolderProblem.NOT_FULL_PATH,
            "//server/share" to FolderProblem.NOT_FULL_PATH,
            "${bs}${bs}server${bs}share" to FolderProblem.NOT_FULL_PATH,
            "C:" to FolderProblem.NOT_FULL_PATH,
            "/a//b" to FolderProblem.NOT_FULL_PATH,
            "/a/b/" to FolderProblem.NOT_FULL_PATH,
            "/a/./b" to FolderProblem.NOT_FULL_PATH,
            "/a/../b" to FolderProblem.NOT_FULL_PATH,
            "/a/b:c" to FolderProblem.NOT_FULL_PATH,
            "C:${bs}a:b" to FolderProblem.NOT_FULL_PATH,
            "/a/b" + cp(0x202E) to FolderProblem.NOT_FULL_PATH,
            "/a/b" + cp(0x0A) to FolderProblem.NOT_FULL_PATH,
            "/" + "a".repeat(4096) to FolderProblem.NOT_FULL_PATH,
            "/" to FolderProblem.WHOLE_DRIVE,
            "C:$bs" to FolderProblem.WHOLE_DRIVE,
            "d:/" to FolderProblem.WHOLE_DRIVE,
            "/a/..." to FolderProblem.DOTS_ONLY,
            "/a/. " to FolderProblem.DOTS_ONLY,
            "/a/ " to FolderProblem.DOTS_ONLY,
            "C:$bs.. ${bs}x" to FolderProblem.DOTS_ONLY,
        )
        for ((path, want) in cases) assertEquals(path, want, RunFolders.problem(path))
        for (ok in listOf("/home/you/project", "C:${bs}Users${bs}you${bs}project", "C:/Users/you", "/a/b.", "/a/.git", "/a/x y")) {
            assertNull(ok, RunFolders.problem(ok))
        }
        assertNull(RunFolders.problem("/" + "a".repeat(4095)))
        // The cap is in bytes of UTF-8, not characters: 2,048 two-byte characters and the slash are 4,097 bytes.
        assertEquals(FolderProblem.NOT_FULL_PATH, RunFolders.problem("/" + cp(0xE9).repeat(2048)))
        assertNull(RunFolders.problem("/" + cp(0xE9).repeat(2047)))
        // Line and paragraph separators, and a lone surrogate.
        assertEquals(FolderProblem.NOT_FULL_PATH, RunFolders.problem("/a" + cp(0x2028)))
        assertEquals(FolderProblem.NOT_FULL_PATH, RunFolders.problem("/a" + cp(0x2029)))
        assertEquals(FolderProblem.NOT_FULL_PATH, RunFolders.problem("/a" + Char(0xD800)))
    }

    @Test
    fun addingChecksTheListToo() {
        val list = listOf(RunFolder("/home/al/Work", false), RunFolder("C:${bs}Users${bs}al", true))
        assertEquals(FolderProblem.DUPLICATE, RunFolders.addProblem(list, "/home/al/work"))
        assertEquals(FolderProblem.DUPLICATE, RunFolders.addProblem(list, "/home/al/Work."))
        assertEquals(FolderProblem.DUPLICATE, RunFolders.addProblem(list, "c:/users/AL"))
        assertEquals(FolderProblem.NESTED, RunFolders.addProblem(list, "/home/al/WORK/sub"))
        assertEquals(FolderProblem.NESTED, RunFolders.addProblem(list, "/home"))
        assertEquals(FolderProblem.NESTED, RunFolders.addProblem(list, "C:${bs}Users"))
        // Element by element, never as a text prefix.
        assertNull(RunFolders.addProblem(list, "/home/al/Work2"))
        assertNull(RunFolders.addProblem(list, "/home/al/Wor"))
        // A deeper path elsewhere is neither under nor above an entry: the whole prefix counts, not the root.
        assertNull(RunFolders.addProblem(list, "/home/bob/x/y/z"))
        assertNull(RunFolders.addProblem(list, "C:${bs}Users${bs}bob${bs}x"))
        val full = (1..16).map { RunFolder("/f$it", false) }
        assertEquals(FolderProblem.TOO_MANY, RunFolders.addProblem(full, "/g"))
        // The path's own problem comes first.
        assertEquals(FolderProblem.WHOLE_DRIVE, RunFolders.addProblem(full, "/"))
    }

    /** The key's examples from VAULT-MESSAGING 0.93.2. */
    @Test
    fun theKeyIsTheVaults() {
        fun same(a: String, b: String) = assertEquals("$a / $b", RunFolders.elementKey(a), RunFolders.elementKey(b))
        fun differ(a: String, b: String) = assertNotEquals("$a / $b", RunFolders.elementKey(a), RunFolders.elementKey(b))
        // Composed and decomposed forms are one: café, ǰ against J + U+030C.
        same("caf" + cp(0xE9), "cafe" + cp(0x301))
        same(cp(0x1F0), "J" + cp(0x30C))
        // The dotless ı keys as I, so it merges with I and i (the upper step).
        same(cp(0x131), "I")
        same(cp(0x131), "i")
        // İ is I + U+0307 after NFD: never plain i, but one with i + U+0307.
        differ(cp(0x130), "i")
        same(cp(0x130), "i" + cp(0x307))
        // Simple folding orbits: K and the Kelvin sign, ſ and s, ß and ẞ, the three sigmas.
        same("k", cp(0x212A))
        same("s", cp(0x17F))
        same(cp(0xDF), cp(0x1E9E))
        same(cp(0x3C3), cp(0x3C2))
        same(cp(0x3A3), cp(0x3C2))
        // The U+0345 orbit folds to the mark: αί and άι merge.
        same(cp(0x3B1, 0x3AF), cp(0x3AC, 0x3B9))
        // Trailing dots and spaces are dropped; leading ones are not.
        same("Work", "work. .")
        differ("Work", ".Work")
        differ("a", "b")
    }

    @Test
    fun validListsAreTheVaultsToo() {
        assertTrue(RunFolders.valid(emptyList()))
        assertTrue(RunFolders.valid(listOf(RunFolder("/a", false), RunFolder("/b", true))))
        assertFalse(RunFolders.valid(listOf(RunFolder("/a", false), RunFolder("/A", true))))
        assertFalse(RunFolders.valid(listOf(RunFolder("/a/b", false), RunFolder("/a", true))))
        assertFalse(RunFolders.valid(listOf(RunFolder("/", false))))
        assertFalse(RunFolders.valid((1..17).map { RunFolder("/f$it", false) }))
    }

    /** The per-rune step is Go's own table (Unicode 15.0), not the phone's: the same on Android 12's older tables. */
    @Test
    fun theFoldIsGosTable() {
        // The generated data, pinned: 1,455 runes, Go 1.26's tables (Unicode 15.0.0).
        assertEquals(1455, FolderFold.size)
        val sha = java.security.MessageDigest.getInstance("SHA-256").digest(FolderFold.data().toByteArray())
            .joinToString("") { "%02x".format(it) }
        assertEquals("9b24f7b602e22b0a25b5cca5794dbcf91dc2b028037bcb1be819d0bf51890626", sha)
        // Vithkuqi (Unicode 14): a pair Android 12 does not know.
        assertEquals(0x10570, FolderFold.key(0x10597))
        assertEquals(0x10570, FolderFold.key(0x10570))
        assertEquals(RunFolders.elementKey("x" + cp(0x10570)), RunFolders.elementKey("x" + cp(0x10597)))
        // Latin A7C0/A7C1 (Unicode 14).
        assertEquals(0xA7C0, FolderFold.key(0xA7C1))
        // Go keeps İ apart (it never reaches the fold: NFD makes it I + U+0307 first); ı upper-cases to I.
        assertEquals(0x130, FolderFold.key(0x130))
        assertEquals('I'.code, FolderFold.key(0x131))
        assertEquals(0x345, FolderFold.key(0x3B9))
        assertEquals('A'.code, FolderFold.key('a'.code))
        assertEquals('A'.code, FolderFold.key('A'.code))
        assertEquals('1'.code, FolderFold.key('1'.code))
    }
}
