package com.vettid.feature.settings

import com.vettid.core.ui.components.ProfilePhotos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * How the shot sits under the review's round frame (owner, 2026-10-08: "my head in my photo looks pretty small").
 * Normalised, so it survives a rotation (the frame's size on screen changes, the crop does not):
 * [zoom] is 1 when the shot's shorter side just fills the frame and grows as the member zooms in;
 * [centreX] and [centreY] are the crop's centre as fractions of the shot's width and height.
 */
data class Framing(val zoom: Float = 1f, val centreX: Float = 0.5f, val centreY: Float = 0.5f) {
    /** The default framing: centred, the shot fitted to the frame. */
    val isDefault: Boolean get() = this == Framing()
}

/** A square of the shot, in its pixels: what "Use photo" keeps. */
data class CropRect(val left: Int, val top: Int, val side: Int)

/**
 * The crop math of the review step, pure. The frame is a square (the circle drawn in it, as the avatar is shown);
 * the shot always covers it: never zoomed out past its shorter side, never panned off an edge, and never zoomed in
 * so far that the square under the frame is smaller than [ProfilePhotos.TARGET_SIDE] (it would be scaled up into blur).
 */
object PhotoCrop {
    /** One press of "Zoom in" or "Zoom out". */
    const val ZOOM_STEP = 1.25f

    /** Double-tap zooms to this (or the most allowed). */
    const val DOUBLE_TAP_ZOOM = 2f

    /** One accessibility "Move" step: this fraction of the frame. */
    const val NUDGE = 0.25f

    /** The most the shot of [w]×[h] can be zoomed in (1: not at all, when it is no larger than [target]). */
    fun maxZoom(w: Int, h: Int, target: Int = ProfilePhotos.TARGET_SIDE): Float =
        max(1f, min(w, h).toFloat() / target)

    /** [f] made valid for a shot of [w]×[h]: the zoom within 1..[maxZoom], the centre so the shot covers the frame. */
    fun clamp(f: Framing, w: Int, h: Int, maxZoom: Float = maxZoom(w, h)): Framing {
        val zoom = if (f.zoom.isNaN()) 1f else f.zoom.coerceIn(1f, maxZoom)
        val side = min(w, h) / zoom
        return Framing(zoom, centre(f.centreX, side / 2f / w), centre(f.centreY, side / 2f / h))
    }

    private fun centre(c: Float, half: Float): Float {
        val lo = min(half, MIDDLE)
        return if (c.isNaN()) MIDDLE else c.coerceIn(lo, 1f - lo)
    }

    /** The square of the shot under the frame, in its pixels (always inside the shot). */
    fun sourceRect(f: Framing, w: Int, h: Int): CropRect {
        val c = clamp(f, w, h)
        val short = min(w, h)
        val side = (short / c.zoom).roundToInt().coerceIn(1, short)
        val left = (c.centreX * w - side / 2f).roundToInt().coerceIn(0, w - side)
        val top = (c.centreY * h - side / 2f).roundToInt().coerceIn(0, h - side)
        return CropRect(left, top, side)
    }

    /** Screen pixels per shot pixel, for a frame [frame] px wide. */
    fun displayScale(f: Framing, w: Int, h: Int, frame: Float): Float = frame * clamp(f, w, h).zoom / min(w, h)

    /**
     * A pinch or drag over a frame [frame] px wide (as `detectTransformGestures` reports it): the point of the shot
     * that was under the previous centroid (`centroid - pan`) ends up under [centroidX], [centroidY] at the new zoom.
     */
    @Suppress("LongParameterList")
    fun gesture(
        f: Framing,
        w: Int,
        h: Int,
        frame: Float,
        centroidX: Float,
        centroidY: Float,
        panX: Float,
        panY: Float,
        zoomChange: Float,
    ): Framing {
        if (frame <= 0f) return clamp(f, w, h)
        val old = clamp(f, w, h)
        val scale = displayScale(old, w, h, frame)
        val qx = old.centreX * w + (centroidX - panX - frame / 2f) / scale
        val qy = old.centreY * h + (centroidY - panY - frame / 2f) / scale
        val zoom = (old.zoom * zoomChange).coerceIn(1f, maxZoom(w, h))
        val newScale = frame * zoom / min(w, h)
        val cx = qx - (centroidX - frame / 2f) / newScale
        val cy = qy - (centroidY - frame / 2f) / newScale
        return clamp(Framing(zoom, cx / w, cy / h), w, h)
    }

    /** The zoom set to [zoom], about the frame's centre (the slider). */
    fun zoomTo(f: Framing, w: Int, h: Int, zoom: Float): Framing = clamp(f.copy(zoom = zoom), w, h)

    /** "Zoom in" ([zoomIn]) or "Zoom out" by [ZOOM_STEP], about the frame's centre. */
    fun step(f: Framing, w: Int, h: Int, zoomIn: Boolean): Framing =
        zoomTo(f, w, h, clamp(f, w, h).zoom * if (zoomIn) ZOOM_STEP else 1f / ZOOM_STEP)

    /** Double-tap at [x], [y] of a frame [frame] px wide: zoomed in, back to the default; else zoomed in there. */
    fun doubleTap(f: Framing, w: Int, h: Int, frame: Float, x: Float, y: Float): Framing {
        val c = clamp(f, w, h)
        if (c.zoom > 1f + EPSILON || maxZoom(w, h) <= 1f + EPSILON) return Framing()
        val target = min(DOUBLE_TAP_ZOOM, maxZoom(w, h))
        return gesture(c, w, h, frame, x, y, 0f, 0f, target / c.zoom)
    }

    /** An accessibility "Move": the view shifts by [dx], [dy] steps of [NUDGE] of the frame (+x: shows more to the right). */
    fun nudge(f: Framing, w: Int, h: Int, dx: Int, dy: Int): Framing {
        val c = clamp(f, w, h)
        val side = min(w, h) / c.zoom
        return clamp(c.copy(centreX = c.centreX + dx * NUDGE * side / w, centreY = c.centreY + dy * NUDGE * side / h), w, h)
    }

    /** The shot can be moved that way (an accessibility action is offered only then). */
    fun canNudge(f: Framing, w: Int, h: Int, dx: Int, dy: Int): Boolean = nudge(f, w, h, dx, dy) != clamp(f, w, h)

    private const val EPSILON = 1e-3f
    private const val MIDDLE = 0.5f
}
