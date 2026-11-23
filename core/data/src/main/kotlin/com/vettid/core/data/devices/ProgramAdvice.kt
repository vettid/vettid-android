package com.vettid.core.data.devices

/** Advice on a listed program (AA7 "Run settings"; AA8 "A batch file's fingerprint"). Pure. */
object ProgramAdvice {
    private val ENDINGS = listOf(".exe", ".com", ".cmd", ".bat", ".ps1")

    /** vettid/vettid.org#636: programs that can run other commands given in their arguments. */
    val RUNS_COMMANDS = setOf(
        "git", "rsync", "tar", "sed", "npm", "yarn", "pnpm", "uv", "dotnet", "make", "find", "vim", "nvim", "vi", "ex", "cargo",
        "go", "gradle", "mvn", "pip", "pip3", "docker", "podman", "kubectl", "terraform", "gh", "scp", "sftp", "less", "ed", "zip",
    )
    private val VERSIONED_PIP = Regex("^pip[0-9][0-9.]*$")

    /**
     * The last path element as VAULT-MESSAGING §10.11's `shell` flag compares it (0.91.1): any case, without any number
     * of final `.exe`, `.com`, `.cmd`, `.bat` or `.ps1` endings, with trailing dots and spaces trimmed after each.
     */
    fun nameKey(program: String): String {
        var k = ItemWaysRules.simpleLower(ItemWaysRules.lastElement(program)).trimEnd(' ', '.')
        while (true) {
            val e = ENDINGS.firstOrNull { k.endsWith(it) } ?: break
            k = k.removeSuffix(e).trimEnd(' ', '.')
        }
        return k
    }

    /** The #636 advice applies: a listed name, or `pip` with digits and dots appended. */
    fun runsCommands(program: String): Boolean {
        val k = nameKey(program)
        return k.isNotEmpty() && (k in RUNS_COMMANDS || VERSIONED_PIP.matches(k))
    }

    /** A Windows batch file: the last part ends in `.cmd` or `.bat`, any case, trailing dots and spaces dropped. */
    fun batchFile(program: String): Boolean {
        val last = ItemWaysRules.simpleLower(ItemWaysRules.lastElement(program)).trimEnd(' ', '.')
        return last.endsWith(".cmd") || last.endsWith(".bat")
    }
}
