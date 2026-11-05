package com.vettid.core.map

/** What the controller asks of the real map (the SDK behind it; a fake in tests). */
interface MapDrawer {
    /** Loads the style and, once loaded and the shapes are set up, calls [MapController.onStyleLoaded]. */
    fun loadStyle(url: String)

    fun draw(content: MapContent)

    fun frame(content: MapContent)
}

/**
 * Decides what reaches the map and when: every new content is drawn (a moving share keeps a current dot); the camera frames the
 * first draw and a new share, then follows new positions only while the member has not panned or zoomed. A new style is loaded
 * and drawn again.
 */
class MapController(private val drawer: MapDrawer) {
    private var content: MapContent? = null
    private var styleUrl: String? = null
    private var styleReady = false
    private var drawn: MapContent? = null
    private var framedKey: String? = null
    private var moved = false

    fun onContent(c: MapContent) {
        content = c
        if (styleUrl != c.styleUrl) {
            styleUrl = c.styleUrl
            styleReady = false
            drawn = null
            drawer.loadStyle(c.styleUrl)
        } else {
            sync()
        }
    }

    /** The real map exists now: whatever was asked before it did is loaded. */
    fun onMapReady() {
        styleUrl = null
        content?.let { onContent(it) }
    }

    fun onStyleLoaded() {
        styleReady = true
        drawn = null
        sync()
    }

    /** The member panned or zoomed: their camera is kept until another share is shown. */
    fun onUserMoved() {
        moved = true
    }

    private fun sync() {
        val c = content ?: return
        if (!styleReady || c.sameDrawing(drawn)) return
        val newShare = framedKey != c.shareKey
        val positionChanged = drawn == null || drawn?.position?.lat != c.position.lat || drawn?.position?.lon != c.position.lon
        drawer.draw(c)
        if (newShare) {
            moved = false
            framedKey = c.shareKey
            drawer.frame(c)
        } else if (!moved && positionChanged) {
            drawer.frame(c)
        }
        drawn = c
    }
}
