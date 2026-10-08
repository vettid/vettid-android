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
import com.vettid.feature.items.EditDialog
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
import com.vettid.core.data.items.GrantDirection
import com.vettid.core.data.items.GrantView
import com.vettid.core.data.items.RuleDraft
import com.vettid.core.data.items.RuleMatch
import com.vettid.core.data.items.RulePreview
import com.vettid.core.data.items.ShareMode
import com.vettid.core.data.items.ShareRule
import com.vettid.core.data.items.SharedContent
import com.vettid.core.data.items.TagChange
import com.vettid.core.data.items.TagRegistry
import com.vettid.core.data.items.TagView
import com.vettid.feature.items.ConnectionSharingActions
import com.vettid.feature.items.ConnectionSharingScreen
import com.vettid.feature.items.ConnectionSharingUiState
import com.vettid.feature.items.RuleEditActions
import com.vettid.feature.items.RuleEditScreen
import com.vettid.feature.items.RuleEditUiState
import com.vettid.feature.items.ShareImpact
import com.vettid.feature.items.SharedWithYouActions
import com.vettid.feature.items.SharedWithYouScreen
import com.vettid.feature.items.SharedWithYouUiState
import com.vettid.feature.items.TagDialog
import com.vettid.feature.items.TagsActions
import com.vettid.feature.items.TagsScreen
import com.vettid.feature.items.TagsUiState
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
    // One item per contact point (VAULT-ITEMS 0.1.1); the member put the email into their shared profile themselves.
    private val contact = ItemDetail(
        "01JCONTACT000000000000000", 2, "Email address", "contact", Sensitivity.DATA, "email_address", listOf("@profile"),
        listOf(text("f1", "Email", "email", "sam@example.org")),
        createdAt = t0,
    )
    private val home = ItemDetail(
        "01JHOME000000000000000000", 1, "Home address", "contact", Sensitivity.DATA, "postal_address", emptyList(),
        listOf(ItemFieldView("f1", "Address", "address", FieldValue.Address(AddressValue(street = "Lindenstraße 1", postalCode = "10969", city = "Berlin", country = "DE")))),
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
    private val list = listOf(passport, contact, home, login, phrase).map { it.summary }

    // A template's name is the placeholder, never pre-typed (owner request 2026-10-08).
    private val newDraft = ItemDraft(
        "", "identity_document", "passport", Sensitivity.DATA, listOf("identity", "travel"),
        listOf(DraftField(label = "Number", kind = "text", text = "C01X00T47"), DraftField(label = "Expires", kind = "date"), DraftField(label = "Issued", kind = "date")),
    )
    private val badDraft = newDraft.copy(name = "", template = null, fields = listOf(DraftField(label = "Expires", kind = "date", text = "next May"), DraftField(label = "", kind = "email", text = "sam")))
    private val profileDraft = ItemDraft.of(contact)

    /** A payment card being added (owner feedback 2026-10-08: value-first fields captioned by their labels). */
    private val cardDraft = ItemDraft(
        "", "payment_card", "payment_card", Sensitivity.SECRET, listOf("money"),
        listOf(
            DraftField(label = "Cardholder", kind = "text", text = "Sam Rivera"), DraftField(label = "Number", kind = "text"),
            DraftField(label = "Expires", kind = "date", text = "2031-04", monthYear = true), DraftField(label = "Security code", kind = "password"),
        ),
    )

    /** The sample templates' names, as the editor shows them for a new item. */
    private val templateNames = mapOf("passport" to "Passport", "payment_card" to "Payment card")

    private fun edit(d: ItemDraft, isNew: Boolean = true, errors: Boolean = false): ItemEditUiState {
        val hint = d.template?.let { templateNames[it] }?.takeIf { isNew }
        val named = if (d.name.isBlank() && hint != null) d.copy(name = hint) else d
        return ItemEditUiState(itemId = if (isNew) null else "01J", draft = d, check = ItemChecks.check(named), showErrors = errors, nameHint = hint)
    }

    private fun detail(d: ItemDetail, vararg extra: (ItemDetailUiState) -> ItemDetailUiState): ItemDetailUiState =
        extra.fold(ItemDetailUiState(d.itemId, d, loading = false)) { s, f -> f(s) }

    // --- A5b: tags and sharing ---
    private val registry = TagRegistry(
        4,
        listOf(
            TagView("@profile", 1), TagView("crypto", 1), TagView("identity", 1, description = "Passports and IDs"),
            TagView("medical", 3, listOf("01JRULE1")), TagView("money", 1), TagView("travel", 1, listOf("01JRULE2")),
        ),
    )
    private val rules = listOf(
        ShareRule("01JRULE1", 2, "c1", tags = listOf("medical"), included = listOf("i1", "i2"), pending = listOf("i3")),
        ShareRule("01JRULE2", 1, "c1", tags = listOf("travel", "identity"), match = com.vettid.core.data.items.TagMatch.ALL, mode = ShareMode.AUTO, uses = 5, expiresAt = Instant.now().plusSeconds(86_400 * 30L), included = listOf(passport.itemId)),
    )
    private val givenGrants = listOf(
        GrantView("g1", "c1", GrantDirection.GIVEN, "i1", "Allergies", "medical", ruleId = "01JRULE1"),
        GrantView("g2", "c1", GrantDirection.GIVEN, passport.itemId, "Passport", "identity_document", ruleId = "01JRULE2", uses = 5, used = 2),
        GrantView("g3", "c1", GrantDirection.GIVEN, "i7", "Insurance card", "insurance", uses = 1, expiresAt = Instant.now().plusSeconds(86_400 * 6L)),
    )
    private val received = listOf(
        GrantView("g5", "c1", GrantDirection.RECEIVED, "x1", "Practice address", "contact"),
        GrantView(
            "g6", "c1", GrantDirection.RECEIVED, "x2", "Opening hours", "note", uses = 3, used = 1,
            labels = listOf(com.vettid.core.data.items.FieldLabel("f1", "Weekdays", "text"), com.vettid.core.data.items.FieldLabel("f2", "Saturday", "text")),
        ),
        GrantView("g7", "c1", GrantDirection.RECEIVED, "x3", "Old fax number", "contact", state = "revoked"),
    )
    private val opened = mapOf(
        "g5" to SharedContent(
            "x1", "Practice address", "contact",
            listOf(ItemFieldView("f1", "Address", "address", FieldValue.Address(AddressValue(street = "Hauptstraße 5", postalCode = "10827", city = "Berlin", country = "DE"))), text("f2", "Phone", "phone", "+49 30 1234567")),
            null, null,
        ),
    )
    private val ruleDraft = RuleDraft("c1", listOf("medical"))
    private val rulePreview = RulePreview(listOf(RuleMatch("i1", "Allergies", "medical", Sensitivity.DATA), RuleMatch("i2", "Blood type", "medical", Sensitivity.DATA), RuleMatch("i9", "Signing key", "crypto_wallet", Sensitivity.CRITICAL)), 3)

    val screens: Map<String, @Composable () -> Unit> = linkedMapOf(
        "items" to { ItemsScreen(ItemsUiState(list, load = ListLoad.LOADED), chrome, ItemsActions()) },
        // The search behind the top bar's icon (owner request 2026-10-08): hidden, then open with a query.
        "items.list" to { ItemsScreen(ItemsUiState(list, load = ListLoad.LOADED), chrome, ItemsActions()) },
        "items.list_search" to { ItemsScreen(ItemsUiState(list, ItemFilter(query = "pass"), ListLoad.LOADED), chrome, ItemsActions()) },
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
        "items.edit_blank" to { ItemEditScreen(edit(ItemDraft()), ItemEditActions()) },
        "items.edit_address" to { ItemEditScreen(edit(ItemDraft.of(home), isNew = false), ItemEditActions()) },
        "items.edit_card" to { ItemEditScreen(edit(cardDraft), ItemEditActions()) },
        // An existing item's protection changed in the editor (owner request 2026-10-08), and the warning for leaving critical.
        "items.edit_protection" to {
            ItemEditScreen(edit(ItemDraft.of(login), isNew = false).copy(protectionTo = Sensitivity.CRITICAL), ItemEditActions())
        },
        "items.edit_leave_critical" to {
            ItemEditScreen(edit(ItemDraft.of(phrase), isNew = false).copy(protectionTo = Sensitivity.SECRET, confirmLeaveCritical = true), ItemEditActions())
        },
        "items.edit_saved_protection_not" to {
            ItemEditScreen(
                edit(ItemDraft.of(login), isNew = false).copy(protectionTo = Sensitivity.CRITICAL, savedButProtection = true, error = FailureKind.LIMIT),
                ItemEditActions(),
            )
        },
        "items.edit_add_field" to { ItemEditScreen(edit(cardDraft).copy(dialog = EditDialog.AddField("Billing postcode")), ItemEditActions()) },
        "items.edit_add_field_long" to { ItemEditScreen(edit(cardDraft).copy(dialog = EditDialog.AddField("x".repeat(70))), ItemEditActions()) },
        "items.edit_rename_field" to { ItemEditScreen(edit(cardDraft).copy(dialog = EditDialog.RenameField(0, "Cardholder")), ItemEditActions()) },
        "items.edit_field_type" to { ItemEditScreen(edit(cardDraft).copy(dialog = EditDialog.FieldKind(1, "text")), ItemEditActions()) },
        "items.edit_new_category" to { ItemEditScreen(edit(cardDraft).copy(dialog = EditDialog.NewCategory("Loyalty cards")), ItemEditActions()) },
        "items.edit_new_category_bad" to { ItemEditScreen(edit(cardDraft).copy(dialog = EditDialog.NewCategory("2fa codes")), ItemEditActions()) },
        "items.edit_custom_category" to {
            ItemEditScreen(edit(cardDraft.copy(category = "loyalty_cards")).copy(customCategories = listOf("gym", "loyalty_cards")), ItemEditActions())
        },
        "items.edit_conflict" to { ItemEditScreen(edit(ItemDraft.of(passport), isNew = false).copy(error = FailureKind.CONFLICT), ItemEditActions()) },
        "items.tags" to { TagsScreen(TagsUiState(registry, loading = false), TagsActions()) },
        "items.tags_edit" to { TagsScreen(TagsUiState(registry, loading = false, dialog = TagDialog.Edit(registry.tags[3], "medical", "")), TagsActions()) },
        "items.tags_rename" to {
            TagsScreen(TagsUiState(registry, loading = false, dialog = TagDialog.ConfirmRename("travel", "trips", TagChange(5, 1, 1, emptyList(), 1))), TagsActions())
        },
        "items.tags_in_use" to { TagsScreen(TagsUiState(registry, loading = false, error = FailureKind.IN_USE), TagsActions()) },
        "items.sharing" to { ConnectionSharingScreen(ConnectionSharingUiState("c1", "Dana Lee", rules, givenGrants, mapOf(passport.itemId to passport.summary), loading = false), ConnectionSharingActions()) },
        "items.sharing_empty" to { ConnectionSharingScreen(ConnectionSharingUiState("c1", "Dana Lee", loading = false), ConnectionSharingActions()) },
        "items.rule_new" to { RuleEditScreen(RuleEditUiState(ruleDraft, "Dana Lee", registry.tags.filterNot { it.reserved }.map { it.tag }, preview = rulePreview), RuleEditActions()) },
        "items.rule_edit" to {
            RuleEditScreen(
                RuleEditUiState(RuleDraft.of(rules[1]), "Dana Lee", registry.tags.filterNot { it.reserved }.map { it.tag }, com.vettid.feature.items.RuleExpiry.KEEP, "5", RulePreview(listOf(RuleMatch(passport.itemId, "Passport", "identity_document", Sensitivity.DATA)), 1)),
                RuleEditActions(),
            )
        },
        "items.shared_with_you" to { SharedWithYouScreen(SharedWithYouUiState("c1", "Dana Lee", received, opened, mapOf("g6" to "exhausted"), loading = false), SharedWithYouActions()) },
        "items.shared_with_you_empty" to { SharedWithYouScreen(SharedWithYouUiState("c1", "Dana Lee", loading = false), SharedWithYouActions()) },
        "items.edit_share_impact" to {
            ItemEditScreen(
                edit(ItemDraft.of(passport), isNew = false).copy(
                    shareImpact = listOf(ShareImpact("Dana Lee", ShareMode.AUTO), ShareImpact("Alex Kim", ShareMode.ASK), ShareImpact("Jo Park", ShareMode.ASK, withdrawn = true)),
                ),
                ItemEditActions(),
            )
        },
        "approvals.share_partial" to {
            val share = com.vettid.core.data.social.Approval.ShareDecision(
                "01JRULE1", "c1", null,
                listOf(
                    com.vettid.core.data.social.ShareItem("i1", "Allergy list", "medical", "data"),
                    com.vettid.core.data.social.ShareItem("i2", "Blood type", "medical", "data"),
                    com.vettid.core.data.social.ShareItem("i3", "Signing key", "crypto_wallet", "critical"),
                ),
                "tagged", t0, null, "Dana Lee", tags = listOf("medical"),
            )
            com.vettid.feature.approvals.ApprovalDetailScreen(
                com.vettid.feature.approvals.ApprovalDetailUiState(share.key, share, shareExcluded = setOf("i2")),
                com.vettid.feature.approvals.DecisionActions(),
            )
        },
        "settings.shared_profile_items" to {
            com.vettid.feature.settings.SharedProfileContent(
                com.vettid.feature.settings.SharedProfileUiState(
                    com.vettid.core.data.vault.AccountInfo("s***@example.org", state = "member", firstName = "Sam", lastName = "Rivera"),
                    com.vettid.core.data.vault.OwnProfile(3, "Sam", "Sam", "Rivera", "9a1f bb7d 873e eafb 494b ef94 f072 7b25"),
                    displayName = "Sam",
                    profileItems = listOf(contact.summary),
                    candidates = listOf(passport.summary),
                ),
                com.vettid.feature.settings.SharedProfileActions(),
            )
        },
        // --- A5c: grants, critical-item uses, History item names ---
        "approvals.grant_decide" to {
            val g = com.vettid.core.data.social.Approval.GrantRequest(
                "g9", "c1",
                listOf(
                    com.vettid.core.data.social.GrantEntry(
                        "item", passport.itemId, "Your passport", true, name = passport.name, category = passport.category,
                        labels = passport.fields.take(2).map { com.vettid.core.data.items.FieldLabel(it.fieldId!!, it.label, it.kind) },
                    ),
                    com.vettid.core.data.social.GrantEntry("category", "insurance", "Your insurance card", false),
                    com.vettid.core.data.social.GrantEntry("item", "01JGONE", "Your visa", false),
                ),
                1, 604_800, "Booking the trip", t0, t0.plusSeconds(86_400 * 7), "Dana Lee",
            )
            val insurance = com.vettid.core.data.items.ItemSummary("i7", 1, "Health insurance card", "insurance", Sensitivity.DATA)
            com.vettid.feature.approvals.ApprovalDetailScreen(
                com.vettid.feature.approvals.ApprovalDetailUiState(
                    g.key, g, items = listOf(passport.summary, insurance, login.summary), grantAnswers = mapOf(1 to "i7"), grantUses = 3,
                ),
                com.vettid.feature.approvals.DecisionActions(),
            )
        },
        "approvals.critical_backoff" to {
            val payload = "SGVsbG8sIFZldHRJRCE="
            val c = com.vettid.core.data.social.Approval.CriticalUse(
                "u2", "c1", "Signing key", "Key (Ed25519 seed, base64)", "sign", payload,
                com.vettid.core.data.social.ApprovalParser.payloadSha256(payload)!!, "Sign the lease", t0, t0.plusSeconds(86_400), "Dana Lee",
            )
            com.vettid.feature.approvals.ApprovalDetailScreen(
                com.vettid.feature.approvals.ApprovalDetailUiState(c.key, c, error = FailureKind.BACKOFF, retryUntil = Instant.now().plusSeconds(40)),
                com.vettid.feature.approvals.DecisionActions(),
            )
        },
        "approvals.critical_result" to {
            val c = com.vettid.core.data.social.Approval.CriticalUse("u3", "c1", "Signing key", "Key", "sign", "", "x", null, t0, null, "Dana Lee")
            com.vettid.feature.approvals.ApprovalDetailScreen(
                com.vettid.feature.approvals.ApprovalDetailUiState(c.key, c, criticalResult = "ok"),
                com.vettid.feature.approvals.DecisionActions(),
            )
        },
        "approvals.critical_unsuitable" to {
            val c = com.vettid.core.data.social.Approval.CriticalUse("u4", "c1", "Recovery phrase", "Words", "sign", "", "x", null, t0, null, "Dana Lee")
            com.vettid.feature.approvals.ApprovalDetailScreen(
                com.vettid.feature.approvals.ApprovalDetailUiState(c.key, c, criticalResult = "unsuitable"),
                com.vettid.feature.approvals.DecisionActions(),
            )
        },
        "history.items" to {
            val entries = listOf(
                com.vettid.core.data.vault.AuditRecord("e5", 5, Instant.now().minusSeconds(60), "item.revealed", ref = login.itemId),
                com.vettid.core.data.vault.AuditRecord("e4", 4, Instant.now().minusSeconds(600), "share.included", connectionId = "c1", ref = passport.itemId),
                com.vettid.core.data.vault.AuditRecord("e3", 3, Instant.now().minusSeconds(3_600), "item.updated", ref = phrase.itemId),
                com.vettid.core.data.vault.AuditRecord("e2", 2, Instant.now().minusSeconds(7_200), "item.deleted", ref = "01JGONE"),
            )
            com.vettid.feature.history.HistoryScreen(
                com.vettid.feature.history.HistoryUiState(
                    entries, connectionNames = mapOf("c1" to "Dana Lee"), itemNames = list.associate { it.itemId to it.name }, loading = false, end = true,
                ),
                chrome,
                com.vettid.feature.history.HistoryActions(),
            )
        },
        "history.entry_item" to {
            com.vettid.feature.history.HistoryEntryScreen(
                com.vettid.feature.history.HistoryEntryUiState(
                    com.vettid.core.data.vault.AuditRecord("e5", 5, Instant.now().minusSeconds(60), "item.revealed", ref = login.itemId, hash = "AAECAwQ="),
                    loading = false, itemName = login.name, itemExists = true,
                ),
                onBack = {},
            )
        },
        "items.shared_ask" to {
            SharedWithYouScreen(
                SharedWithYouUiState(
                    "c1", "Dana Lee", received, loading = false,
                    requested = listOf(
                        com.vettid.core.data.items.GrantAsk(
                            "q1", "c1", listOf(com.vettid.core.data.items.GrantAskEntry("category", "medical", "Your vaccination record")), "pending",
                        ),
                        com.vettid.core.data.items.GrantAsk("q2", "c1", listOf(com.vettid.core.data.items.GrantAskEntry("category", "insurance")), "denied"),
                    ),
                    ask = com.vettid.feature.items.GrantAskForm("medical", "Your vaccination record", "For school"),
                ),
                SharedWithYouActions(),
            )
        },
        "items.edit_critical_password" to {
            ItemEditScreen(edit(ItemDraft.of(phrase)).copy(prompt = PasswordPrompt(PasswordPurpose.SAVE)), ItemEditActions())
        },
        // --- VAULT-MESSAGING 0.21.0: kept values, the room left, named limits, suitability ---
        "items.edit_secret_kept" to {
            ItemEditScreen(edit(ItemDraft.of(login.hidden().copy(size = 61_000)), isNew = false), ItemEditActions())
        },
        "items.edit_critical_kept" to {
            ItemEditScreen(edit(ItemDraft.of(phrase.hidden().copy(size = 9_800)), isNew = false), ItemEditActions())
        },
        "items.edit_limit" to {
            ItemEditScreen(
                edit(ItemDraft.of(passport), isNew = false).copy(
                    error = FailureKind.LIMIT,
                    limit = com.vettid.core.data.vault.VaultLimit("share_pending", 4_096),
                ),
                ItemEditActions(),
            )
        },
        "approvals.critical_suitable" to {
            val payload = "SGVsbG8sIFZldHRJRCE="
            val c = com.vettid.core.data.social.Approval.CriticalUse(
                "u5", "c1", "Signing key", "Key", "sign", payload,
                com.vettid.core.data.social.ApprovalParser.payloadSha256(payload)!!, "Sign the lease", t0, t0.plusSeconds(86_400), "Dana Lee",
                kind = "password",
            )
            com.vettid.feature.approvals.ApprovalDetailScreen(
                com.vettid.feature.approvals.ApprovalDetailUiState(c.key, c),
                com.vettid.feature.approvals.DecisionActions(),
            )
        },
        "approvals.critical_unsuitable_kind" to {
            val payload = "SGVsbG8sIFZldHRJRCE="
            val c = com.vettid.core.data.social.Approval.CriticalUse(
                "u6", "c1", "Bank login", "Website", "auth", payload,
                com.vettid.core.data.social.ApprovalParser.payloadSha256(payload)!!, null, t0, t0.plusSeconds(86_400), "Dana Lee",
                kind = "url",
            )
            com.vettid.feature.approvals.ApprovalDetailScreen(
                com.vettid.feature.approvals.ApprovalDetailUiState(c.key, c),
                com.vettid.feature.approvals.DecisionActions(),
            )
        },
    )
}
