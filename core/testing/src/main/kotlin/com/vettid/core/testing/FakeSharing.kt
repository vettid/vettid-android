package com.vettid.core.testing

import com.vettid.core.data.items.FetchOutcome
import com.vettid.core.data.items.GrantLists
import com.vettid.core.data.items.GrantView
import com.vettid.core.data.items.RuleDraft
import com.vettid.core.data.items.RulePreview
import com.vettid.core.data.items.ShareRule
import com.vettid.core.data.items.SharedContent
import com.vettid.core.data.items.SharingRepository
import com.vettid.core.data.items.TagChange
import com.vettid.core.data.items.TagRegistry
import com.vettid.core.data.items.TagView
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.VaultFailure
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * TEST ONLY. Tags, share rules and grants in memory for ViewModel tests: calls are recorded in [calls]; [fail]
 * makes the next call of a name throw; [inUse] tags refuse deletion as the vault does (`in_use`).
 */
@Suppress("TooManyFunctions")
class FakeSharing : SharingRepository {
    val calls = mutableListOf<String>()
    val fail = mutableMapOf<String, VaultFailure>()
    var registry = TagRegistry(1, emptyList())
    val rulesStored = mutableListOf<ShareRule>()
    var given = mutableListOf<GrantView>()
    var received = mutableListOf<GrantView>()
    var requested = mutableListOf<com.vettid.core.data.items.GrantAsk>()
    var preview = RulePreview(emptyList(), 0)
    var change = TagChange(1, 0)
    val contents = mutableMapOf<String, SharedContent>()
    val refusals = mutableMapOf<String, String>()
    val saved = mutableListOf<RuleDraft>()
    private var next = 1

    override val tags = MutableStateFlow<TagRegistry?>(null)

    private fun call(n: String) {
        calls += n
        fail.remove(n)?.let { throw it }
    }

    override suspend fun refreshTags(): TagRegistry {
        call("refreshTags")
        tags.value = registry
        return registry
    }

    override suspend fun setTag(tag: String, description: String?, color: String?) {
        call("setTag:$tag")
        registry = registry.copy(
            version = registry.version + 1,
            tags = registry.tags.filterNot { it.tag == tag } + TagView(tag, description = description),
        )
        tags.value = registry
    }

    override suspend fun renameTag(from: String, into: String, dryRun: Boolean): TagChange {
        call(if (dryRun) "renameDry:$from>$into" else "rename:$from>$into")
        if (!dryRun) {
            registry = registry.copy(tags = registry.tags.map { if (it.tag == from) it.copy(tag = into) else it })
            tags.value = registry
        }
        return change
    }

    override suspend fun deleteTag(tag: String, dryRun: Boolean): TagChange {
        call(if (dryRun) "deleteDry:$tag" else "delete:$tag")
        if (registry.tags.firstOrNull { it.tag == tag }?.rules?.isNotEmpty() == true) throw VaultFailure(FailureKind.IN_USE, "in_use")
        if (!dryRun) {
            registry = registry.copy(tags = registry.tags.filterNot { it.tag == tag })
            tags.value = registry
        }
        return change
    }

    override suspend fun rules(connectionId: String?): List<ShareRule> {
        call("rules")
        return rulesStored.filter { connectionId == null || it.connectionId == connectionId }
    }

    override suspend fun preview(draft: RuleDraft): RulePreview {
        call("preview")
        return preview
    }

    override suspend fun saveRule(draft: RuleDraft): ShareRule {
        call("saveRule")
        saved += draft
        val r = ShareRule(
            draft.ruleId ?: "01RULE${next++}", (draft.version ?: 0) + 1, draft.connectionId, null, draft.tags, draft.match, draft.mode,
            draft.uses, draft.expiresAt, draft.includeExisting,
        )
        rulesStored.removeAll { it.ruleId == r.ruleId }
        rulesStored += r
        return r
    }

    override suspend fun deleteRule(ruleId: String) {
        call("deleteRule")
        rulesStored.removeAll { it.ruleId == ruleId }
    }

    override suspend fun grants(): GrantLists {
        call("grants")
        return GrantLists(given.toList(), received.toList(), requested.toList())
    }

    override suspend fun revokeGrant(grantId: String) {
        call("revoke:$grantId")
        given = given.map { if (it.grantId == grantId) it.copy(state = "revoked") else it }.toMutableList()
        received = received.map { if (it.grantId == grantId) it.copy(state = "revoked") else it }.toMutableList()
    }

    val requests = mutableListOf<List<String?>>()

    override suspend fun requestGrant(connectionId: String, category: String, label: String?, reason: String?): String {
        call("requestGrant")
        requests += listOf(connectionId, category, label, reason)
        return "01REQ${requests.size}"
    }

    override suspend fun fetchShared(grantId: String): FetchOutcome {
        call("fetch:$grantId")
        refusals[grantId]?.let { return FetchOutcome.Refused(it) }
        return contents[grantId]?.let { FetchOutcome.Shared(it) } ?: FetchOutcome.Refused("unavailable")
    }
}
