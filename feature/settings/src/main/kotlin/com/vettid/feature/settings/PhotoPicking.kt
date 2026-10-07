package com.vettid.feature.settings

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import com.vettid.core.ui.components.ProfilePhotos
import java.io.IOException
import kotlin.math.max
import kotlin.math.min

/**
 * Reads the picture the Photo Picker returned (no storage permission: the picker grants this one URI) and encodes
 * it for `profile.set{photo}` (VAULT-MESSAGING §10.8). [ImageDecoder] applies the EXIF orientation and decodes
 * scaled down (the shorter side to at most [DECODE_SIDE]); [ProfilePhotos.encode] re-encodes the pixels, so no
 * EXIF or location metadata of the original is sent. Blocking: call off the main thread.
 */
internal object PhotoPicking {
    private const val DECODE_SIDE = 1_024

    fun encode(context: Context, uri: Uri): String? = try {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val w = info.size.width
            val h = info.size.height
            val shorter = min(w, h)
            if (shorter > DECODE_SIDE) {
                val scale = DECODE_SIDE.toFloat() / shorter
                decoder.setTargetSize(max(1, (w * scale).toInt()), max(1, (h * scale).toInt()))
            }
        }
        val software = if (bitmap.config == Bitmap.Config.HARDWARE) bitmap.copy(Bitmap.Config.ARGB_8888, false) else bitmap
        ProfilePhotos.encodeBase64(software)
    } catch (_: IOException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: SecurityException) {
        null
    } catch (_: OutOfMemoryError) {
        null
    }
}
