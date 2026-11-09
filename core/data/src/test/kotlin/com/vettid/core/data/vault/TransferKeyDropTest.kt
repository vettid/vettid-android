package com.vettid.core.data.vault

import com.vettid.core.keystore.KeyLevel
import com.vettid.core.keystore.KeystoreException
import com.vettid.core.keystore.PhoneKeyHandle
import com.vettid.core.vault.DroppedTransfer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * (VAULT-MESSAGING §6.7.1, §11.14.10 rule 5) The app's side of a dropped transfer to this phone: after it, the next scan's
 * epoch-1 secret is a NEW one, whatever failed on the way, and only a transfer's key is ever touched. The device's side
 * (every drop calls it, under the lock, after the drop's save, and again after a crash) is in `VaultDeviceTest`.
 *
 * [scan] is what `VaultManager.transferIn` now does inside the device's lock (`newTransferPhoneKey`: a NEW key every
 * time). [scanReusing] is what it did before the fix (`keepPhoneKey("")`: [PhoneSecrets.reuseOrCreate] on the record's
 * state): the drop's tests use it, to show that the drop's delete ALONE already stops the reuse, whatever fails, so
 * each of the two defences holds without the other.
 */
class TransferKeyDropTest {
    /** A Keystore HMAC key in software: a NEW random key at each create (as the Keystore makes one), and a delete that can fail. */
    private class SoftKey : PhoneKeyHandle {
        var key: ByteArray? = null
        var failDelete = false

        override fun create(): KeyLevel {
            key = ByteArray(32).also { SecureRandom().nextBytes(it) }
            return KeyLevel.TEE
        }

        override fun exists() = key != null

        override fun delete() {
            if (failDelete) throw KeystoreException("delete refused")
            key = null
        }

        override fun hmac(message: ByteArray): ByteArray =
            Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key!!, "HmacSHA256")) }.doFinal(message)
    }

    private val keys = mutableMapOf<String, SoftKey>()
    private val secrets = PhoneSecrets { keys.getOrPut(it) { SoftKey() } }
    private val record = AccountRecord(
        LocalAccount(),
        readable = true,
        persist = { if (failRecordWrite) throw java.io.IOException("disk") },
    )
    private var failRecordWrite = false

    /** The identity slot's attestation keys, in memory; the Keystore's aliases as a set. */
    private val attestKeystore = mutableSetOf<String>()
    private var failAttestDelete = false
    private val attestStore = object : AttestationKeys.Store {
        val m = mutableMapOf<Int, String?>()

        override fun read(slot: Int) = m[slot]

        override fun write(slot: Int, value: String?) {
            m[slot] = value
        }
    }
    private val ring = AttestationKeys(
        attestStore,
        object : AttestationKeys.Keys {
            override fun exists(alias: String) = alias in attestKeystore

            override fun delete(alias: String) {
                if (failAttestDelete) throw KeystoreException("delete refused")
                attestKeystore -= alias
            }
        },
        active = { 0 },
    )
    private val logs = mutableListOf<String>()

    private val drop = TransferKeyDrop(
        phoneSecrets = secrets,
        phone = { record.value.phone },
        resetPhone = { record.update(RecordStore.UNBOUND, TransferKeyDrop::resetRecord) },
        removeAttestKeys = { a ->
            ring.remove(0, a)
            ring.candidates(0).map { it.alias }.filter { it in a }
        },
        log = { logs += it },
    )

    /** `VaultManager.newTransferPhoneKey` then `phoneSecrets.secret(...)`. Returns the secret and the transfer's attested key. */
    private fun scan(): Pair<ByteArray, String> {
        val phone = secrets.create("")
        record.update(RecordStore.UNBOUND) { it.copy(phoneKeyVault = phone.keyVault, phoneEpoch = phone.epoch) }
        val alias = ring.attest(0, null) { a -> attestKeystore += a; a }
        return secrets.secret(phone) to alias
    }

    /** The code before the fix: `keepPhoneKey("", rec)`, which reuses the key while the record says ("", 1). */
    private fun scanReusing(): Pair<ByteArray, String> {
        val phone = secrets.reuseOrCreate(record.value.phone, "")
        if (phone != record.value.phone) {
            record.update(RecordStore.UNBOUND) { it.copy(phoneKeyVault = phone.keyVault, phoneEpoch = phone.epoch) }
        }
        val alias = ring.attest(0, null) { a -> attestKeystore += a; a }
        return secrets.secret(phone) to alias
    }

    /** A transfer has no retry of the same request on Android: every scan is a new key, with no drop in between too. */
    @Test
    fun everyTransferScanMakesANewKey() {
        val (first, _) = scan()
        val (again, _) = scan()
        assertFalse(first.contentEquals(again))
        assertEquals(PhoneSecretState("", 1), record.value.phone)
        // What the code did before: the same secret again (the bug).
        val (old1, _) = scanReusing()
        val (old2, _) = scanReusing()
        assertArrayEquals(old1, old2)
    }

    /** The start-up sweep: no transfer and no device id, so a ("", 1) key belongs to nothing and goes. */
    @Test
    fun theStartUpSweepDeletesAnOrphanedTransferKey() {
        val (first, _) = scanReusing()
        assertTrue(drop.dropped(DroppedTransfer(emptyList())))
        assertEquals(PhoneSecretState("", 0), record.value.phone)
        assertFalse(keys.getValue("").exists())
        assertFalse(first.contentEquals(scanReusing().first))
    }

    @Test
    fun afterADropTheNextScanSendsANewSecretAndTheAttestedKeyIsGone() {
        val (first, alias) = scanReusing()
        assertTrue(drop.dropped(DroppedTransfer(listOf(alias))))
        assertEquals("the record no longer names an epoch-1 transfer key", PhoneSecretState("", 0), record.value.phone)
        assertFalse("the Keystore key is gone", keys.getValue("").exists())
        assertFalse("the attested key is gone from the Keystore", alias in attestKeystore)
        assertTrue("and from the slot's list", ring.candidates(0).none { it.alias == alias })
        val (second, _) = scanReusing()
        assertFalse("a different epoch-1 secret", first.contentEquals(second))
        assertEquals(PhoneSecretState("", 1), record.value.phone)
        assertTrue(logs.isEmpty())
    }

    /** The process dies after the record's reset and before the Keystore delete: the reset alone stops the reuse. */
    @Test
    fun aCrashBetweenTheResetAndTheKeyDeleteIsSafe() {
        val (first, _) = scanReusing()
        assertTrue(record.update(RecordStore.UNBOUND, TransferKeyDrop::resetRecord)) // step 1 only; killed here
        assertTrue("the old key is still in the Keystore", keys.getValue("").exists())
        val (second, _) = scanReusing() // reuseOrCreate no longer matches: create replaces the alias
        assertFalse(first.contentEquals(second))
    }

    /** The settle runs again after a crash (the device keeps the drop recorded): a record already at ("", 0) still loses the key. */
    @Test
    fun theDeleteRunAgainAfterACrashFinishesTheJob() {
        scanReusing()
        record.update(RecordStore.UNBOUND, TransferKeyDrop::resetRecord)
        assertTrue(drop.dropped(DroppedTransfer(emptyList())))
        assertFalse(keys.getValue("").exists())
        assertEquals(PhoneSecretState("", 0), record.value.phone)
    }

    /** The Keystore refuses the delete: logged by alias and type only, the drop goes on, and the next scan still sends a new secret. */
    @Test
    fun aFailedKeystoreDeleteStillLeavesTheNextScanWithANewSecret() {
        val (first, alias) = scanReusing()
        keys.getValue("").failDelete = true
        failAttestDelete = true
        assertTrue("the reset held: the secret can no longer be sent again", drop.dropped(DroppedTransfer(listOf(alias))))
        assertTrue(keys.getValue("").exists())
        assertEquals(PhoneSecretState("", 0), record.value.phone)
        assertEquals(
            listOf(
                "the phone key vault-unlock- could not be deleted: KeystoreException",
                "the attested keys [$alias] could not be deleted",
            ),
            logs,
        )
        assertTrue("the attested key stays listed, to go another time", ring.candidates(0).any { it.alias == alias })
        keys.getValue("").failDelete = false
        val (second, _) = scanReusing()
        assertFalse(first.contentEquals(second))
    }

    /** The record cannot be written but the key goes: the next scan finds no key and makes a new one. */
    @Test
    fun aFailedRecordWriteWithTheKeyDeletedStillLeavesANewSecret() {
        val (first, alias) = scanReusing()
        failRecordWrite = true
        assertTrue(drop.dropped(DroppedTransfer(listOf(alias))))
        assertTrue(logs.single().startsWith("the phone key's record could not be reset: IOException"))
        failRecordWrite = false
        val (second, _) = scanReusing()
        assertFalse(first.contentEquals(second))
    }

    /** Both fail: not done, so the device keeps the drop recorded and refuses the next scan until it holds. */
    @Test
    fun bothFailingIsNotDone() {
        scanReusing()
        failRecordWrite = true
        keys.getValue("").failDelete = true
        assertFalse(drop.dropped(DroppedTransfer(emptyList())))
        failRecordWrite = false
        keys.getValue("").failDelete = false
        assertTrue("the next try holds", drop.dropped(DroppedTransfer(emptyList())))
        assertEquals(PhoneSecretState("", 0), record.value.phone)
    }

    /** A key made for a vault (an enrollment, a recovery, or a paired transfer's later epoch) is never touched. */
    @Test
    fun aKeyThatIsNotAnUnpairedTransfersIsNeverDeleted() {
        for (state in listOf(PhoneSecretState("vault-1", 1), PhoneSecretState("", 2), PhoneSecretState("vault-1", 3))) {
            keys.clear()
            secrets.create(state.keyVault)
            secrets.create("") // a transfer key left in the Keystore too: not named by this record, so not ours to judge here
            record.update(RecordStore.UNBOUND) { it.copy(phoneKeyVault = state.keyVault, phoneEpoch = state.epoch) }
            assertTrue(drop.dropped(DroppedTransfer(emptyList())))
            assertEquals(state, record.value.phone)
            assertTrue("$state's key stays", keys.getValue(state.keyVault).exists())
        }
        // The record's reset alone, too: only ("", 1) is ever written.
        assertEquals(null, TransferKeyDrop.resetRecord(LocalAccount(phoneKeyVault = "v", phoneEpoch = 1)))
        assertEquals(null, TransferKeyDrop.resetRecord(LocalAccount(phoneKeyVault = "", phoneEpoch = 2)))
        assertEquals(0L, TransferKeyDrop.resetRecord(LocalAccount(phoneKeyVault = "", phoneEpoch = 1))!!.phoneEpoch)
    }

    /**
     * [VaultManager] has no unit-test harness, so its wiring is read from the source (as `TransferInDeadlineCallTest`): the
     * session's device calls this class on every drop, the scan names its attested key, and the scan's secret is computed
     * inside `startTransfer`'s lambda, i.e. under the device's lock that the drop's delete also holds.
     */
    @Test
    fun theManagerWiresTheDropIntoTheSessionsDeviceAndTheScan() {
        val code = java.io.File("src/main/kotlin/com/vettid/core/data/vault/VaultManager.kt").readLines()
            .map { it.substringBefore("//").trim() }
        assertEquals(1, code.count { it == "val drops = transferKeyDrop(slot)" })
        assertEquals(1, code.count { it == "transferDropped = drops::dropped," })
        assertEquals(1, code.count { it == "record.update(RecordStore.UNBOUND, TransferKeyDrop::resetRecord)" })
        val start = code.indexOfFirst { it == "d.startTransfer(link, att, attestedKey = { att.made }) { traits ->" }
        assertTrue("the scan names its attested key", start >= 0)
        assertEquals("val att = attester()", code[start - 1])
        assertEquals("a new key, in the lambda", "if (r) phoneSecrets.secret(newTransferPhoneKey(rec)) else null", code[start + 2])
        val fresh = code.indexOfFirst { it == "private fun newTransferPhoneKey(since: Int): PhoneSecretState {" }
        assertEquals("never reused", "val phone = phoneSecrets.create(TransferKeyDrop.TRANSFER_KEY_VAULT)", code[fresh + 1])
        assertFalse(
            "no transfer reuses a key",
            code.any { it.contains("keepPhoneKey(\"\"") || it.contains("keepPhoneKey(TransferKeyDrop") },
        )
        val load = code.indexOfFirst { it == "val device = VaultDevice.load(cfg, secrets) ?: VaultDevice.create(cfg, secrets)" }
        val next = code.drop(load + 1).first { it.isNotEmpty() }
        assertEquals("the start-up settle, before the device is used", "device.settleTransferKeysAtStart(", next)
    }
}
