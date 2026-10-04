package com.vettid.core.ui.components

import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.Assert.assertEquals
import org.junit.Test

class QrCodeTest {
    @Test
    fun anInvitationPayloadRoundTripsThroughTheEncoderAndTheScannersDecoder() {
        val payload = """{"v":2,"t":"c","r":"https://relay.vettid.org","c":"abcdefghijklmnopqrstuvwxyz","h":"${"A".repeat(43)}",""" +
            """"k":"${"B".repeat(43)}","e":1791100000}"""
        val m = qrMatrix(payload)!!
        // Render with a quiet zone, 4 pixels per module, as a camera frame would see it.
        val scale = 4
        val quiet = 4
        val size = (m.width + 2 * quiet) * scale
        val pixels = IntArray(size * size) { i ->
            val x = i % size / scale - quiet
            val y = i / size / scale - quiet
            if (x in 0 until m.width && y in 0 until m.height && m[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        val text = QRCodeReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(size, size, pixels)))).text
        assertEquals(payload, text)
    }
}
