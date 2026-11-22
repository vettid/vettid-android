package com.vettid.feature.approvals

import com.vettid.core.data.social.HeldRequest
import com.vettid.core.data.social.SecretHow

/** A run of [itemId] as AA8 reads it (still deny only in this version). */
internal fun heldRun(itemId: String) = HeldRequest.ItemRun(
    itemId = itemId, fieldId = "f1", program = "/usr/bin/git", sha256 = "ab".repeat(32), argv = listOf("git", "status"),
    cwd = null, how = SecretHow.ENV, name = "GH_TOKEN", env = emptyList(), flags = emptySet(),
    previousSha256 = null, previousPath = null, replaces = null,
)
