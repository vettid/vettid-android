package com.vettid.app.env

import android.content.Context
import com.vettid.core.altchan.AltTrust
import com.vettid.core.altchan.Attester
import com.vettid.core.altchan.KeystoreAttester
import com.vettid.core.data.Endpoints
import com.vettid.core.data.KeystoreFileStore
import com.vettid.core.data.account.AccountGateway
import com.vettid.core.data.account.SessionAccountGateway
import com.vettid.core.data.env.AppEnvironment
import okhttp3.OkHttpClient
import java.io.File

/**
 * account.vettid.org, relay.vettid.org, the pinned production anchors, the
 * Keystore attestation key, and the member session's cookies encrypted under
 * a Keystore key. Release and debug builds use it; the debug-only `devStack`
 * build type points the app at the local dev stack instead.
 */
object ProductionEnvironment : AppEnvironment {
    override val name = "production"
    override val endpoints = Endpoints.PRODUCTION

    override fun http(base: OkHttpClient): OkHttpClient = base

    override suspend fun trust(http: OkHttpClient): AltTrust = AltTrust.production()

    override fun attester(): Attester = KeystoreAttester()

    override fun accountGateway(context: Context, http: OkHttpClient): AccountGateway {
        val cookies = KeystoreFileStore(File(context.noBackupFilesDir, "member-session.bin"), "member-session").asCookiePersistence()
        return SessionAccountGateway(endpoints.apiBase, endpoints.manifestUrl, http, cookies)
    }
}
