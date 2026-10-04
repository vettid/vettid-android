package com.vettid.core.data

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vettid.core.keystore.AndroidKeys
import com.vettid.core.keystore.DeviceKeys
import com.vettid.core.keystore.KeySlot
import com.vettid.core.keystore.SeedWrapKey
import com.vettid.core.keystore.WrappedKeyStore
import com.vettid.core.testing.DevStack
import com.vettid.core.testing.ExitScenario
import com.vettid.core.testing.TestAndroidAttester
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * ANDROID-PLAN phase A2 exit test, on a phone against the local dev stack
 * (devstack/README.md: `devstack/devstack.sh up` sets up `adb reverse`):
 * enrolls a vault (PIN, credential password), locks and unlocks it, creates
 * a critical item, connects to the vaultctl peer through an invitation and
 * exchanges a message both ways. The device keys are wrapped under the
 * Keystore and the device state is in a Keystore-encrypted file, as in the
 * app; device attestation uses the dev stack's TEST attestation CA
 * ([TestAndroidAttester]), because the dev enclave pins only that CA.
 *
 * Skipped when the dev stack is not reachable. Instrumentation arguments
 * `devstackApi`, `devstackRelay`, `devstackCtl` override the defaults
 * (http://127.0.0.1:18081, :18080, :18082).
 */
@RunWith(AndroidJUnit4::class)
class A2ExitTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val args = InstrumentationRegistry.getArguments()
    private val wrapAlias = "vettid.test.a2.seed_wrap"
    private val fileAlias = "vettid.test.a2.files"
    private val stateFile = File(context.noBackupFilesDir, "a2-exit-device.bin")

    @After
    fun cleanup() {
        AndroidKeys.delete(wrapAlias)
        AndroidKeys.delete(fileAlias)
        stateFile.delete()
    }

    @Test
    fun enrollUnlockCriticalItemConnectAndMessage() = runBlocking {
        val stack = DevStack(
            apiBase = args.getString("devstackApi") ?: "http://127.0.0.1:18081",
            relayTransport = args.getString("devstackRelay") ?: "http://127.0.0.1:18080",
            ctl = args.getString("devstackCtl") ?: "http://127.0.0.1:18082",
        )
        assumeTrue("dev stack not reachable (devstack/devstack.sh up)", stack.reachable())

        // Device keys as the app keeps them: seeds wrapped under a Keystore AES key.
        val wrapped = HashMap<KeySlot, ByteArray>()
        val keys = DeviceKeys(
            SeedWrapKey.wrapper(wrapAlias),
            object : WrappedKeyStore {
                override fun get(slot: KeySlot) = wrapped[slot]

                override fun put(slot: KeySlot, blob: ByteArray) {
                    wrapped[slot] = blob
                }

                override fun remove(slot: KeySlot) {
                    wrapped.remove(slot)
                }
            },
        )
        val secrets = VaultSession.secrets(keys)
        val store = KeystoreFileStore(stateFile, "vault-device", key = { SeedWrapKey.getOrCreate(fileAlias) })
        withTimeout(TIMEOUT_MS) {
            ExitScenario(stack, TestAndroidAttester(), secrets, store) { Log.i(TAG, it) }.run(this)
        }
        // The state on disk is ciphertext that only the Keystore key opens.
        val raw = stateFile.readBytes()
        check(!String(raw, Charsets.ISO_8859_1).contains("vault_id"))
        check(String(store.load()!!).contains("\"vaultId\""))
    }

    private companion object {
        const val TAG = "A2ExitTest"
        const val TIMEOUT_MS = 300_000L
    }
}
