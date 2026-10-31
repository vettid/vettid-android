package com.vettid.core.chain

import com.vettid.core.chain.EsploraFixtures.ADDRESS
import com.vettid.core.chain.EsploraFixtures.page
import com.vettid.core.chain.EsploraFixtures.tx
import com.vettid.core.chain.EsploraFixtures.txid
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.util.concurrent.TimeUnit
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger

/**
 * Nothing of a wallet reaches a log (ANDROID-PLAN §4 "The chain source", "Local state"): no address, script hash, txid, amount or
 * server path in any log line, error message or stack trace, whatever the call and however it ends.
 */
class ChainPrivacyTest {
    private val secretPath = "private-node-path"

    @Test
    fun noLogLineOrErrorNamesAnAddressATxidOrTheServersPath() {
        val errors = mutableListOf<Throwable>()
        val lines = captured { errors += scenario() }
        assertEquals(5, errors.size)
        val values = listOf(
            ChainTarget.Address(ADDRESS), ChainTarget.ScriptHash(txid(5)), ChainSummary(150_000, 50_000, 1, 2),
            ChainUtxo(txid(1), 0, 50_000, true, 1), ChainTxInput(txid(2), 0, ADDRESS, 50_000), ChainTxOutput("0014", ADDRESS, 50_000),
            ChainTx(txid(3), true, 1, 1, 150_000, listOf(ChainTxInput(txid(4), 0, ADDRESS, 50_000)), emptyList()),
        )
        // What a string template would print of each value that carries wallet data: its type name only.
        val text = lines + errors.map { it.toString() + it.stackTraceToString() } + values.map { "$it" }
        val secrets = listOf(ADDRESS, secretPath, "150000", "50000", EsploraFixtures.RAW_TXID) + (1..5).map { txid(it) }
        for (t in text) {
            for (s in secrets) assertFalse("'$s' in: $t", t.contains(s))
        }
    }

    /** Every call kind and every way one ends (answers, a refused broadcast, a redirect, a timeout, too large, unreachable). */
    private fun scenario(): List<Throwable> {
        val errors = mutableListOf<Throwable>()
        MockWebServer().use { server ->
            server.start()
            val client = EsploraClient(server.url("/$secretPath/api"), OkHttpClient(), EsploraClient.Limits(callTimeoutMs = 300))
            val refused = """sendrawtransaction RPC error: {"code":-25,"message":"${txid(1)}"}"""
            val bodies = listOf(
                EsploraFixtures.SUMMARY, EsploraFixtures.summary(1), page(tx(1, true), tx(2, false)),
                EsploraFixtures.UTXO, EsploraFixtures.RAW_HEX,
            )
            for (body in bodies) {
                server.enqueue(MockResponse.Builder().code(200).body(body).build())
            }
            server.enqueue(MockResponse.Builder().code(400).body(refused).build())
            server.enqueue(MockResponse.Builder().code(302).addHeader("Location", "/$secretPath/x").build())
            server.enqueue(MockResponse.Builder().code(200).body("1").headersDelay(2, TimeUnit.SECONDS).build())
            server.enqueue(MockResponse.Builder().code(200).body("x".repeat(70_000)).build())
            val calls: List<suspend () -> Any> = listOf(
                { client.summary(ChainTarget.Address(ADDRESS)) },
                { client.transactions(ChainTarget.Address(ADDRESS)) },
                { client.unspent(ChainTarget.Address(ADDRESS)) },
                { client.rawTransaction(EsploraFixtures.RAW_TXID) },
                { client.broadcast(EsploraFixtures.RAW_TX) },
                { client.unspent(ChainTarget.Address(ADDRESS)) },
                { client.rawTransaction(txid(4)) },
                { client.summary(ChainTarget.ScriptHash(txid(5))) },
                {
                    server.close()
                    client.unspent(ChainTarget.Address(ADDRESS))
                },
            )
            for (c in calls) runCatching { runBlocking { c() } }.exceptionOrNull()?.let { errors += it }
        }
        return errors
    }

    /** What [block] writes to `java.util.logging` (OkHttp's log on the JVM) and to standard output and error. */
    private fun captured(block: () -> Unit): List<String> {
        val lines = mutableListOf<String>()
        val handler = object : Handler() {
            override fun publish(record: LogRecord) {
                // The recorded server's own request log is the test's, not the app's.
                if (record.loggerName?.startsWith("mockwebserver3") == true) return
                lines += "${record.loggerName} ${record.message} ${record.parameters?.joinToString()} ${record.thrown}"
            }

            override fun flush() = Unit

            override fun close() = Unit
        }
        val loggers = listOf(Logger.getLogger(""), Logger.getLogger("okhttp3"), Logger.getLogger(OkHttpClient::class.java.name))
        val levels = loggers.map { it.level }
        loggers.forEach {
            it.level = Level.ALL
            it.addHandler(handler)
        }
        val out = ByteArrayOutputStream()
        val (oldOut, oldErr) = System.out to System.err
        System.setOut(PrintStream(out, true))
        System.setErr(PrintStream(out, true))
        try {
            block()
        } finally {
            System.setOut(oldOut)
            System.setErr(oldErr)
            loggers.forEachIndexed { i, l ->
                l.removeHandler(handler)
                l.level = levels[i]
            }
        }
        return lines + out.toString()
    }

    @Test
    fun theModuleHasNoLoggingCall() {
        // This module, and the app's wallet wiring (`app/src/wallet`).
        val dirs = listOf(File("src/main/kotlin"), File("../../app/src/wallet/kotlin"))
        for (d in dirs) assertTrue("$d is missing", d.isDirectory)
        val sources = dirs.flatMap { d -> d.walkTopDown().filter { it.extension == "kt" }.toList() }
        assertTrue(sources.size > 5)
        val banned = listOf(
            "android.util.Log", "Log.", "println(", "printStackTrace", "Timber", "HttpLoggingInterceptor", "System.out", "System.err",
            "java.util.logging", "Logger", "stackTraceToString",
        )
        for (f in sources) {
            val code = f.readLines().filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("//") }.joinToString("\n")
            for (b in banned) assertFalse("${f.name} calls $b", code.contains(b))
        }
    }
}
