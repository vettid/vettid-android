package com.vettid.core.data

import android.content.Context
import com.vettid.core.altchan.AltChannelFlow
import com.vettid.core.altchan.AltTrust
import com.vettid.core.altchan.MemberApiClient
import com.vettid.core.altchan.MemberAuth
import com.vettid.core.keystore.DeviceKeys
import com.vettid.core.keystore.KeySlot
import com.vettid.core.keystore.SeedWrapKey
import com.vettid.core.keystore.SharedPreferencesWrappedKeyStore
import com.vettid.core.vault.DeviceConfig
import com.vettid.core.vault.DeviceSecrets
import com.vettid.core.vault.VaultApi
import com.vettid.core.vault.VaultDevice
import okhttp3.OkHttpClient
import java.io.File

/** Where the app talks to: production, or the local dev stack (debug `devStack` builds only). */
data class Endpoints(val apiBase: String, val manifestUrl: String, val relayUrl: String) {
    companion object {
        val PRODUCTION = Endpoints(MemberApiClient.PRODUCTION_API, MemberApiClient.PRODUCTION_MANIFEST, "https://relay.vettid.org")
    }
}

/**
 * The app's vault session: the device keys from the Keystore, the device
 * state in an encrypted file, the vault client and its typed API, and the
 * alternate channel through the member API. A3's screens and ViewModels use
 * it through their repositories; nothing in a feature touches transport or
 * crypto directly.
 */
class VaultSession(
    val device: VaultDevice,
    val api: VaultApi,
    val member: MemberApiClient,
    val altChannel: AltChannelFlow,
) {
    companion object {
        /** The device's keys: generated on first use, wrapped under the Keystore (§3.2). */
        fun secrets(keys: DeviceKeys): DeviceSecrets {
            for (slot in KeySlot.entries) if (!keys.has(slot)) keys.generate(slot)
            return DeviceSecrets(keys.ed25519(KeySlot.IDENTITY), keys.kem(), keys.ed25519(KeySlot.RELAY))
        }

        /** Opens (or creates and registers) the device and wires the session. */
        suspend fun open(
            context: Context,
            endpoints: Endpoints,
            http: OkHttpClient,
            trust: AltTrust,
            auth: MemberAuth,
            deviceName: String,
        ): VaultSession {
            val keys = DeviceKeys(SeedWrapKey.wrapper(), SharedPreferencesWrappedKeyStore(context))
            val store = KeystoreFileStore(File(context.noBackupFilesDir, "vault-device.bin"), "vault-device")
            val cfg = DeviceConfig(name = deviceName, relayUrl = endpoints.relayUrl, http = http, store = store, trust = trust)
            val s = secrets(keys)
            val device = VaultDevice.load(cfg, s) ?: VaultDevice.create(cfg, s)
            val member = MemberApiClient(endpoints.apiBase, endpoints.manifestUrl, http, auth)
            return VaultSession(device, VaultApi(device), member, AltChannelFlow(member, trust))
        }
    }
}
