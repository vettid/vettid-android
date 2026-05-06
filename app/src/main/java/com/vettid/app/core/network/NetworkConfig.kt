package com.vettid.app.core.network

import com.vettid.app.BuildConfig
import okhttp3.CertificatePinner
import okhttp3.ConnectionPool
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import okhttp3.TlsVersion
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit

/**
 * Centralized network configuration for VettID API clients.
 *
 * Provides:
 * - Certificate pinning for custom domains (when deployed)
 * - Consistent timeouts across all clients
 * - Logging in debug builds
 *
 * Certificate Pinning Notes:
 * - AWS API Gateway rotates certificates automatically, making pinning impractical
 * - Pinning is only enabled for custom domains with stable certificates
 * - When deploying custom domain, generate pins with:
 *   openssl s_client -connect api.vettid.dev:443 | \
 *   openssl x509 -pubkey -noout | openssl pkey -pubin -outform der | \
 *   openssl dgst -sha256 -binary | base64
 */
object NetworkConfig {

    /**
     * API base URL from BuildConfig (environment-specific)
     */
    val apiBaseUrl: String = BuildConfig.API_BASE_URL

    /**
     * Whether to enable certificate pinning.
     *
     * SECURITY (manifest-F6): pinned to the Amazon RSA 2048 M04
     * intermediate (current api.vettid.dev issuer) plus the Amazon
     * Root CA 1 as a backup. We do NOT pin the leaf — ACM rotates it
     * roughly annually and there is no dev workflow today to push a
     * new pin every rotation. The intermediate has a 2030 expiry and
     * the root expires in 2038, so the pin set survives anything but
     * Amazon retiring the M04 line.
     *
     * Refresh procedure if Amazon publishes a new intermediate:
     *   echo | openssl s_client -servername api.vettid.dev \
     *       -showcerts -connect api.vettid.dev:443 2>/dev/null \
     *     | awk '/-----BEGIN CERTIFICATE-----/,/-----END CERTIFICATE-----/' \
     *     | csplit -s -z -f cert- - '/-----BEGIN CERTIFICATE-----/' '{*}'
     *   openssl x509 -in cert-01 -pubkey -noout \
     *     | openssl pkey -pubin -outform der \
     *     | openssl dgst -sha256 -binary | base64
     *   # Replace AMAZON_RSA_M04_PIN below with the new value.
     */
    private val ENABLE_CERTIFICATE_PINNING = true

    // Amazon RSA 2048 M04 (intermediate that signs api.vettid.dev today).
    private const val AMAZON_RSA_M04_PIN = "sha256/G9LNNAql897egYsabashkzUCTEJkWBzgoEtk8X/678c="

    // Amazon Root CA 1 (backup; would have to replace M04 entirely if M04 is retired).
    private const val AMAZON_ROOT_CA_1_PIN = "sha256/++MBgDH5WGvL9Bcn5Be30cRcL0f5O+NyoXuWtQdX1aI="

    private val CERTIFICATE_PINS: Map<String, List<String>> = mapOf(
        "api.vettid.dev" to listOf(AMAZON_RSA_M04_PIN, AMAZON_ROOT_CA_1_PIN),
        "vettid.dev"     to listOf(AMAZON_RSA_M04_PIN, AMAZON_ROOT_CA_1_PIN),
        "pcr-manifest.vettid.dev" to listOf(AMAZON_RSA_M04_PIN, AMAZON_ROOT_CA_1_PIN),
    )

    /**
     * Build certificate pinner if pinning is enabled and pins are configured.
     */
    private fun buildCertificatePinner(): CertificatePinner? {
        if (!ENABLE_CERTIFICATE_PINNING) return null

        val builder = CertificatePinner.Builder()
        var hasPins = false

        for ((hostname, pins) in CERTIFICATE_PINS) {
            for (pin in pins) {
                if (pin.isNotBlank()) {
                    builder.add(hostname, pin)
                    hasPins = true
                }
            }
        }

        return if (hasPins) builder.build() else null
    }

    /**
     * TLS configuration - only allow TLS 1.2 and 1.3
     * Prevents downgrade attacks to older, vulnerable TLS versions
     */
    private val tlsSpec = ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
        .tlsVersions(TlsVersion.TLS_1_3, TlsVersion.TLS_1_2)
        .build()

    /**
     * Connection pool configuration
     * Limits connection reuse to prevent resource exhaustion
     */
    private val connectionPool = ConnectionPool(
        maxIdleConnections = 5,
        keepAliveDuration = 5,
        timeUnit = TimeUnit.MINUTES
    )

    /**
     * Create a configured OkHttpClient with security settings.
     *
     * Security features:
     * - TLS 1.2/1.3 only (no older versions)
     * - Connection pool limits to prevent resource exhaustion
     * - Appropriate timeouts
     * - Certificate pinning (when enabled)
     *
     * @param enableLogging Whether to enable HTTP logging (should be false in production)
     */
    fun createHttpClient(enableLogging: Boolean = BuildConfig.DEBUG): OkHttpClient {
        return newHttpClientBuilder(enableLogging).build()
    }

    /**
     * Returns an OkHttpClient.Builder pre-configured with TLS 1.2/1.3
     * only, certificate pinning (when enabled), connection pool limits,
     * and the localhost-only-cleartext interceptor in debug.
     *
     * SECURITY (android-crypto-H2 + manifest-F5 + manifest-F6): every
     * HTTP-using component in the app should construct its OkHttpClient
     * via this factory. Direct `OkHttpClient.Builder()` calls inherit
     * OkHttp defaults that include TLS 1.0 / 1.1 in some negotiations
     * and have no cert pinning — that's the bug F-H2 was about.
     */
    fun newHttpClientBuilder(enableLogging: Boolean = BuildConfig.DEBUG): OkHttpClient.Builder {
        val builder = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .connectionSpecs(
                if (BuildConfig.DEBUG) listOf(tlsSpec, ConnectionSpec.CLEARTEXT)
                else listOf(tlsSpec)
            )
            .connectionPool(connectionPool)

        if (BuildConfig.DEBUG) {
            builder.addInterceptor(LocalhostOnlyCleartextInterceptor())
        }

        if (enableLogging) {
            val loggingInterceptor = HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BODY
            }
            builder.addInterceptor(loggingInterceptor)
        }

        buildCertificatePinner()?.let { pinner ->
            builder.certificatePinner(pinner)
        }

        return builder
    }

    /**
     * Debug-only interceptor that rejects cleartext (HTTP) requests
     * unless they target localhost or the emulator-host alias. Prevents
     * a debug build from accidentally talking unencrypted to a
     * production-shaped URL when ConnectionSpec.CLEARTEXT is allowed.
     */
    private class LocalhostOnlyCleartextInterceptor : okhttp3.Interceptor {
        override fun intercept(chain: okhttp3.Interceptor.Chain): okhttp3.Response {
            val req = chain.request()
            if (!req.isHttps) {
                val host = req.url.host
                val ok = host == "127.0.0.1" || host == "::1" ||
                    host == "localhost" || host == "10.0.2.2"
                if (!ok) {
                    throw SecurityException("Cleartext request to non-localhost host blocked: $host")
                }
            }
            return chain.proceed(req)
        }
    }

    /**
     * Verify the API URL is using HTTPS.
     * @throws SecurityException if cleartext HTTP is attempted
     */
    fun requireHttps(url: String) {
        if (!url.startsWith("https://")) {
            throw SecurityException("Cleartext HTTP not allowed. Use HTTPS: $url")
        }
    }
}
