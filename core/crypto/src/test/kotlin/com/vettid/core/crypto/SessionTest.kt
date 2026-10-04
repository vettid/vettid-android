package com.vettid.core.crypto

import com.vettid.core.crypto.envelope.Envelope
import com.vettid.core.crypto.envelope.Inner
import com.vettid.core.crypto.envelope.Mode
import com.vettid.core.crypto.hpke.KemPrivateKey
import com.vettid.core.crypto.session.Epoch
import com.vettid.core.crypto.session.Initiator
import com.vettid.core.crypto.session.InitiatorConfig
import com.vettid.core.crypto.session.InitiatorResult
import com.vettid.core.crypto.session.KeyLookup
import com.vettid.core.crypto.session.Keyring
import com.vettid.core.crypto.session.Mailbox
import com.vettid.core.crypto.session.PendingInit
import com.vettid.core.crypto.session.Policy
import com.vettid.core.crypto.session.Purpose
import com.vettid.core.crypto.session.RelayAddr
import com.vettid.core.crypto.session.Relay
import com.vettid.core.crypto.session.ResponderConfig
import com.vettid.core.crypto.session.Rotation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

/** Handshake, epochs and rekeys beyond the vectors: the failure rules of §6.3 and §6.5. */
class SessionTest {
    private val now = Instant.parse("2026-10-01T12:00:00Z")

    private class Party(val ik: Ed25519PrivateKey = Ed25519PrivateKey.generate(), val kem: KemPrivateKey = KemPrivateKey.generate()) {
        val relayKey = Ed25519PrivateKey.generate()
        val relay = RelayAddr("https://relay.vettid.org", Mailbox.id(relayKey.publicKey), relayKey.publicKey)
        val lookup = KeyLookup { if (it == kem.publicKey.kid) kem else null }
    }

    private val app = Party()
    private val vault = Party()
    private val token = "v4.public.dGVzdA"

    private fun appInit(policy: Policy = Policy.VAULT_TO_DEVICE) = Initiator.create(
        InitiatorConfig(
            purpose = Purpose.APP,
            ctx = "vault-0001",
            identity = app.ik,
            staticKem = app.kem.publicKey,
            relay = app.relay,
            token = token,
            responderIk = vault.ik.publicKey,
            responderEk = vault.kem.publicKey,
            responderRelayKey = vault.relayKey.publicKey,
            policy = policy,
            now = now,
        ),
    )

    private fun respCfg(identity: Ed25519PrivateKey = vault.ik) =
        ResponderConfig(
            identity = identity,
            token = token,
            policy = Policy.VAULT_TO_DEVICE,
            collectSender = app.relayKey.publicKey,
            now = now,
        )

    /** Runs a full app pairing handshake and returns (app epoch, vault epoch). */
    private fun pair(): Pair<Epoch, Epoch> {
        val i = appInit()
        val p = PendingInit.open(i.envelope(), vault.lookup, now)
        assertEquals(i.sas, p.sas)
        val (r, resp) = p.respond(respCfg())
        val res = i.handleResp(resp, vault.relayKey.publicKey, now)
        val (ve, _) = r.handleFin(res.fin, app.relayKey.publicKey, now)
        return res.epoch to ve
    }

    @Test
    fun pairingRoundTripAndTraffic() {
        val (a, v) = pair()
        val m = Inner(id = "01JB2Z6V9K3M4N5P6Q7R8S9T0V", type = "vault.status", ts = now)
        assertEquals("vault.status", v.open(Envelope.parse(a.seal(m))).type)
        assertEquals(1L, a.open(Envelope.parse(v.seal(m))).seq)
        // A message cannot be opened by the direction that sent it.
        assertThrows(CryptoException.Protocol::class.java) { a.open(Envelope.parse(a.seal(m))) }
    }

    @Test
    fun badSigRAbortsAfterDecryption() {
        val i = appInit()
        val p = PendingInit.open(i.envelope(), vault.lookup, now)
        // A responder signing with another ik: sig_R does not verify under the pinned key.
        val (_, resp) = p.respond(respCfg(identity = Ed25519PrivateKey.generate()))
        assertThrows(CryptoException.Signature::class.java) { i.handleResp(resp, vault.relayKey.publicKey, now) }
        // The handshake is destroyed: even a genuine answer is refused now.
        assertThrows(CryptoException.Used::class.java) { i.handleResp(resp, vault.relayKey.publicKey, now) }
    }

    @Test
    fun junkDoesNotCancelThePendingHandshake() {
        val i = appInit()
        val p = PendingInit.open(i.envelope(), vault.lookup, now)
        val (_, resp) = p.respond(respCfg())
        // Wrong collect sender, garbage, a message to another key: dropped, the handshake stays pending.
        assertThrows(CryptoException.Protocol::class.java) { i.handleResp(resp, app.relayKey.publicKey, now) }
        assertThrows(CryptoException.Format::class.java) { i.handleResp(ByteArray(100), vault.relayKey.publicKey, now) }
        val stray = Inner.encode(Inner(id = "01JB2Z6V9K3M4N5P6Q7R8S9T0V", type = "hs.resp", ts = now), Mode.SEALED)
        val other = Envelope.sealSealed(KemPrivateKey.generate().publicKey, Kid.ANONYMOUS, stray).first
        assertThrows(CryptoException.Protocol::class.java) { i.handleResp(other, vault.relayKey.publicKey, now) }
        i.handleResp(resp, vault.relayKey.publicKey, now)
    }

    @Test
    fun responderChecks() {
        val i = appInit()
        // hs.init's collect sender must be from.relay.pk.
        val p = PendingInit.open(i.envelope(), vault.lookup, now)
        assertThrows(CryptoException.Protocol::class.java) {
            p.respond(respCfg().let { ResponderConfig(it.identity, token, policy = it.policy, collectSender = vault.relayKey.publicKey) })
        }
        // No key for the kid.
        assertThrows(CryptoException.Protocol::class.java) { PendingInit.open(i.envelope(), KeyLookup { null }, now) }
        // Too old for a durable type.
        assertThrows(CryptoException.Time::class.java) { PendingInit.open(i.envelope(), vault.lookup, now.plus(Duration.ofDays(17))) }
        // A forged hs.fin is dropped and the pending responder survives.
        val (r, resp) = PendingInit.open(i.envelope(), vault.lookup, now).respond(respCfg())
        val res = i.handleResp(resp, vault.relayKey.publicKey, now)
        val forged = res.fin.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        assertThrows(CryptoException.Decrypt::class.java) { r.handleFin(forged, app.relayKey.publicKey, now) }
        assertThrows(CryptoException.Protocol::class.java) { r.handleFin(res.fin, vault.relayKey.publicKey, now) }
        r.handleFin(res.fin, app.relayKey.publicKey, now)
    }

    @Test
    fun rekeyInSessionMode() {
        val (a, v) = pair()
        val keyringA = Keyring().also { it.activate(a, now) }
        val keyringV = Keyring().also { it.activate(v, now) }
        val i = Initiator.create(
            InitiatorConfig(
                purpose = Purpose.REKEY, ctx = "", identity = app.ik, staticKem = app.kem.publicKey, relay = app.relay,
                responderIk = vault.ik.publicKey, responderEk = null, responderRelayKey = vault.relayKey.publicKey,
                current = a, policy = Policy.VAULT_TO_DEVICE, now = now,
            ),
        )
        assertEquals(Mode.SESSION, Envelope.parse(i.envelope()).mode)
        val p = PendingInit.openRekey(i.envelope(), v, now)
        assertEquals(a.ctx, p.body.ctx)
        val (r, resp) = p.respond(
            ResponderConfig(
                identity = vault.ik, policy = Policy.VAULT_TO_DEVICE, collectSender = app.relayKey.publicKey,
                recordRelayKey = app.relayKey.publicKey, knownInitiatorIk = app.ik.publicKey, now = now,
            ),
        )
        val res: InitiatorResult = i.handleResp(resp, vault.relayKey.publicKey, now)
        val (v2, _) = r.handleFin(res.fin, app.relayKey.publicKey, now)
        assertNotEquals(Bytes.hex(a.id), Bytes.hex(res.epoch.id))

        // A message sealed in the old epoch before activation still opens after it (receive keys kept).
        val m = Inner(id = "01JB2Z6V9K3M4N5P6Q7R8S9T0V", type = "x.y", ts = now)
        val late = v.seal(m)
        keyringA.activate(res.epoch, now)
        keyringV.activate(v2, now)
        assertFalse(a.canSend())
        assertThrows(CryptoException.Protocol::class.java) { a.seal(m) }
        assertEquals("x.y", keyringA.open(Envelope.parse(late), now).first.type)
        assertEquals("x.y", keyringV.open(Envelope.parse(res.epoch.seal(m)), now).first.type)
        // After 16 days the old receive key is gone.
        keyringA.prune(now.plus(Duration.ofDays(17)))
        assertThrows(CryptoException.Protocol::class.java) { keyringA.open(Envelope.parse(late), now.plus(Duration.ofDays(17))) }
        // A rekey hs.init is accepted only under the current epoch.
        assertThrows(CryptoException.Protocol::class.java) { PendingInit.openRekey(i.envelope(), v2, now) }
    }

    @Test
    fun reconnectFollowsTheRotationChain() {
        val (a, _) = pair()
        // The vault rotated its ik and kem since the last epoch.
        val newIk = Ed25519PrivateKey.generate()
        val newKem = KemPrivateKey.generate()
        val rot = Rotation.create(vault.ik, newIk, newKem.publicKey)
        val i = Initiator.create(
            InitiatorConfig(
                purpose = Purpose.RECONNECT, ctx = a.ctx, identity = app.ik, staticKem = app.kem.publicKey, relay = app.relay,
                token = token, reconnectToken = token, responderIk = vault.ik.publicKey, responderEk = newKem.publicKey,
                responderRelayKey = vault.relayKey.publicKey, policy = Policy.VAULT_TO_VAULT, now = now,
            ),
        )
        val p = PendingInit.open(i.envelope(), KeyLookup { if (it == newKem.publicKey.kid) newKem else null }, now)
        val (r, resp) = p.respond(
            ResponderConfig(
                identity = newIk, token = token, reconnectToken = token, rotations = listOf(rot), policy = Policy.VAULT_TO_VAULT,
                collectSender = app.relayKey.publicKey, recordRelayKey = app.relayKey.publicKey,
                knownInitiatorIk = app.ik.publicKey, expectedCtx = a.ctx, now = now,
            ),
        )
        val res = i.handleResp(resp, vault.relayKey.publicKey, now)
        assertTrue(Ed25519.equalPublic(newIk.publicKey, res.responderIk))
        assertEquals(newKem.publicKey, res.responderKem)
        r.handleFin(res.fin, app.relayKey.publicKey, now)
        // A broken chain is refused.
        val forged = Rotation.create(Ed25519PrivateKey.generate(), newIk, newKem.publicKey)
        assertThrows(CryptoException.Signature::class.java) { Rotation.resolveChain(vault.ik.publicKey, listOf(forged)) }
        assertThrows(CryptoException.Signature::class.java) { Rotation.create(newIk, newIk, newKem.publicKey) }
    }

    @Test
    fun epochPolicyExportAndImport() {
        val (a, v) = pair()
        assertFalse(a.needsRekey(now.plus(Duration.ofDays(6))))
        assertTrue(a.needsRekey(now.plus(Duration.ofDays(7))))
        val m = Inner(id = "01JB2Z6V9K3M4N5P6Q7R8S9T0V", type = "x.y", ts = now)
        a.seal(m)
        val restored = Epoch.import(a.export())
        assertEquals(3L, v.open(Envelope.parse(restored.seal(m))).seq) // hs.fin, m, then this: the counter survives
        assertEquals("Epoch[redacted]", restored.toString())
        val small = Policy(Duration.ofDays(1), maxMessages = 2)
        val (b, _) = run {
            val i = appInit(small)
            val (r, resp) = PendingInit.open(i.envelope(), vault.lookup, now).respond(respCfg())
            val res = i.handleResp(resp, vault.relayKey.publicKey, now)
            r.handleFin(res.fin, app.relayKey.publicKey, now)
            res.epoch to Unit
        }
        // hs.fin was the first message sent in the epoch; the second reaches the bound.
        assertFalse(b.needsRekey(now))
        b.seal(m)
        assertTrue(b.needsRekey(now))
        // Simultaneous rekeys: the lower th1 wins.
        assertTrue(Epoch.ourRekeyWins(byteArrayOf(0, 1), byteArrayOf(0, 2)))
        assertFalse(Epoch.ourRekeyWins(byteArrayOf(1), byteArrayOf(0)))
    }

    @Test
    fun relayShapes() {
        Relay.validateUrl("https://relay.vettid.org")
        Relay.validateUrl("http://127.0.0.1:8080")
        Relay.validateUrl("http://localhost:8080/base")
        val bad = listOf(
            "http://relay.vettid.org", "https://u@relay.vettid.org", "https://relay.vettid.org?x", "https://relay.vettid.org#f",
            "ftp://relay.vettid.org", "https://", "relay.vettid.org", "https://relay .org",
        )
        for (u in bad) {
            assertThrows(u, CryptoException.Format::class.java) { Relay.validateUrl(u) }
        }
        assertTrue(Relay.isValidToken("v4.public.abc-_.x"))
        assertFalse(Relay.isValidToken("v4.public."))
        assertFalse(Relay.isValidToken("v4.local.abc"))
        assertFalse(Relay.isValidToken("v4.public.a+b"))
    }
}
