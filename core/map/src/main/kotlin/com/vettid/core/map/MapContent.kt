package com.vettid.core.map

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** A point on the map. Memory only; it prints its type name. */
class MapPoint(val lat: Double, val lon: Double) {
    override fun toString(): String = "MapPoint"
}

/**
 * What the map draws: the latest position with a circle of [radiusM] metres, and the trail as a line when the share allows it and
 * the trail is held ([trail] has two points or more; otherwise it is empty). In memory only: it is built from the received
 * share every time and is in no saved state. [styleUrl] is the member's tile server style (HTTPS only).
 */
class MapContent(
    val styleUrl: String,
    val position: MapPoint,
    val radiusM: Double,
    val trail: List<MapPoint> = emptyList(),
    /** Which share this is (its id, never a position): the camera frames again when it changes, and follows until the member moves it. */
    val shareKey: String = "",
    /** The dot at the position: only for an Exact share. An area (Approximate, City) is drawn as its circle alone, never as a spot. */
    val showPoint: Boolean = true,
) {
    override fun toString(): String = "MapContent"

    /** The same picture: nothing to draw again. */
    fun sameDrawing(o: MapContent?): Boolean = o != null && styleUrl == o.styleUrl && radiusM == o.radiusM && showPoint == o.showPoint &&
        position.lat == o.position.lat && position.lon == o.position.lon && trail.size == o.trail.size &&
        trail.indices.all { trail[it].lat == o.trail[it].lat && trail[it].lon == o.trail[it].lon }
}

/** How the screen asks for a map: the real one ([MapLibreSurface]) or, in tests, a fake that records what it was given. */
fun interface MapSurface {
    @Composable
    fun Show(content: MapContent, modifier: Modifier)
}

/** The circle's rule: the position's own accuracy, or the share's nominal area when that is larger. */
object MapCircle {
    fun radiusM(accuracyM: Double?, nominalRadiusM: Double): Double = maxOf(accuracyM?.takeIf { it.isFinite() } ?: 0.0, nominalRadiusM)

    /** The trail is drawn only with two points or more. */
    fun trailOf(points: List<MapPoint>): List<MapPoint> = if (points.size >= 2) points else emptyList()
}

/** The shapes the map draws, as GeoJSON text (built here so that the drawing code only hands strings to the SDK). */
object MapShapes {
    private const val METRES_PER_DEGREE = 111_320.0
    private const val RING_POINTS = 64
    private const val MIN_COS = 0.01

    /** The circle of [radiusM] metres around the point, as [RING_POINTS] points (first and last the same). */
    fun ring(lat: Double, lon: Double, radiusM: Double): List<MapPoint> {
        val dLat = radiusM / METRES_PER_DEGREE
        val dLon = radiusM / (METRES_PER_DEGREE * maxOf(cos(Math.toRadians(lat)), MIN_COS))
        val points = (0 until RING_POINTS).map { i ->
            val a = 2 * PI * i / RING_POINTS
            MapPoint((lat + dLat * sin(a)).coerceIn(-MAX_LAT, MAX_LAT), lon + dLon * cos(a))
        }
        return points + points.first()
    }

    private const val MAX_LAT = 90.0

    private fun coordinates(points: List<MapPoint>) =
        points.joinToString(",", "[", "]") { String.format(Locale.ROOT, "[%.7f,%.7f]", it.lon, it.lat) }

    private fun feature(geometry: String) = """{"type":"Feature","properties":{},"geometry":$geometry}"""

    fun circleGeoJson(c: MapContent): String =
        feature("""{"type":"Polygon","coordinates":[${coordinates(ring(c.position.lat, c.position.lon, c.radiusM))}]}""")

    fun pointGeoJson(c: MapContent): String = if (!c.showPoint) EMPTY else
        feature(String.format(Locale.ROOT, """{"type":"Point","coordinates":[%.7f,%.7f]}""", c.position.lon, c.position.lat))

    /** No trail is an empty collection, not a line with no points. */
    fun trailGeoJson(c: MapContent): String {
        val trail = MapCircle.trailOf(c.trail)
        return if (trail.isEmpty()) EMPTY else feature("""{"type":"LineString","coordinates":${coordinates(trail)}}""")
    }

    const val EMPTY = """{"type":"FeatureCollection","features":[]}"""

    /** What the camera should show: the circle and the trail. */
    fun framed(c: MapContent): List<MapPoint> =
        ring(c.position.lat, c.position.lon, maxOf(c.radiusM, MIN_FRAME_M)) + MapCircle.trailOf(c.trail)

    /** A position with no accuracy is still shown with some map around it. */
    const val MIN_FRAME_M = 150.0
}
