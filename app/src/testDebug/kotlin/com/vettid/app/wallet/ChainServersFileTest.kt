package com.vettid.app.wallet

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.vettid.app.BuildConfig
import com.vettid.core.chain.ChainDefaults
import com.vettid.core.chain.ChainServer
import com.vettid.core.chain.ChainServers
import com.vettid.core.chain.Network
import com.vettid.core.chain.SaveResult
import com.vettid.core.data.KeystoreFileStore
import com.vettid.core.data.wipe.AndroidWipeTargets
import com.vettid.core.data.wipe.LocalWipe
import com.vettid.core.data.wipe.WipeTargets
import com.vettid.core.wallet.WalletFlags
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * The chain server setting over the app's real encrypted file (WalletModule): written sealed in no-backup storage, read back by a
 * new process, and gone after a wipe. Robolectric has no Android Keystore, so the file's key is a software AES key here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ChainServersFileTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val key: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private lateinit var server: MockWebServer

    /** What the member types is `https`: the test's client sends that host to the local recorded server. */
    private lateinit var http: OkHttpClient

    private val own = "https://node.example/api"

    @Before
    fun start() {
        server = MockWebServer()
        server.start()
        val local = server.url("/")
        http = OkHttpClient.Builder().addInterceptor { chain ->
            val r = chain.request()
            chain.proceed(r.newBuilder().url(r.url.newBuilder().scheme("http").host(local.host).port(local.port).build()).build())
        }.build()
    }

    @After
    fun stop() = server.close()

    private fun store() = KeystoreFileStore(WalletModule.chainServersFile(context), WalletModule.CHAIN_SERVERS_PURPOSE) { key }

    private fun servers() =
        ChainServers(WalletModule.settingsFile(store()), ChainDefaults.forBuild(BuildConfig.WALLET_REGTEST_ESPLORA), http)

    private suspend fun saveOwnSignetServer(s: ChainServers = servers()) {
        server.enqueue(MockResponse.Builder().code(200).body("00000008819873e925422c1ff0f99f7cc9bbb232af63a077a480a3633bee1ef6").build())
        assertTrue(s.save(Network.SIGNET, own) is SaveResult.Saved)
    }

    @Test
    fun theSettingIsSealedInNoBackupStorageAndReadBack() = runBlocking {
        saveOwnSignetServer()
        val file = WalletModule.chainServersFile(context)
        assertEquals(context.noBackupFilesDir.canonicalFile, file.parentFile!!.canonicalFile)
        val bytes = file.readBytes()
        assertEquals(1.toByte(), bytes[0])
        assertFalse(String(bytes, Charsets.ISO_8859_1).contains("node.example"))
        // A new process (a new store over the same file) reads it back.
        assertEquals(own, (servers().server(Network.SIGNET) as ChainServer.Own).url.text)
    }

    @Test
    fun aWipeRemovesTheFileAndLeavesNothingInMemory() = runBlocking {
        // One instance throughout, as the app's singleton is.
        val s = servers()
        saveOwnSignetServer(s)
        val file = WalletModule.chainServersFile(context)
        assertTrue(file.exists())
        // The app's own wipe targets (its directories and marker), with a stand-in for the Keystore.
        val real = AndroidWipeTargets(context)
        val targets = object : WipeTargets {
            override val marker = real.marker
            override val dirs = real.dirs

            override fun keyAliases(): List<String> = emptyList()

            override fun deleteKey(alias: String) = Unit

            override fun clearSharedPreferences() = Unit

            override fun cancelNotifications() = Unit
        }
        val wipe = LocalWipe(targets, hooks = listOf({ s.forgetChecks() }))
        wipe.begin()
        wipe.resetMemory()
        assertTrue(wipe.erase())
        wipe.finish()
        assertFalse(file.exists())
        // The same instance: the default, and a reset writes nothing back (the old server is not kept anywhere).
        assertEquals(ChainServer.Default(ChainDefaults.SIGNET), s.server(Network.SIGNET))
        assertTrue(s.reset(Network.SIGNET))
        assertFalse(file.exists())
    }

    @Test
    fun theWalletFlagsAreSealedReadBackAndWiped() = runBlocking {
        val flags = { WalletFlags(WalletModule.flagsFile(KeystoreFileStore(WalletModule.walletFlagsFile(context), WalletModule.WALLET_FLAGS_PURPOSE) { key })) }
        assertTrue(flags().setBackedUp("01WALLET0000000000000000AA"))
        val file = WalletModule.walletFlagsFile(context)
        assertEquals(context.noBackupFilesDir.canonicalFile, file.parentFile!!.canonicalFile)
        assertEquals(1.toByte(), file.readBytes()[0])
        assertFalse(String(file.readBytes(), Charsets.ISO_8859_1).contains("01WALLET"))
        assertTrue(flags().backedUp("01WALLET0000000000000000AA"))
        val wipe = LocalWipe(object : WipeTargets {
            private val real = AndroidWipeTargets(context)
            override val marker = real.marker
            override val dirs = real.dirs

            override fun keyAliases(): List<String> = emptyList()

            override fun deleteKey(alias: String) = Unit

            override fun clearSharedPreferences() = Unit

            override fun cancelNotifications() = Unit
        })
        wipe.begin()
        assertTrue(wipe.erase())
        wipe.finish()
        assertFalse(file.exists())
        assertFalse(flags().backedUp("01WALLET0000000000000000AA"))
    }

    @Test
    fun onlyDevStackHasARegtestServer() {
        // This test runs in the debug build: no regtest server, so regtest is not offered.
        assertFalse(BuildConfig.WALLET_REGTEST)
        assertNull(WalletModule.chainDefaults().regtest)
    }
}
