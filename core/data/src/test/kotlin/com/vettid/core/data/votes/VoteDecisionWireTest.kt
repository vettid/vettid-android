// Fixtures stay on one line each.
@file:Suppress("MaxLineLength")

package com.vettid.core.data.votes

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `vote.get`'s `decision` and `outcome` (VAULT-MESSAGING 0.90.18 §10.19.8; BALLOT-PROTOCOL 0.14.0 §3.5, §7.1), the same cases as
 * the iPhone app. The vault refused any other decision on arrival and recomputed the outcome; one the app can't read gives no
 * line, never a wrong one.
 */
class VoteDecisionWireTest {
    private fun obj(json: String) = Json.parseToJsonElement(json) as JsonObject

    private fun decision(json: String) = VoteWire.decision(obj(json))

    private fun outcome(json: String) = VoteWire.outcome(obj(json))

    @Test
    fun eachRuleWithItsBase() {
        assertEquals(VoteDecision(VoteDecision.Rule.Majority, VoteDecision.Base.CAST), decision("""{"rule":"majority","base":"cast","threshold":null}"""))
        assertEquals(VoteDecision(VoteDecision.Rule.Majority, VoteDecision.Base.VOTED), decision("""{"rule":"majority","base":"voted","threshold":null}"""))
        assertEquals(VoteDecision(VoteDecision.Rule.Threshold(2, 3), VoteDecision.Base.ELIG), decision("""{"rule":"threshold","base":"elig","threshold":{"num":2,"den":3}}"""))
        assertEquals(VoteDecision(VoteDecision.Rule.Threshold(1, 1), VoteDecision.Base.CAST), decision("""{"rule":"threshold","base":"cast","threshold":{"num":1,"den":1}}"""))
        assertEquals(VoteDecision(VoteDecision.Rule.Plurality, null), decision("""{"rule":"plurality","base":null,"threshold":null}"""))
    }

    @Test
    fun aDecisionOutsideTheRulesGivesNone() {
        for (
        json in listOf(
            """{"rule":"majority","base":null,"threshold":null}""",
            """{"rule":"majority","base":"all","threshold":null}""",
            """{"rule":"majority","base":1,"threshold":null}""",
            """{"rule":"threshold","base":"cast","threshold":{"num":1,"den":2}}""", // not above one half
            """{"rule":"threshold","base":"cast","threshold":{"num":10,"den":11}}""", // den above 10
            """{"rule":"threshold","base":"cast","threshold":{"num":3,"den":2}}""",
            """{"rule":"threshold","base":"cast","threshold":{"num":0,"den":1}}""",
            """{"rule":"threshold","base":"cast","threshold":{"num":"2","den":3}}""",
            """{"rule":"threshold","base":"cast","threshold":{"num":2.5,"den":3}}""",
            """{"rule":"threshold","base":"cast","threshold":null}""",
            """{"rule":"runoff","base":"cast","threshold":null}""",
            """{"rule":"threshold","base":"cast","threshold":{"num":4,"den":6}}""", // not in lowest terms
            """{"rule":"threshold","base":"cast","threshold":{"num":2,"den":2}}""", // 1/1 is the only "all"
            """{"rule":"plurality","base":"cast","threshold":null}""", // plurality has no base
            """{"rule":"plurality","base":null,"threshold":{"num":2,"den":3}}""",
            """{"rule":"majority","base":"cast","threshold":{"num":2,"den":3}}""",
            """{"rule":"majority","base":"cast"}""", // threshold absent
            """{"rule":"plurality","threshold":null}""", // base absent
            """{"base":"cast","threshold":null,"x":1}""", // rule absent
            """{"rule":"majority","base":"cast","threshold":null,"extra":1}""", // an unknown member
            """{"rule":"threshold","base":"cast","threshold":{"num":2,"den":3,"x":1}}""",
            """{"rule":"threshold","base":"cast","threshold":[2,3]}""",
            """{"rule":"majority","base":"cast","threshold":{"num":1,"den":2}}""", // a threshold that doesn't read is not "none"
            """{"rule":"majority","base":"cast","threshold":"2/3"}""",
            """{"rule":"plurality","base":null,"threshold":{"num":4,"den":6}}""",
            """{"rule":"plurality","base":7,"threshold":null}""", // a base that doesn't read is not "none"
            """{"rule":"plurality","base":"most","threshold":null}""",
        )
        ) {
            assertNull(json, decision(json))
        }
    }

    @Test
    fun eachOutcome() {
        assertEquals(VoteOutcome(VoteOutcome.Status.DECIDED, "yes"), outcome("""{"quorum_met":true,"status":"decided","choice":"yes"}"""))
        assertEquals(VoteOutcome(VoteOutcome.Status.NOT_DECIDED, null), outcome("""{"quorum_met":true,"status":"not_decided","choice":null}"""))
        assertEquals(VoteOutcome(VoteOutcome.Status.TIED, null), outcome("""{"quorum_met":true,"status":"tied","choice":null}"""))
        assertEquals(VoteOutcome(VoteOutcome.Status.NO_QUORUM, null), outcome("""{"quorum_met":false,"status":"no_quorum","choice":null}"""))
        for (
        json in listOf(
            """{"quorum_met":true,"status":"decided","choice":null}""", // decided names its choice
            """{"quorum_met":true,"status":"decided","choice":7}""",
            """{"quorum_met":true,"status":"passed","choice":"yes"}""",
            """{"quorum_met":false,"status":"decided","choice":"yes"}""", // decided meets the quorum
            """{"quorum_met":true,"status":"no_quorum","choice":null}""",
            """{"quorum_met":"true","status":"tied","choice":null}""",
            """{"quorum_met":true,"status":"not_decided","choice":"yes"}""", // only decided names a choice
            """{"quorum_met":true,"status":"tied"}""", // choice absent
            """{"status":"tied","choice":null}""", // quorum_met absent
        )
        ) {
            assertNull(json, outcome(json))
        }
    }

    @Test
    fun voteGetCarriesThemIntoTheDetail() {
        val d = VoteWire.detail(
            obj(
                """{"proposal_id":"P","connection_id":"C","title":"T","state":"closed","text":"x","choices":[{"id":"yes","label":"Yes"}],""" +
                    """"decision":{"rule":"majority","base":"cast","threshold":null},"outcome":{"quorum_met":true,"status":"decided","choice":"yes"}}""",
            ),
        )!!
        assertEquals(VoteDecision(VoteDecision.Rule.Majority, VoteDecision.Base.CAST), d.decision)
        assertEquals(VoteOutcome(VoteOutcome.Status.DECIDED, "yes"), d.outcome)
        val none = VoteWire.detail(obj("""{"proposal_id":"P","connection_id":"C","title":"T","state":"open","text":"x","choices":[]}"""))!!
        assertNull(none.decision)
        assertNull(none.outcome)
    }
}
