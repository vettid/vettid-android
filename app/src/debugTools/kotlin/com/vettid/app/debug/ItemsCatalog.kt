// Debug-only sample data for screenshots: long literal rows are clearer than wrapped ones.
@file:Suppress("MaxLineLength", "MagicNumber")

package com.vettid.app.debug

import androidx.compose.runtime.Composable
import com.vettid.core.data.items.AddressValue
import com.vettid.core.data.items.DraftField
import com.vettid.core.data.items.FieldValue
import com.vettid.core.data.items.ItemChecks
import com.vettid.core.data.items.ItemDetail
import com.vettid.core.data.items.ItemDraft
import com.vettid.core.data.items.ItemFieldView
import com.vettid.core.data.items.ItemFilter
import com.vettid.core.data.items.ListLoad
import com.vettid.core.data.items.Sensitivity
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.ui.components.ShellChrome
import com.vettid.feature.items.DetailDialog
import com.vettid.feature.items.ItemDetailActions
import com.vettid.feature.items.ItemDetailScreen
import com.vettid.feature.items.ItemDetailUiState
import com.vettid.feature.items.ItemEditActions
import com.vettid.feature.items.ItemEditScreen
import com.vettid.feature.items.ItemEditUiState
import com.vettid.feature.items.ItemsActions
import com.vettid.feature.items.ItemsScreen
import com.vettid.feature.items.ItemsUiState
import com.vettid.feature.items.PasswordPrompt
import com.vettid.feature.items.PasswordPurpose
import com.vettid.feature.items.TemplatePickerScreen
import java.time.Instant

/** The Vault screens (A5a, ANDROID-PLAN 0.1.11) with made-up items, for `--es vettid.start screen:items…`. */
internal object ItemsCatalog {
    private val chrome = ShellChrome(accountName = "Sam Rivera", onMenuClick = {}, onAvatarClick = {})
    private val t0 = Instant.now().minusSeconds(86_400 * 3)

    private fun text(id: String, label: String, kind: String, v: String?) = ItemFieldView(id, label, kind, v?.let { FieldValue.Text(it) })

    private val passport = ItemDetail(
        "01JPASSPORT00000000000000", 3, "Passport", "identity_document", Sensitivity.DATA, "passport", listOf("identity", "travel"),
        listOf(
            text("f1", "Number", "text", "C01X00T47"), text("f2", "Full name", "text", "Sam Rivera"),
            text("f3", "Nationality", "text", "German"), text("f4", "Expires", "date", "2031-04-30"),
        ),
        createdAt = t0, updatedAt = t0.plusSeconds(3_600),
    )
    private val contact = ItemDetail(
        "01JCONTACT000000000000000", 2, "Contact details", "contact", Sensitivity.DATA, "contact_card", listOf("@profile"),
        listOf(
            text("f1", "Email", "email", "sam@example.org"),
            ItemFieldView("f2", "Address", "address", FieldValue.Address(AddressValue(street = "Lindenstraße 1", postalCode = "10969", city = "Berlin", country = "DE"))),
        ),
        createdAt = t0,
    )
    private val login = ItemDetail(
        "01JLOGIN00000000000000000", 5, "Bank login", "login", Sensitivity.SECRET, "login", listOf("money"),
        listOf(
            text("f1", "Website", "url", "https://bank.example"), text("f2", "Username", "text", "sam.r"),
            text("f3", "Password", "password", "correct-horse-battery"), text("f4", "One-time code", "otp", "JBSWY3DPEHPK3PXP"),
        ),
        notes = "Branch: Kreuzberg", createdAt = t0, revealed = true,
    )
    private val phrase = ItemDetail(
        "01JPHRASE0000000000000000", 1, "Recovery phrase", "crypto_wallet", Sensitivity.CRITICAL, "recovery_phrase", listOf("crypto"),
        listOf(text("f1", "Wallet", "text", "Cold wallet"), text("f2", "Words", "multiline", "abandon ability able about above absent absorb abstract absurd abuse access accident"), text("f3", "Passphrase", "password", "")),
        createdAt = t0, revealed = true,
    )
    private val list = listOf(passport, contact, login, phrase).map { it.summary }

    private val newDraft = ItemDraft(
        "Passport", "identity_document", "passport", Sensitivity.DATA, listOf("identity", "travel"),
        listOf(DraftField(label = "Number", kind = "text", text = "C01X00T47"), DraftField(label = "Expires", kind = "date"), DraftField(label = "Issued", kind = "date")),
    )
    private val badDraft = newDraft.copy(name = "", fields = listOf(DraftField(label = "Expires", kind = "date", text = "next May"), DraftField(label = "", kind = "email", text = "sam")))
    private val profileDraft = ItemDraft.of(contact)

    private fun edit(d: ItemDraft, isNew: Boolean = true, errors: Boolean = false) =
        ItemEditUiState(itemId = if (isNew) null else "01J", draft = d, check = ItemChecks.check(d), showErrors = errors)

    private fun detail(d: ItemDetail, vararg extra: (ItemDetailUiState) -> ItemDetailUiState): ItemDetailUiState =
        extra.fold(ItemDetailUiState(d.itemId, d, loading = false)) { s, f -> f(s) }

    val screens: Map<String, @Composable () -> Unit> = linkedMapOf(
        "items" to { ItemsScreen(ItemsUiState(list, load = ListLoad.LOADED), chrome, ItemsActions()) },
        "items.filtered" to { ItemsScreen(ItemsUiState(list, ItemFilter(tag = "travel", sensitivity = Sensitivity.DATA), ListLoad.LOADED), chrome, ItemsActions()) },
        "items.empty" to { ItemsScreen(ItemsUiState(load = ListLoad.LOADED), chrome, ItemsActions()) },
        "items.no_match" to { ItemsScreen(ItemsUiState(list, ItemFilter(query = "zzz"), ListLoad.LOADED), chrome, ItemsActions()) },
        "items.error" to { ItemsScreen(ItemsUiState(load = ListLoad.FAILED, error = FailureKind.NETWORK), chrome, ItemsActions()) },
        "items.templates" to { TemplatePickerScreen(onBack = {}, onPick = {}) },
        "items.detail" to { ItemDetailScreen(detail(passport), ItemDetailActions()) },
        "items.detail_profile" to { ItemDetailScreen(detail(contact), ItemDetailActions()) },
        "items.detail_secret_hidden" to { ItemDetailScreen(detail(login.hidden()), ItemDetailActions()) },
        "items.detail_secret_revealed" to { ItemDetailScreen(detail(login, { it.copy(shown = setOf("f3")) }), ItemDetailActions()) },
        "items.detail_critical_hidden" to { ItemDetailScreen(detail(phrase.hidden()), ItemDetailActions()) },
        "items.detail_critical_open" to { ItemDetailScreen(detail(phrase), ItemDetailActions()) },
        "items.detail_critical_password" to {
            ItemDetailScreen(detail(phrase.hidden(), { it.copy(prompt = PasswordPrompt(PasswordPurpose.OPEN, error = FailureKind.BAD_PASSWORD)) }), ItemDetailActions())
        },
        "items.detail_critical_backoff" to {
            ItemDetailScreen(detail(phrase.hidden(), { it.copy(prompt = PasswordPrompt(PasswordPurpose.OPEN, retryUntil = Instant.now().plusSeconds(45))) }), ItemDetailActions())
        },
        "items.detail_protection" to { ItemDetailScreen(detail(passport, { it.copy(dialog = DetailDialog.PROTECTION_PICK) }), ItemDetailActions()) },
        "items.detail_leave_critical" to { ItemDetailScreen(detail(phrase, { it.copy(dialog = DetailDialog.LEAVE_CRITICAL, target = Sensitivity.DATA) }), ItemDetailActions()) },
        "items.detail_delete" to { ItemDetailScreen(detail(passport, { it.copy(dialog = DetailDialog.DELETE) }), ItemDetailActions()) },
        "items.detail_missing" to { ItemDetailScreen(ItemDetailUiState("01J", loading = false, missing = true), ItemDetailActions()) },
        "items.edit_new" to { ItemEditScreen(edit(newDraft), ItemEditActions()) },
        "items.edit_problems" to { ItemEditScreen(edit(badDraft, errors = true), ItemEditActions()) },
        "items.edit_profile" to { ItemEditScreen(edit(profileDraft, isNew = false), ItemEditActions()) },
        "items.edit_conflict" to { ItemEditScreen(edit(ItemDraft.of(passport), isNew = false).copy(error = FailureKind.CONFLICT), ItemEditActions()) },
        "items.edit_critical_password" to {
            ItemEditScreen(edit(ItemDraft.of(phrase)).copy(prompt = PasswordPrompt(PasswordPurpose.SAVE)), ItemEditActions())
        },
    )
}
