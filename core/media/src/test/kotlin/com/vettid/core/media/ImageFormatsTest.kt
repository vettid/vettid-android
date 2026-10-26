package com.vettid.core.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Sniffing, header-first bounds, metadata segments, name sanitizing and the memory cache (pure, no Android). */
class ImageFormatsTest {
    private fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test
    fun sniffingDecidesByTheBytesAlone() {
        assertEquals(ImageType.JPEG, ImageType.sniff(b(0xFF, 0xD8, 0xFF, 0xE0)))
        assertEquals(ImageType.PNG, ImageType.sniff(b(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0)))
        assertEquals(ImageType.WEBP, ImageType.sniff("RIFF\u0001\u0002\u0003\u0004WEBPVP8 ".toByteArray(Charsets.ISO_8859_1)))
        assertNull("GIF", ImageType.sniff("GIF89a....".toByteArray()))
        assertNull("HEIC", ImageType.sniff(b(0, 0, 0, 0x18) + "ftypheic".toByteArray()))
        assertNull("SVG", ImageType.sniff("<svg xmlns=\"http://www.w3.org/2000/svg\"/>".toByteArray()))
        assertNull("text", ImageType.sniff("hello".toByteArray()))
        assertNull("RIFF but WAVE", ImageType.sniff("RIFF\u0000\u0000\u0000\u0000WAVE".toByteArray(Charsets.ISO_8859_1)))
        assertNull("empty", ImageType.sniff(ByteArray(0)))
        // A JPEG sent as "photo.png" with mime image/png is a JPEG: the name and mime are never read.
        assertEquals(ImageType.JPEG, ImageType.sniff(TestImages.minimalJpegHeader(10, 10)))
    }

    @Test
    fun aDecompressionBombIsRefusedFromItsHeader() {
        val bomb = TestImages.pngHeaderOnly(60_000, 60_000)
        val d = ImageHeader.dimensions(bomb)!!
        assertEquals(Dimensions(60_000, 60_000), d)
        assertFalse(d.within(PhotoLimits.VIEW_MAX_SIDE, PhotoLimits.VIEW_MAX_PIXELS))
        assertFalse(d.within(PhotoLimits.SOURCE_MAX_SIDE, PhotoLimits.SOURCE_MAX_PIXELS))
    }

    @Test
    fun headersOfTheThreeTypes() {
        assertEquals(Dimensions(4_000, 3_000), ImageHeader.dimensions(TestImages.pngHeaderOnly(4_000, 3_000)))
        assertEquals(Dimensions(640, 480), ImageHeader.dimensions(TestImages.minimalJpegHeader(640, 480)))
        assertEquals(Dimensions(321, 123), ImageHeader.dimensions(TestImages.webpVp8xHeader(321, 123)))
        assertNull(ImageHeader.dimensions(TestImages.pngHeaderOnly(10, 10).copyOf(20)))
        assertNull(ImageHeader.dimensions("text".toByteArray()))
    }

    @Test
    fun aThumbnailOverAThousandPixelsIsRefusedFromItsHeader() {
        val d = ImageHeader.dimensions(TestImages.minimalJpegHeader(4_000, 300))!!
        assertFalse(d.within(PhotoLimits.THUMB_VIEW_MAX_SIDE, PhotoLimits.THUMB_VIEW_MAX_PIXELS))
        assertTrue(Dimensions(320, 240).within(PhotoLimits.THUMB_VIEW_MAX_SIDE, PhotoLimits.THUMB_VIEW_MAX_PIXELS))
    }

    @Test
    fun metadataSegmentsAreFound() {
        val withExif = TestImages.withApp1(TestImages.minimalJpegHeader(8, 8), TestImages.exifWithGpsAndOrientation(6))
        assertTrue(JpegSegments.hasMetadata(withExif))
        assertTrue(JpegSegments.APP1 in JpegSegments.markers(withExif))
        assertFalse(JpegSegments.hasMetadata(TestImages.minimalJpegHeader(8, 8)))
    }

    @Test
    fun namesAreSanitized() {
        assertEquals("etcpasswd", AttachmentNames.sanitize("../../etc/passwd"))
        assertEquals("etcpasswd", AttachmentNames.sanitize("../etc/passwd"))
        assertEquals("photogpj.exe", AttachmentNames.sanitize("photo‮gpj.exe"))
        assertEquals("ab", AttachmentNames.sanitize("a\u0000\u0007\nb"))
        assertEquals("hidden", AttachmentNames.sanitize("...hidden"))
        assertEquals("a b", AttachmentNames.sanitize("a​ ⁦b⁩"))
        assertEquals("windowsfile", AttachmentNames.sanitize("windows\\file"))
        assertNull(AttachmentNames.sanitize("‮/\\.."))
        assertNull(AttachmentNames.sanitize(null))
        val long = AttachmentNames.sanitize("x".repeat(300))!!
        assertEquals(PhotoLimits.NAME_MAX_CHARS, long.length)
        // Characters, not UTF-16 units: an emoji counts once and is never cut in half.
        val emoji = AttachmentNames.sanitize("📷".repeat(70))!!
        assertEquals(PhotoLimits.NAME_MAX_CHARS, emoji.codePointCount(0, emoji.length))
    }

    @Test
    fun theCacheIsBoundedLeastRecentlyUsedOutAndCleared() {
        val evicted = mutableListOf<String>()
        val c = MemoryCache<String, ByteArray>(100, { it.size.toLong() }) { evicted += String(it) }
        c.put("a", ByteArray(40) { 'a'.code.toByte() })
        c.put("b", ByteArray(40) { 'b'.code.toByte() })
        c.get("a") // a is now the most recent
        c.put("c", ByteArray(40) { 'c'.code.toByte() })
        assertNull(c.get("b"))
        assertEquals(80, c.usedBytes)
        c.put("huge", ByteArray(101))
        assertNull("larger than the bound: not kept", c.get("huge"))
        c.clear()
        assertEquals(0, c.size)
        assertEquals(0, c.usedBytes)
        assertTrue(evicted.any { it.startsWith("b") })
        assertTrue(evicted.any { it.startsWith("a") } && evicted.any { it.startsWith("c") })
    }
    @Test
    fun theSameObjectPutAgainIsNotZeroed() {
        val c = MemoryCache<String, ByteArray>(100, { it.size.toLong() }) { it.fill(0) }
        val v = ByteArray(10) { 7 }
        c.put("a", v)
        c.put("a", v)
        assertTrue(v.all { it == 7.toByte() })
        assertEquals(10, c.usedBytes)
    }
}
