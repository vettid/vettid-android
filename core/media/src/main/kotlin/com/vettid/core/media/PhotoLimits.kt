package com.vettid.core.media

/** The bounds of ANDROID-PLAN 0.1.39 "Photo attachments" and VAULT-MESSAGING 0.32.0 §10.5.1's release constants. */
object PhotoLimits {
    /** A picked or taken picture larger than this on a side is refused before it is decoded ("too large to send"). */
    const val SOURCE_MAX_SIDE = 16_384

    /** … or larger than this many pixels. */
    const val SOURCE_MAX_PIXELS = 100_000_000L

    /** The most bytes read from a picked picture into memory. */
    const val SOURCE_MAX_BYTES = 64L * 1024 * 1024

    /** The long edge of a sent photo. */
    const val SEND_LONG_EDGE = 2_560

    /** `attachment_max_bytes` at vettid.org: the largest content whose blob fits the relay's 8 MiB. */
    const val ATTACHMENT_MAX_BYTES = 8_257_532

    /** The relay's `max_blob_bytes`: a fetched blob larger than this is refused before it is hashed. */
    const val MAX_BLOB_BYTES = 8_388_608

    /** The JPEG quality a photo starts at, lowered in steps until it fits. */
    const val SEND_QUALITY = 85
    const val MIN_QUALITY = 40
    const val QUALITY_STEP = 10

    /** The thumbnail's long edge and its bound (`thumb_max_bytes`). */
    const val THUMB_LONG_EDGE = 320
    const val THUMB_MAX_BYTES = 32_768
    const val THUMB_QUALITY = 80
    const val THUMB_MIN_QUALITY = 20

    /** A received photo: `photo_max_side` and 40 megapixels; nothing larger is decoded. */
    const val VIEW_MAX_SIDE = 8_192
    const val VIEW_MAX_PIXELS = 40_000_000L

    /** A received thumbnail: the sender makes 320, anything over 1,024 on a side is refused from its header. */
    const val THUMB_VIEW_MAX_SIDE = 1_024
    const val THUMB_VIEW_MAX_PIXELS = 1_024L * 1_024

    /** A sent name, as shown: at most 64 characters. */
    const val NAME_MAX_CHARS = 64

    /** The memory-only caches: decoded and encoded photos, and thumbnails. */
    const val PHOTO_CACHE_BYTES = 64L * 1024 * 1024
    const val THUMB_CACHE_BYTES = 8L * 1024 * 1024
}

/** A peer's `name` made safe to show; it is never used as a file name. */
object AttachmentNames {
    private val BIDI = setOf(0x061C, 0x200E, 0x200F) + (0x202A..0x202E) + (0x2066..0x2069)

    /**
     * Removes control and bidirectional formatting characters (and every other format character, zero-width ones
     * included), path separators and leading dots; trims; at most [PhotoLimits.NAME_MAX_CHARS] characters. Null when
     * nothing is left.
     */
    @Suppress("ReturnCount", "CyclomaticComplexMethod")
    fun sanitize(name: String?): String? {
        if (name == null) return null
        val sb = StringBuilder()
        var i = 0
        while (i < name.length) {
            val cp = name.codePointAt(i)
            i += Character.charCount(cp)
            val type = Character.getType(cp)
            val drop = type == Character.CONTROL.toInt() || type == Character.FORMAT.toInt() || cp in BIDI ||
                cp == '/'.code || cp == '\\'.code || type == Character.UNASSIGNED.toInt() ||
                type == Character.LINE_SEPARATOR.toInt() || type == Character.PARAGRAPH_SEPARATOR.toInt()
            if (!drop) sb.appendCodePoint(cp)
        }
        val s = sb.toString().trim().trimStart('.').trim()
        if (s.isEmpty()) return null
        if (s.codePointCount(0, s.length) <= PhotoLimits.NAME_MAX_CHARS) return s
        return s.substring(0, s.offsetByCodePoints(0, PhotoLimits.NAME_MAX_CHARS))
    }
}

/**
 * A memory-only cache bounded by bytes, least recently used out first (ANDROID-PLAN "Memory only, wiped at lock"):
 * thumbnails, photos and prepared uploads never go to a file, Room, DataStore or an image library's disk cache.
 * [clear] runs at lock, on entering the hold or the due state, at a wipe and at an unlink.
 */
class MemoryCache<K : Any, V : Any>(val maxBytes: Long, private val sizeOf: (V) -> Long, private val onEvict: (V) -> Unit = {}) {
    private val map = LinkedHashMap<K, V>(INITIAL, LOAD, true)
    private var bytes = 0L

    @get:Synchronized
    val size: Int get() = map.size

    @get:Synchronized
    val usedBytes: Long get() = bytes

    @Synchronized
    fun get(key: K): V? = map[key]

    /** Keeps [value] unless it alone exceeds the bound; evicts the least recently used until it fits. */
    @Synchronized
    fun put(key: K, value: V) {
        val n = sizeOf(value)
        // The same object put again is not evicted (its bytes would be zeroed under the caller).
        map.remove(key)?.let {
            bytes -= sizeOf(it)
            if (it !== value) onEvict(it)
        }
        if (n > maxBytes) return
        map[key] = value
        bytes += n
        val it = map.entries.iterator()
        while (bytes > maxBytes && it.hasNext()) {
            val e = it.next()
            if (e.key == key) continue
            bytes -= sizeOf(e.value)
            it.remove()
            onEvict(e.value)
        }
    }

    @Synchronized
    fun remove(key: K) {
        map.remove(key)?.let { bytes -= sizeOf(it); onEvict(it) }
    }

    /** Drops everything ([onEvict] zeroizes what it can). */
    @Synchronized
    fun clear() {
        map.values.forEach(onEvict)
        map.clear()
        bytes = 0
    }

    private companion object {
        const val INITIAL = 16
        const val LOAD = 0.75f
    }
}
