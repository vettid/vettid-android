// Test data: long literal rows are clearer than wrapped ones.
@file:Suppress("MaxLineLength")

package com.vettid.core.data.vault

import com.vettid.core.crypto.envelope.Inner
import com.vettid.core.crypto.envelope.Ulid
import com.vettid.core.vault.VaultJson
import com.vettid.core.vault.VaultMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** Google push against the vault (ANDROID-PLAN 0.1.30, Notification modes 4; VAULT-MESSAGING 0.24.1 §14): the wire forms and a wake's collect. */
class PushTest {
    private fun msg(type: String, body: String = "{}") =
        VaultMessage(Inner(id = Ulid.new(), type = type, ts = Instant.parse("2026-10-11T08:00:00Z"), body = body.toByteArray()))

    // --- the push state (§14.1) and `push` in device.list (§10.3) ---

    @Test
    fun thePushStateAsTheVaultWritesIt() {
        val full = VaultJson.parseObject(
            """{"platform":"fcm","app_id":"com.vettid.app","state":"active","registered_at":"2026-10-11T08:00:00.000Z","last_wake_at":"2026-10-11T08:01:00.000Z","last_result":"gateway_error"}""".toByteArray(),
        )
        assertEquals(PushStateView("fcm", "com.vettid.app", "active", "gateway_error"), PushWire.state(full))
        assertTrue(PushWire.state(full)!!.active)
        // Before the first wake there is no last_result.
        val fresh = VaultJson.parseObject("""{"platform":"fcm","app_id":"com.vettid.app","state":"active","registered_at":"2026-10-11T08:00:00.000Z"}""".toByteArray())
        assertNull(PushWire.state(fresh)!!.lastResult)
        // Not a push state.
        assertNull(PushWire.state(VaultJson.parseObject("""{"platform":"fcm"}""".toByteArray())))
        assertNull(PushWire.state(null))
    }

    @Test
    fun pushIsReadFromThisAppsDeviceListEntryOnly() {
        val list = VaultJson.parseObject(
            """{"devices":[{"id":"d-desktop","kind":"desktop","state":"active","name":"Laptop","ik":"x"},{"id":"d-app","kind":"app","state":"active","name":"Pixel","ik":"y","push":{"platform":"fcm","app_id":"com.vettid.app","state":"active","registered_at":"2026-10-11T08:00:00.000Z","last_result":"sent"}}]}""".toByteArray(),
        )
        assertEquals("sent", PushWire.own(list, "d-app")?.lastResult)
        assertEquals("the one entry that has it, when the id is not known", "sent", PushWire.own(list, null)?.lastResult)
        assertNull("another device's entry has none", PushWire.own(list, "d-desktop"))
        // No registration: device.list shows no push.
        val none = VaultJson.parseObject("""{"devices":[{"id":"d-app","kind":"app","state":"active","name":"Pixel","ik":"y"}]}""".toByteArray())
        assertNull(PushWire.own(none, "d-app"))
        assertNull(PushWire.own(VaultJson.parseObject("{}".toByteArray()), "d-app"))
    }

    // --- push.register's errors (§14.1) ---

    @Test
    fun theAnswersOfPushRegister() {
        assertEquals(PushRegisterOutcome.Unavailable, PushWire.outcome(VaultFailure(FailureKind.OTHER, "unavailable")))
        assertEquals("a vault release from before §14", PushRegisterOutcome.Unavailable, PushWire.outcome(VaultFailure(FailureKind.NOT_SUPPORTED, "unsupported_type")))
        assertEquals(PushRegisterOutcome.GatewayError(120), PushWire.outcome(VaultFailure(FailureKind.OTHER, "gateway_error", retryAfterSeconds = 120)))
        assertEquals(PushRegisterOutcome.GatewayError(null), PushWire.outcome(VaultFailure(FailureKind.OTHER, "gateway_error")))
        assertEquals(PushRegisterOutcome.Limit, PushWire.outcome(VaultFailure(FailureKind.LIMIT, "limit", limit = VaultLimit("push_registrations", 20))))
        assertEquals(PushRegisterOutcome.Refused("forbidden"), PushWire.outcome(VaultFailure(FailureKind.OTHER, "forbidden")))
        assertEquals(PushRegisterOutcome.Refused("bad_request"), PushWire.outcome(VaultFailure(FailureKind.OTHER, "bad_request")))
        assertEquals(PushRegisterOutcome.Refused("credential_required"), PushWire.outcome(VaultFailure(FailureKind.OTHER, "credential_required")))
        assertEquals(PushRegisterOutcome.NoAnswer, PushWire.outcome(VaultFailure(FailureKind.NETWORK)))
        assertEquals(PushRegisterOutcome.NoAnswer, PushWire.outcome(VaultFailure(FailureKind.NO_RESPONSE)))
    }

    @Test
    fun theEventIsSpelledWithAHyphenAndCarriesAReason() {
        // VAULT-MESSAGING 0.24.1 (errata): `push.token-needed`, not 0.24.0's `push.token_needed`.
        assertEquals("push.token-needed", PushWire.TOKEN_NEEDED)
        assertEquals("blob_invalid", PushWire.reason(msg(PushWire.TOKEN_NEEDED, """{"reason":"blob_invalid"}""")))
        assertEquals("token_invalid", PushWire.reason(msg(PushWire.TOKEN_NEEDED, """{"reason":"token_invalid"}""")))
        assertEquals("token_gone", PushWire.reason(msg(PushWire.TOKEN_NEEDED)))
    }

    // --- a process a wake started: feed events before the phase is known ---

    @Test
    fun feedEventsWaitUntilTheVaultIsKnownToBeOpen() {
        val held = HeldBackEvents()
        val a = msg("feed.event", """{"item_id":"a"}""")
        val b = msg("feed.event", """{"item_id":"b"}""")
        assertTrue(held.hold(a, AppPhase.Starting))
        assertTrue("a status read that failed: still not known", held.hold(b, AppPhase.Unreachable(FailureKind.NETWORK)))
        assertFalse("only feed items wait", held.hold(msg("vault.held"), AppPhase.Starting))
        assertFalse("known to be open: handled at once", held.hold(a, AppPhase.Unlocked))
        assertFalse(held.hold(a, AppPhase.Locked))
        assertTrue(held.waiting)
        assertTrue("nothing is released while it is still not known", held.release(AppPhase.Unreachable(FailureKind.NETWORK)).isEmpty())
        assertTrue(held.waiting)
        assertEquals(listOf(a, b), held.release(AppPhase.Unlocked))
        assertFalse(held.waiting)
        assertTrue("released once", held.release(AppPhase.Unlocked).isEmpty())
    }

    @Test
    fun nothingIsKeptOfAVaultFoundLockedOrGone() {
        for (phase in listOf(AppPhase.Locked, AppPhase.SignedOut)) {
            val held = HeldBackEvents()
            held.hold(msg("feed.event"), AppPhase.Starting)
            assertTrue(held.release(phase).isEmpty())
            assertFalse(held.waiting)
            assertTrue("and not when it opens later", held.release(AppPhase.Unlocked).isEmpty())
        }
    }

    @Test
    fun atMostSixtyFourEventsWaitAndTheOldestGo() {
        val held = HeldBackEvents()
        val all = (0..HeldBackEvents.MAX).map { msg("feed.event", """{"item_id":"i$it"}""") }
        all.forEach { held.hold(it, AppPhase.Starting) }
        assertEquals(all.drop(1), held.release(AppPhase.Unlocked))
    }

    // --- when a wake's collect is done ---

    @Test
    fun aWakeIsDoneWhenACollectRequestHasBeenOutLongEnoughToHaveFoundNothing() {
        val quiet = 2_500L
        val s = WakeSettle(quietNanos = quiet)
        assertFalse("no request is out (connecting, between rounds, a backoff)", s.settled(now = 10_000, began = 0, requestSince = null, busy = false))
        assertFalse("a request that just went out may still bring messages", s.settled(now = 10_000, began = 0, requestSince = 9_000, busy = false))
        assertTrue(s.settled(now = 10_000, began = 0, requestSince = 7_500, busy = false))
        // A long poll that was out before the wake says nothing yet: the wake's own time counts (a frozen process's
        // request may be about to return what the wake is for).
        assertFalse(s.settled(now = 10_000, began = 9_000, requestSince = 1_000, busy = false))
        assertTrue(s.settled(now = 11_500, began = 9_000, requestSince = 1_000, busy = false))
        assertFalse("the feed's read or the held-back events are still out", s.settled(now = 20_000, began = 0, requestSince = 1_000, busy = true))
    }
}
