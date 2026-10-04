package com.vettid.core.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.WriterException
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.vettid.core.ui.theme.VettIdShape

/** The modules of a QR code for [content] (error correction M, no margin), or null if it does not fit. */
fun qrMatrix(content: String): BitMatrix? = try {
    QRCodeWriter().encode(
        content,
        BarcodeFormat.QR_CODE,
        0,
        0,
        mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to 0,
            EncodeHintType.CHARACTER_SET to "UTF-8",
        ),
    )
} catch (_: WriterException) {
    null
} catch (_: IllegalArgumentException) {
    null
}

/**
 * A QR code (an invitation, VAULT-MESSAGING §6.4). Always dark modules on a
 * white card with a quiet zone, in both themes: scanners need that contrast,
 * so these two colours are the one place the palette does not apply.
 */
@Composable
fun QrCode(
    content: String,
    contentDescription: String,
    modifier: Modifier = Modifier,
    size: Dp = 240.dp,
) {
    val matrix = remember(content) { qrMatrix(content) }
    Box(
        modifier
            .semantics {
                this.contentDescription = contentDescription
                role = Role.Image
            }
            .background(QR_LIGHT, VettIdShape.card)
            .padding(QUIET_ZONE),
    ) {
        Canvas(Modifier.size(size)) {
            val m = matrix ?: return@Canvas
            val cell = this.size.width / m.width
            for (y in 0 until m.height) {
                var x = 0
                while (x < m.width) {
                    if (!m[x, y]) {
                        x++
                        continue
                    }
                    val start = x
                    while (x < m.width && m[x, y]) x++
                    // One rectangle per run, slightly overlapping, so no hairlines show between modules.
                    drawRect(QR_DARK, Offset(start * cell, y * cell), Size((x - start) * cell + HAIRLINE, cell + HAIRLINE))
                }
            }
        }
    }
}

private val QR_DARK = Color(0xFF000000)
private val QR_LIGHT = Color(0xFFFFFFFF)
private val QUIET_ZONE = 16.dp
private const val HAIRLINE = 0.5f
