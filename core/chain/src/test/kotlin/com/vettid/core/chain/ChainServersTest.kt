package com.vettid.core.chain

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

/**
 * The chain server setting (ANDROID-PLAN §4 "The chain source"): one per network, the defaults, "Reset to default", and the genesis
 * check before anything is saved. The typed URLs are `https`; every client is pointed at the local recorded server.
 */
class ChainServersTest {
    private lateinit var server: MockWebServer

    /** An in-memory stand-in for the encrypted settings file. */
    private class MemoryFile : ChainSettingsFile {
        var bytes: ByteArray? = null
        var failLoad = false
        var failSave = false
        var saves = 0

        override fun load(): ByteArray? {
            check(!failLoad) { "cannot decrypt" }
            return bytes
        }

        override fun save(bytes: ByteArray) {
            check(!failSave) { "disk full" }
            saves++
            this.bytes = bytes.copyOf()
        }
    }

    private val file = MemoryFile()
    private val asked = mutableListOf<ChainServerUrl>()

    @Before
    fun start() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun stop() = server.close()

    private fun servers(defaults: ChainDefaults = ChainDefaults()) = ChainServers(file, defaults, clientFor = { url ->
        asked += url
        EsploraClient(server.url("/api"), OkHttpClient())
    })

    private fun genesis(network: Network) = MockResponse.Builder().code(200).body(Genesis.of(network)!!).build()

    private val own = "https://node.example/api"

    @Test
    fun theDefaultsPerNetwork() = runBlocking {
        val s = servers()
        assertEquals(ChainServer.Default(ChainDefaults.SIGNET), s.server(Network.SIGNET))
        assertEquals(ChainServer.Default(ChainDefaults.TESTNET4), s.server(Network.TESTNET4))
        assertEquals("https://mempool.space/signet/api", ChainDefaults.SIGNET.text)
        assertEquals("https://mempool.space/testnet4/api", ChainDefaults.TESTNET4.text)
        // Mainnet is not offered yet (its server comes with the production approval); nothing to look up.
        assertEquals(ChainServer.NotOffered, s.server(Network.MAINNET))
        assertEquals(ChainAccess.NoServer(ChainServer.NotOffered), s.access(Network.MAINNET))
        // The app does not connect to testnet3; regtest only in devStack.
        assertEquals(ChainServer.NotOffered, s.server(Network.TESTNET))
        assertEquals(ChainServer.NotOffered, s.server(Network.REGTEST))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun block0IsCheckedOncePerSessionBeforeTheFirstLookup() = runBlocking {
        val s = servers()
        server.enqueue(genesis(Network.SIGNET))
        assertTrue(s.access(Network.SIGNET) is ChainAccess.Ready)
        assertTrue(s.access(Network.SIGNET) is ChainAccess.Ready)
        assertEquals(1, server.requestCount)
        // A new session checks again.
        s.forgetChecks()
        server.enqueue(genesis(Network.SIGNET))
        assertTrue(s.access(Network.SIGNET) is ChainAccess.Ready)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun aServerThatChangedNetworkSinceItWasSavedIsNotUsed() = runBlocking {
        val s = servers()
        server.enqueue(genesis(Network.MAINNET))
        assertEquals(ChainAccess.WrongNetwork(Network.MAINNET), s.access(Network.SIGNET))
        // Not remembered as checked: asked again next time.
        server.enqueue(genesis(Network.SIGNET))
        assertTrue(s.access(Network.SIGNET) is ChainAccess.Ready)
    }

    @Test
    fun aServerThatDoesNotAnswerTheCheckIsNotUsed() = runBlocking {
        server.enqueue(MockResponse.Builder().code(503).build())
        assertTrue(servers().access(Network.SIGNET) is ChainAccess.Unchecked)
    }

    @Test
    fun aSavedServerNeedsNoSecondCheckThisSession() = runBlocking {
        val s = servers()
        server.enqueue(genesis(Network.SIGNET))
        assertTrue(s.save(Network.SIGNET, own) is SaveResult.Saved)
        assertEquals(own, ((s.access(Network.SIGNET) as ChainAccess.Ready).server as ChainServer.Own).url.text)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun nothingOfTheSettingIsKeptInMemory() = runBlocking {
        val s = servers()
        server.enqueue(genesis(Network.SIGNET))
        assertTrue(s.save(Network.SIGNET, own) is SaveResult.Saved)
        // The file goes (a wipe): the same instance reads the default, and a reset writes nothing back.
        file.bytes = null
        val saves = file.saves
        assertEquals(ChainServer.Default(ChainDefaults.SIGNET), s.server(Network.SIGNET))
        assertTrue(s.reset(Network.SIGNET))
        assertNull(file.bytes)
        assertEquals(saves, file.saves)
        // A passing read failure is not remembered either.
        file.failLoad = true
        assertEquals(ChainServer.Unreadable, s.server(Network.SIGNET))
        file.failLoad = false
        assertEquals(ChainServer.Default(ChainDefaults.SIGNET), s.server(Network.SIGNET))
    }

    @Test
    fun devStacksRegtestServerComesFromItsBuildConfig() = runBlocking {
        val s = servers(ChainDefaults.forBuild("http://127.0.0.1:18083"))
        assertEquals("http://127.0.0.1:18083", (s.server(Network.REGTEST) as ChainServer.Default).url.text)
        assertEquals(ChainServer.NotOffered, servers(ChainDefaults.forBuild("")).server(Network.REGTEST))
    }

    @Test
    fun aServerOnTheRightNetworkIsSavedAndKept() = runBlocking {
        server.enqueue(genesis(Network.SIGNET))
        val saved = servers().save(Network.SIGNET, own)
        assertEquals(own, (saved as SaveResult.Saved).url.text)
        assertEquals("/api/block-height/0", server.takeRequest().url.encodedPath)
        assertEquals(listOf(own), asked.map { it.text })
        // A new process reads it back; the other networks keep their defaults.
        val again = servers()
        assertEquals(ChainServer.Own(saved.url), again.server(Network.SIGNET))
        assertEquals(ChainServer.Default(ChainDefaults.TESTNET4), again.server(Network.TESTNET4))
    }

    @Test
    fun aTestnet3ServerIsRefusedForTestnet4AndNothingIsSaved() = runBlocking {
        server.enqueue(genesis(Network.TESTNET))
        val s = servers()
        assertEquals(SaveResult.WrongNetwork(Network.TESTNET), s.save(Network.TESTNET4, own))
        assertEquals(ChainServer.Default(ChainDefaults.TESTNET4), s.server(Network.TESTNET4))
        assertEquals(0, file.saves)
    }

    @Test
    fun aMainnetServerIsRefusedForSignet() = runBlocking {
        server.enqueue(genesis(Network.MAINNET))
        assertEquals(SaveResult.WrongNetwork(Network.MAINNET), servers().save(Network.SIGNET, own))
        assertEquals(0, file.saves)
    }

    @Test
    fun aServerOnAnUnknownChainIsRefused() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).body("ab".repeat(32)).build())
        assertEquals(SaveResult.WrongNetwork(null), servers().save(Network.TESTNET4, own))
        assertEquals(0, file.saves)
    }

    @Test
    fun aServerThatDoesNotAnswerIsNotSaved() = runBlocking {
        server.enqueue(MockResponse.Builder().code(503).build())
        assertTrue(servers().save(Network.SIGNET, own) is SaveResult.Failed)
        server.close()
        assertTrue((servers().save(Network.SIGNET, own) as SaveResult.Failed).error is ChainError.Unreachable)
        assertEquals(0, file.saves)
    }

    @Test
    fun aRefusedUrlIsNeverAsked() = runBlocking {
        val s = servers()
        assertEquals(SaveResult.BadUrl(UrlProblem.NOT_HTTPS), s.save(Network.SIGNET, "http://node.example/api"))
        assertEquals(SaveResult.BadUrl(UrlProblem.CREDENTIALS), s.save(Network.SIGNET, "https://me:pw@node.example/api"))
        assertEquals(0, server.requestCount)
        assertTrue(asked.isEmpty())
    }

    @Test
    fun noSettingForANetworkTheAppDoesNotConnectTo() = runBlocking {
        assertEquals(SaveResult.NotOffered, servers().save(Network.TESTNET, own))
        assertEquals(SaveResult.NotOffered, servers().save(Network.REGTEST, own))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun resetToDefault() = runBlocking {
        server.enqueue(genesis(Network.TESTNET4))
        val s = servers()
        assertTrue(s.save(Network.TESTNET4, own) is SaveResult.Saved)
        assertTrue(s.reset(Network.TESTNET4))
        assertEquals(ChainServer.Default(ChainDefaults.TESTNET4), s.server(Network.TESTNET4))
        assertEquals(ChainServer.Default(ChainDefaults.TESTNET4), servers().server(Network.TESTNET4))
    }

    @Test
    fun aRegtestServerOnAKnownNetworkIsRefusedInDevStack() = runBlocking {
        val s = servers(ChainDefaults.forBuild("http://127.0.0.1:18083"))
        server.enqueue(genesis(Network.SIGNET))
        assertEquals(SaveResult.WrongNetwork(Network.SIGNET), s.save(Network.REGTEST, own))
        server.enqueue(MockResponse.Builder().code(200).body(Network.REGTEST.chainHash.value.toByteArray().toDisplayHex()).build())
        assertTrue(s.save(Network.REGTEST, own) is SaveResult.Saved)
        // What the member types is https only, on regtest too.
        assertEquals(SaveResult.BadUrl(UrlProblem.NOT_HTTPS), s.save(Network.REGTEST, "http://127.0.0.1:3002"))
    }

    @Test
    fun anUnreadableSettingUsesNoServerUntilItIsSavedOrReset() = runBlocking {
        file.bytes = "garbage".encodeToByteArray()
        val s = servers()
        assertEquals(ChainServer.Unreadable, s.server(Network.SIGNET))
        // Not the default either: a member who chose their own server must not be sent to mempool.space.
        assertEquals(ChainAccess.NoServer(ChainServer.Unreadable), s.access(Network.SIGNET))
        assertEquals(0, server.requestCount)
        // Resetting one network gives it its default; the other stays without a server until it is saved or reset.
        assertTrue(s.reset(Network.SIGNET))
        assertEquals(ChainServer.Default(ChainDefaults.SIGNET), s.server(Network.SIGNET))
        assertEquals(ChainServer.NotSet, s.server(Network.TESTNET4))
        assertEquals(ChainAccess.NoServer(ChainServer.NotSet), s.access(Network.TESTNET4))
        assertEquals("""{"version":1,"servers":{"testnet4":null}}""", file.bytes!!.decodeToString())
        assertEquals(ChainServer.NotSet, servers().server(Network.TESTNET4))
        assertTrue(s.reset(Network.TESTNET4))
        assertEquals(ChainServer.Default(ChainDefaults.TESTNET4), s.server(Network.TESTNET4))
    }

    @Test
    fun aSaveOverAnUnreadableSettingLeavesTheOtherNetworksWithoutAServer() = runBlocking {
        file.bytes = "garbage".encodeToByteArray()
        server.enqueue(genesis(Network.SIGNET))
        val s = servers()
        assertTrue(s.save(Network.SIGNET, own) is SaveResult.Saved)
        assertEquals(own, (s.server(Network.SIGNET) as ChainServer.Own).url.text)
        assertEquals(ChainServer.NotSet, s.server(Network.TESTNET4))
    }

    @Test
    fun aFileFromAnotherVersionOrMalformedIsUnreadable() = runBlocking {
        for (content in listOf("""{"version":2,"servers":{}}""", "garbage", """{"version":1,"servers":{"signet":"http://x.example"}}""")) {
            file.bytes = content.encodeToByteArray()
            assertEquals(content, ChainServer.Unreadable, servers().server(Network.SIGNET))
        }
    }

    @Test
    fun aSaveThatCannotBeWrittenSaysSo() = runBlocking {
        server.enqueue(genesis(Network.SIGNET))
        file.failSave = true
        val s = servers()
        assertEquals(SaveResult.NotWritten, s.save(Network.SIGNET, own))
        assertEquals(ChainServer.Default(ChainDefaults.SIGNET), s.server(Network.SIGNET))
        // A reset that has something to remove, and cannot write.
        file.bytes = """{"version":1,"servers":{"signet":"$own"}}""".encodeToByteArray()
        assertFalse(s.reset(Network.SIGNET))
        assertEquals(own, (s.server(Network.SIGNET) as ChainServer.Own).url.text)
    }

    @Test
    fun aSaveThatAWipeOvertookWritesNothing() = runBlocking {
        var generation = 0L
        // The wipe begins while the server is being checked.
        server.enqueue(genesis(Network.SIGNET))
        val s = ChainServers(file, ChainDefaults(), { url ->
            generation++
            EsploraClient(server.url("/api"), OkHttpClient())
        }, { generation })
        assertEquals(SaveResult.NotWritten, s.save(Network.SIGNET, own))
        assertNull(file.bytes)
        assertEquals(0, file.saves)
    }

    @Test
    fun theFileHoldsOnlyTheServers() = runBlocking {
        server.enqueue(genesis(Network.SIGNET))
        servers().save(Network.SIGNET, own)
        assertEquals("""{"version":1,"servers":{"signet":"$own"}}""", file.bytes!!.decodeToString())
    }
}
