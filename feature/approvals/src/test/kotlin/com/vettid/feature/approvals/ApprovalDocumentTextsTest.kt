@file:Suppress("MaxLineLength") // The approved texts, one a line.

package com.vettid.feature.approvals

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The texts of an approval's document (VAULT-MESSAGING 0.89.0 §10.4 "Approval documents"), owner-approved 2026-11-17 as
 * vettid-ios #300 (its Localizable.xcstrings at e8829d1f), word for word, with the accessibility labels.
 */
@RunWith(RobolectricTestRunner::class)
class ApprovalDocumentTextsTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @Test
    fun theApprovedTextsWordForWord() {
        val approved = mapOf(
            R.string.approvals_doc_asked to "What is asked",
            R.string.approvals_doc_description to "The service's description",
            R.string.approvals_doc_description_note to "What you approve is the request below",
            R.string.approvals_doc_summary to "The service's summary",
            R.string.approvals_doc_request to "The request",
            R.string.approvals_doc_details to "Request details",
            R.string.approvals_doc_title_proposal_create to "New vote",
            R.string.approvals_doc_title_vote_extend to "Extend a vote",
            R.string.approvals_doc_title_admin_set_add to "Add an admin",
            R.string.approvals_doc_title_admin_set_remove to "Remove an admin",
            R.string.approvals_doc_title_admin_repin to "Re-pin an admin's key",
            R.string.approvals_doc_title_contract_template_change to "Change the contract template",
            R.string.approvals_doc_title_assignment_push to "Send an assignment",
            R.string.approvals_doc_cant_show to "This request can't be shown",
            R.string.approvals_doc_loading to "Getting the request…",
            R.string.approvals_doc_retry to "Try again",
            R.string.approvals_doc_read_to_end to "Scroll to the end of the request to approve it.",
            R.string.approvals_doc_field_title to "Title",
            R.string.approvals_doc_field_motion to "Motion",
            R.string.approvals_doc_none to "None",
            R.string.approvals_doc_field_choices to "Choices",
            R.string.approvals_doc_abstain to "Abstain",
            R.string.approvals_doc_abstain_added to "added by the service",
            R.string.approvals_doc_field_quorum to "Quorum",
            R.string.approvals_doc_binding to "Binding",
            R.string.approvals_doc_advisory to "Advisory",
            R.string.approvals_doc_field_removed_may_vote to "Members removed after the roster date may vote",
            R.string.approvals_doc_yes to "Yes",
            R.string.approvals_doc_no to "No",
            R.string.approvals_doc_field_opens to "Opens",
            R.string.approvals_doc_field_closes to "Closes",
            R.string.approvals_doc_field_outage_rule to "Outage rule",
            R.string.approvals_doc_field_vote to "Vote",
            R.string.approvals_doc_field_new_close to "New close",
            R.string.approvals_doc_field_outage_from to "Outage from",
            R.string.approvals_doc_field_outage_to to "Outage to",
            R.string.approvals_doc_field_reported_by to "Reported by",
        )
        for ((id, text) in approved) assertEquals(text, context.getString(id))
        assertEquals(
            "\u2068Example Co-op\u2069 asks for something this app doesn't know. Read all of it before you approve.",
            context.getString(R.string.approvals_doc_unknown_warning, "\u2068Example Co-op\u2069"),
        )
        assertEquals("1 ballot", context.resources.getQuantityString(R.plurals.approvals_doc_ballots, 1, 1))
        assertEquals("10 ballots", context.resources.getQuantityString(R.plurals.approvals_doc_ballots, 10, 10))
    }

    @Test
    fun theAccessibilityTexts() {
        assertEquals("level 2", context.getString(R.string.approvals_doc_level_spoken, 2))
        assertEquals("code point U+202E", context.getString(R.string.approvals_doc_marker_spoken, DocSpoken.markerName(0x202E)))
        assertEquals("10, text", context.getString(R.string.approvals_doc_string_spoken, "10"))
    }
}
