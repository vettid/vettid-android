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
     * Only enable when using custom domain with stable certificates.
     */
    private val ENABLE_CERTIFICATE_PINNING = false

    /**
     * Certificate pins for VettID API custom domain.
     * These must be updated when certificates rotate.
     *
     * To generate pins:
     * 1. Primary pin (leaf certificate):
     *    openssl s_client -connect api.vettid.dev:443 | \
     *    openssl x509 -pubkey -noout | openssl pkey -pubin -outform der | \
     *    openssl dgst -sha256 -binary | base64
     *
     * 2. Backup pin (intermediate CA):
     *    Include at least one backup pin from the CA chain for rotation resilience
     */
    private val CERTIFICATE_PINS: Map<String, List<String>> = mapOf(
        // Custom domain pins (update when deployed)
        "api.vettid.dev" to emptyList(),
        "vettid.dev" to emptyList()
        // When pins are available, add them like:
        // "api.vettid.dev" to listOf(
        //     "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", // Primary pin
        //     "sha256/BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB="  // Backup pin
        // )
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
        val builder = OkHttpClient.Builder()
            // Timeouts
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            // TLS configuration - only TLS 1.2 and 1.3
            .connectionSpecs(listOf(tlsSpec, ConnectionSpec.CLEARTEXT)) // CLEARTEXT needed for localhost in tests
            // Connection pool limits
            .connectionPool(connectionPool)

        // Add logging interceptor in debug builds
        if (enableLogging) {
            val loggingInterceptor = HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BODY
            }
            builder.addInterceptor(loggingInterceptor)
        }

        // Add certificate pinning if configured
        buildCertificatePinner()?.let { pinner ->
            builder.certificatePinner(pinner)
        }

        return builder.build()
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
