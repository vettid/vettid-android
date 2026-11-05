package com.vettid.app.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source rules that keep the daily owner check (VAULT-MESSAGING §3.6.5) whole, as iOS keeps them with a lint rule:
 *  - only the listed files report input in progress or wipe secrets (`HoldsInput(`, `WipesOnCover(`): a new form screen is a
 *    deliberate addition to the list, with its condition in a tested UI-state property;
 *  - no screen opens a window of its own (a dialog, sheet, menu, popup) except through the wrappers in `:core:ui`
 *    `ShellOverlays.kt`, which are not composed while the check covers the shell. The root dialogs of `VettIdApp.kt` (the app
 *    lock and the vault's start, outside the shell) are the one exception.
 * (detekt's `ForbiddenImport` refuses the imports; this also refuses fully qualified uses, which no detekt rule without type
 * resolution can.)
 */
class ShellOverlayRulesTest {
    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").exists() }

    private fun sources(): List<File> = listOf("feature", "app/src")
        .map { File(root, it) }
        .flatMap { dir -> dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
        .filterNot {
            val p = it.path.replace('\\', '/')
            "/src/test" in p || "/androidTest/" in p || "/build/" in p
        }

    private fun rel(f: File) = f.relativeTo(root).path.replace('\\', '/')

    @Test
    fun `only the listed screens report input in progress or wipe secrets`() {
        val callers = sources().filter { f -> f.readLines().any { Regex("""\b(HoldsInput|WipesOnCover)\(""").containsMatchIn(it) } }
            .map(::rel).toSortedSet()
        val allowed = setOf(
            "feature/approvals/src/main/kotlin/com/vettid/feature/approvals/ApprovalsScreen.kt",
            "feature/connections/src/main/kotlin/com/vettid/feature/connections/ConnectFlowScreens.kt",
            "feature/connections/src/main/kotlin/com/vettid/feature/connections/ShareLocation.kt",
            "feature/connections/src/main/kotlin/com/vettid/feature/connections/LocationViews.kt",
            "feature/items/src/main/kotlin/com/vettid/feature/items/ItemEditScreen.kt",
            "feature/items/src/main/kotlin/com/vettid/feature/items/RuleEditScreen.kt",
            "feature/items/src/main/kotlin/com/vettid/feature/items/SharingScreens.kt",
            "feature/items/src/main/kotlin/com/vettid/feature/items/TagsScreen.kt",
            "feature/messages/src/main/kotlin/com/vettid/feature/messages/MessagesScreen.kt",
            "feature/settings/src/main/kotlin/com/vettid/feature/settings/MapServerSettings.kt",
            "feature/settings/src/main/kotlin/com/vettid/feature/settings/ProfileScreens.kt",
            "feature/settings/src/main/kotlin/com/vettid/feature/settings/SettingsDetailScreens.kt",
            "feature/settings/src/main/kotlin/com/vettid/feature/settings/TransferOutScreen.kt",
            "feature/wallet/src/main/kotlin/com/vettid/feature/wallet/ChainServerScreen.kt",
            "feature/wallet/src/main/kotlin/com/vettid/feature/wallet/ImportScreen.kt",
            "feature/wallet/src/main/kotlin/com/vettid/feature/wallet/SendScreen.kt",
            "feature/wallet/src/main/kotlin/com/vettid/feature/wallet/SpendingLimitsScreen.kt",
            "feature/wallet/src/main/kotlin/com/vettid/feature/wallet/WalletScreens.kt",
        )
        assertTrue("sources not found from $root", callers.isNotEmpty())
        val extra = callers - allowed
        assertTrue("HoldsInput/WipesOnCover in files not on the list (add them here, with a tested holdsInput): $extra", extra.isEmpty())
        val gone = allowed - callers
        assertTrue("files on the list that no longer report (remove them): $gone", gone.isEmpty())
    }

    @Test
    fun `no window of its own is opened outside the wrappers`() {
        val forbidden = Regex(
            """androidx\.compose\.material3\.(AlertDialog|ModalBottomSheet|DatePickerDialog|DropdownMenu|ExposedDropdownMenu)\b""" +
                """|androidx\.compose\.ui\.window\.(Dialog|Popup)\b|(?<![\w.])Popup\(""",
        )
        val offenders = sources()
            .filterNot { rel(it) == "app/src/main/kotlin/com/vettid/app/ui/VettIdApp.kt" }
            .filter { f ->
                f.readLines().any { l ->
                    val t = l.trimStart()
                    !t.startsWith("//") && !t.startsWith("*") && forbidden.containsMatchIn(l)
                }
            }
            .map(::rel)
        assertTrue("windows of their own outside core/ui ShellOverlays.kt: $offenders", offenders.isEmpty())
    }
}
