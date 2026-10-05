package com.vettid.core.ui.components

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.multi.qrcode.QRCodeMultiReader
import com.google.zxing.qrcode.QRCodeReader

/**
 * What the scanner asks of ZXing: QR codes only, decoded as UTF-8, trying
 * harder. Nothing restricts the version or the error-correction level: the
 * recovery QR (VAULT-MESSAGING §11.11.2, 0.10.6) may be any version at level
 * M or higher, and invitation and transfer codes (§6.4, §6.7.1) are read the
 * same way. No `PURE_BARCODE`: a camera frame holds more than the code.
 */
internal val QR_DECODE_HINTS: Map<DecodeHintType, Any> = mapOf(
    DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
    DecodeHintType.TRY_HARDER to true,
    DecodeHintType.CHARACTER_SET to "UTF-8",
)

/**
 * Decodes camera frames. ZXing's single-code reader keeps the first three
 * finder-like patterns it trusts, and in some codes (at level M, for one,
 * versions 14 and 25 of the 146-byte recovery payload) a run of data modules
 * looks enough like a finder that it gives up on a perfect image. The
 * multiple-code reader tries every combination of the patterns it found, so
 * a frame the single reader cannot read goes to it before it counts as empty.
 */
internal class QrFrameDecoder {
    private val reader = QRCodeReader()
    private val multi = QRCodeMultiReader()

    /**
     * Decodes a luminance plane ([data], [rowStride] bytes per row, [width] × [height] pixels, as CameraX's Y
     * plane); null when it holds no QR code.
     */
    fun decode(data: ByteArray, rowStride: Int, width: Int, height: Int): String? {
        val bitmap = BinaryBitmap(HybridBinarizer(PlanarYUVLuminanceSource(data, rowStride, height, 0, 0, width, height, false)))
        return try {
            reader.decode(bitmap, QR_DECODE_HINTS).text
        } catch (_: ReaderException) {
            try {
                multi.decodeMultiple(bitmap, QR_DECODE_HINTS).firstOrNull()?.text
            } catch (_: ReaderException) {
                null
            }
        } finally {
            reader.reset()
            multi.reset()
        }
    }
}
