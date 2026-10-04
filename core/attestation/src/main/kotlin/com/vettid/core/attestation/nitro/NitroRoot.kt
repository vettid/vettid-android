package com.vettid.core.attestation.nitro

import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64

/**
 * The pinned AWS Nitro Enclaves root certificate ("aws.nitro-enclaves",
 * G1, valid 2019-10-28 to 2049-10-28): the vendor's published file,
 * <https://aws-nitro-enclaves.amazonaws.com/AWS_NitroEnclaves_Root-G1.zip>,
 * identical to vettid-vault `vms/pins/roots/aws-nitro-enclaves-root-g1.pem`.
 * SHA-256 of the DER: [SHA256_HEX] (checked by NitroRootTest). Changing it
 * is an app release, as it is a release for the vault.
 */
object NitroRoot {
    const val SHA256_HEX = "641a0321a3e244efe456463195d606317ed7cdcc3c1756e09893f3c68f79bb5b"

    private const val DER_B64 =
        "MIICETCCAZagAwIBAgIRAPkxdWgbkK/hHUbMtOTn+FYwCgYIKoZIzj0EAwMwSTELMAkGA1UEBhMCVVMxDzANBgNVBAoMBkFt" +
        "YXpvbjEMMAoGA1UECwwDQVdTMRswGQYDVQQDDBJhd3Mubml0cm8tZW5jbGF2ZXMwHhcNMTkxMDI4MTMyODA1WhcNNDkxMDI4" +
        "MTQyODA1WjBJMQswCQYDVQQGEwJVUzEPMA0GA1UECgwGQW1hem9uMQwwCgYDVQQLDANBV1MxGzAZBgNVBAMMEmF3cy5uaXRy" +
        "by1lbmNsYXZlczB2MBAGByqGSM49AgEGBSuBBAAiA2IABPwCVOumCMHzaHDimtqQvkY4MpJzbolL//Zy2YlES1BR5TSksfbb" +
        "48C8WBoyt7F2Bw7eEtaaP+ohG2bnUs990d0JX28TcPQXCEPZ3BABIeTPYwEoCWZEh8l5YoQwTcU/9KNCMEAwDwYDVR0TAQH/" +
        "BAUwAwEB/zAdBgNVHQ4EFgQUkCW1DdkFR+eWw5b6cp3PmanfS5YwDgYDVR0PAQH/BAQDAgGGMAoGCCqGSM49BAMDA2kAMGYC" +
        "MQCjfy+Rocm9Xue4YnwWmNJVA44fA0P5W2OpYow9OYCVRaEevL8uO1XYru5xtMPWrfMCMQCi85sWBbJwKKXdS6BptQFuZbT7" +
        "3o/gBh1qUxl/nNr12UO8Yfwr6wPLb+6NIwLz3/Y="

    /** The root certificate. */
    val certificate: X509Certificate by lazy {
        val der = Base64.getDecoder().decode(DER_B64)
        CertificateFactory.getInstance("X.509").generateCertificate(der.inputStream()) as X509Certificate
    }
}
