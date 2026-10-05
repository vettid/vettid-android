package com.vettid.core.ui.components

import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.WriterException
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.decoder.Mode
import com.google.zxing.qrcode.encoder.Encoder
import com.google.zxing.qrcode.encoder.QRCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The scanner's decoder reads QR codes of any version and any error-correction level from M up
 * (VAULT-MESSAGING §11.11.2, 0.10.6), as the account site, other portals and the app itself may draw them.
 */
class QrDecodeTest {
    /** The recovery QR's payload (§11.11.2): 146 bytes with a 32-hex vault id; version 8 at level M. */
    private val recovery = """{"v":1,"t":"r","vault_id":"0123456789abcdef0123456789abcdef","recovery_id":"01JA0RECVERY0000000000001X",""" +
        """"code":"SK01TG8WK2FYJ1Y5MEHJ5R5J7QZKWHX0"}"""

    /** A QR of [content] forced to [version] at [level], in byte mode; null when it does not fit. */
    private fun encode(content: String, level: ErrorCorrectionLevel, version: Int): QRCode? = try {
        Encoder.encode(content, level, mapOf(EncodeHintType.QR_VERSION to version, EncodeHintType.CHARACTER_SET to "UTF-8"))
    } catch (_: WriterException) {
        null
    }

    /**
     * The code as a camera's luminance plane: dark modules on a light background, a 4-module quiet zone,
     * [scale] pixels per module, placed off-centre in a larger frame whose rows are padded ([rowStride] > width).
     */
    private fun frame(qr: QRCode, scale: Int = 3): Triple<ByteArray, Int, Pair<Int, Int>> {
        val m = qr.matrix
        val quiet = 4
        val side = (m.width + 2 * quiet) * scale
        val width = side + 37
        val height = side + 23
        val stride = width + 16
        val data = ByteArray(stride * height) { LIGHT }
        for (py in 0 until height) {
            for (px in 0 until width) {
                val x = (px - 20) / scale - quiet
                val y = (py - 11) / scale - quiet
                val inside = px >= 20 && py >= 11 && x in 0 until m.width && y in 0 until m.height
                if (inside && m[x, y].toInt() == 1) data[py * stride + px] = DARK
            }
        }
        return Triple(data, stride, width to height)
    }

    private fun decode(qr: QRCode, scale: Int = 3): String? {
        val (data, stride, size) = frame(qr, scale)
        return QrFrameDecoder().decode(data, stride, size.first, size.second)
    }

    @Test
    fun theRecoveryQrDecodesAtEveryVersionAndLevelFromM() {
        val levels = listOf(ErrorCorrectionLevel.M, ErrorCorrectionLevel.Q, ErrorCorrectionLevel.H)
        var decoded = 0
        for (level in levels) {
            var smallest: Int? = null
            for (version in 1..40) {
                val qr = encode(recovery, level, version) ?: continue
                if (smallest == null) smallest = version
                assertEquals(Mode.BYTE, qr.mode)
                for (scale in 2..5) assertEquals("version $version, level $level, $scale px", recovery, decode(qr, scale))
                decoded++
            }
            assertTrue("no version fits at $level", smallest != null)
            if (level == ErrorCorrectionLevel.M) assertEquals(8, smallest) // the account site's version (§11.11.2)
        }
        assertTrue(decoded > 90)
    }

    @Test
    fun shortCodesDecodeAtSmallAndLargeVersions() {
        // Invitation and transfer links (§6.4, §6.7.1) are read through the same decoder.
        val link = "vettid://connect#eyJ2IjoyLCJ0IjoicCJ9"
        for (version in listOf(2, 3, 5, 7, 12, 25, 40)) {
            for (level in listOf(ErrorCorrectionLevel.M, ErrorCorrectionLevel.Q, ErrorCorrectionLevel.H)) {
                val qr = encode(link, level, version) ?: continue
                assertEquals("version $version, level $level", link, decode(qr))
            }
        }
    }

    @Test
    fun theAppsOwnCodeDecodes() {
        val m = qrMatrix(recovery)!!
        val qr = Encoder.encode(recovery, ErrorCorrectionLevel.M, mapOf(EncodeHintType.CHARACTER_SET to "UTF-8"))
        assertEquals(qr.matrix.width, m.width)
        assertEquals(recovery, decode(qr))
    }

    @Test
    fun theHintsDoNotRestrictTheCode() {
        assertFalse(DecodeHintType.PURE_BARCODE in QR_DECODE_HINTS)
        assertFalse(DecodeHintType.ALLOWED_LENGTHS in QR_DECODE_HINTS)
        assertEquals(setOf(DecodeHintType.POSSIBLE_FORMATS, DecodeHintType.TRY_HARDER, DecodeHintType.CHARACTER_SET), QR_DECODE_HINTS.keys)
    }

    @Test
    fun aFrameWithoutACodeIsNull() {
        val data = ByteArray(200 * 200) { LIGHT }
        assertNull(QrFrameDecoder().decode(data, 200, 200, 200))
    }

    private companion object {
        const val DARK: Byte = 0x10
        const val LIGHT: Byte = 0xF0.toByte()
    }
}
