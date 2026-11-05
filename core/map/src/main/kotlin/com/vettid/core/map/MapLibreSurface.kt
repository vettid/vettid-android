package com.vettid.core.map

import android.content.Context
import android.widget.FrameLayout
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource

/**
 * The real map: MapLibre's [MapView] on the member's tile server, drawing the circle, the trail and the position. No location
 * component, no saved state (the view's state is never saved and `onSaveInstanceState` is never called), no marker images. The
 * map is destroyed with the composition (so at lock and when the share is hidden) and takes nothing with it.
 */
object MapLibreSurface : MapSurface {
    @Composable
    override fun Show(content: MapContent, modifier: Modifier) = MapLibreMapView(content, modifier)
}

private const val CIRCLE = "vettid-circle"
private const val TRAIL = "vettid-trail"
private const val POINT = "vettid-point"
private const val FRAME_PADDING_PX = 48
private const val CIRCLE_OPACITY = 0.2f
private const val TRAIL_WIDTH = 3f
private const val POINT_RADIUS = 6f
private const val POINT_STROKE = 2f
private val TAGS = Regex("<[^>]*>")

private class Drawing(val view: MapView) {
    var map: MapLibreMap? = null
    var style: Style? = null
    var content: MapContent? = null
    var framedFor: MapPoint? = null
    var loadedStyleUrl: String? = null
}

@Composable
private fun MapLibreMapView(content: MapContent, modifier: Modifier) {
    val context = LocalContext.current
    val accent = MaterialTheme.colorScheme.primary.toArgb()
    val outline = MaterialTheme.colorScheme.surface.toArgb()
    var attribution by remember { mutableStateOf("") }
    val fallback = stringResource(R.string.core_map_attribution_fallback)
    val drawing = remember { Drawing(newMapView(context)) }
    drawing.content = content
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val view = drawing.view
        view.onCreate(null)
        val observer = LifecycleEventObserver { _, event -> forward(view, event) }
        lifecycle.addObserver(observer)
        // A view made while the screen is already shown must catch up with it.
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) view.onStart()
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) view.onResume()
        view.getMapAsync { map ->
            drawing.map = map
            map.uiSettings.isCompassEnabled = false
            map.uiSettings.isRotateGesturesEnabled = false
            map.uiSettings.isTiltGesturesEnabled = false
            map.uiSettings.isAttributionEnabled = true
            map.uiSettings.isLogoEnabled = true
            loadStyle(drawing, accent, outline) { attribution = it }
        }
        onDispose {
            lifecycle.removeObserver(observer)
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) view.onPause()
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) view.onStop()
            drawing.map = null
            drawing.style = null
            view.onDestroy()
        }
    }
    Box(modifier.testTag("position_map")) {
        AndroidView(
            factory = { drawing.view },
            update = { loadStyle(drawing, accent, outline) { attribution = it } },
            modifier = Modifier.matchParentSize(),
        )
        Surface(
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 4.dp, bottom = 4.dp).testTag("position_map_attribution"),
        ) {
            Text(
                attribution.ifBlank { fallback },
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}

private fun newMapView(context: Context): MapView {
    MapSdk.ensure(context)
    return MapView(context).apply {
        layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        // The view's state (camera, position) is not saved with the screen.
        isSaveEnabled = false
        isSaveFromParentEnabled = false
    }
}

private fun forward(view: MapView, event: Lifecycle.Event) {
    when (event) {
        Lifecycle.Event.ON_START -> view.onStart()
        Lifecycle.Event.ON_RESUME -> view.onResume()
        Lifecycle.Event.ON_PAUSE -> view.onPause()
        Lifecycle.Event.ON_STOP -> view.onStop()
        else -> Unit
    }
}

/** Loads the style when it is new, then draws the content into it. */
private fun loadStyle(d: Drawing, accent: Int, outline: Int, onAttribution: (String) -> Unit) {
    val map = d.map ?: return
    val c = d.content ?: return
    if (d.loadedStyleUrl != c.styleUrl) {
        d.loadedStyleUrl = c.styleUrl
        d.style = null
        map.setStyle(c.styleUrl) { style ->
            d.style = style
            install(style, accent, outline)
            onAttribution(attributionOf(style))
            draw(d)
        }
    } else {
        draw(d)
    }
}

private fun attributionOf(style: Style): String =
    style.sources.mapNotNull { it.attribution }.map { TAGS.replace(it, "").trim() }.filter { it.isNotEmpty() }.distinct().joinToString(" ")

private fun install(style: Style, accent: Int, outline: Int) {
    style.addSource(GeoJsonSource(CIRCLE))
    style.addSource(GeoJsonSource(TRAIL))
    style.addSource(GeoJsonSource(POINT))
    style.addLayer(FillLayer("$CIRCLE-fill", CIRCLE).withProperties(PropertyFactory.fillColor(accent), PropertyFactory.fillOpacity(CIRCLE_OPACITY)))
    style.addLayer(LineLayer("$CIRCLE-line", CIRCLE).withProperties(PropertyFactory.lineColor(accent), PropertyFactory.lineWidth(1f)))
    style.addLayer(LineLayer("$TRAIL-line", TRAIL).withProperties(PropertyFactory.lineColor(accent), PropertyFactory.lineWidth(TRAIL_WIDTH)))
    style.addLayer(
        CircleLayer("$POINT-dot", POINT).withProperties(
            PropertyFactory.circleColor(accent),
            PropertyFactory.circleRadius(POINT_RADIUS),
            PropertyFactory.circleStrokeColor(outline),
            PropertyFactory.circleStrokeWidth(POINT_STROKE),
        ),
    )
}

private fun draw(d: Drawing) {
    val map = d.map ?: return
    val style = d.style ?: return
    val c = d.content ?: return
    (style.getSource(CIRCLE) as? GeoJsonSource)?.setGeoJson(MapShapes.circleGeoJson(c))
    (style.getSource(TRAIL) as? GeoJsonSource)?.setGeoJson(MapShapes.trailGeoJson(c))
    (style.getSource(POINT) as? GeoJsonSource)?.setGeoJson(MapShapes.pointGeoJson(c))
    val last = d.framedFor
    if (last == null || last.lat != c.position.lat || last.lon != c.position.lon) {
        d.framedFor = c.position
        val bounds = LatLngBounds.Builder().includes(MapShapes.framed(c).map { LatLng(it.lat, it.lon) }).build()
        map.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, FRAME_PADDING_PX))
    }
}
