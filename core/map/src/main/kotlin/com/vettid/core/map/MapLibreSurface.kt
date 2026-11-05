package com.vettid.core.map

import android.content.ComponentCallbacks
import android.content.Context
import android.content.res.Configuration
import android.view.MotionEvent
import android.widget.FrameLayout
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
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
 * component, no saved state (the view's state is never saved and `onSaveInstanceState` is never called), no marker images, no
 * attribution dialog (it builds feedback links from the camera's position). The SDK is set up by [MapSdk.prepare] first and the
 * view is made only when that has worked. The map is destroyed with the composition (so at lock and when the share is hidden).
 */
object MapLibreSurface : MapSurface {
    @Composable
    override fun Show(content: MapContent, modifier: Modifier) = MapHost(content, modifier)
}

private const val CIRCLE = "vettid-circle"
private const val TRAIL = "vettid-trail"
private const val POINT = "vettid-point"
private const val FRAME_PADDING_PX = 48
private const val CIRCLE_OPACITY = 0.2f
private const val TRAIL_WIDTH = 3f
private const val POINT_RADIUS = 6f
private const val POINT_STROKE = 2f

@Composable
private fun MapHost(content: MapContent, modifier: Modifier) {
    val context = LocalContext.current
    // The only server the map may reach: set before the style is loaded (this effect is recorded before the map's own).
    SideEffect { MapSdk.allowOnly(content.styleUrl) }
    LaunchedEffect(Unit) { MapSdk.prepare(context) }
    val sdk by MapSdk.state.collectAsState()
    when (sdk) {
        MapSdkState.READY -> MapReady(content, modifier)
        MapSdkState.FAILED -> MapMessage(stringResource(R.string.core_map_unavailable), modifier)
        else -> Box(modifier.testTag("position_map"))
    }
}

@Composable
private fun MapMessage(text: String, modifier: Modifier) {
    Box(modifier.testTag("position_map_message"), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp))
    }
}

/** The SDK side of the controller's seam: sets the style up with our layers and draws into it. */
private class MapLibreDrawer(
    private val accent: Int,
    private val outline: Int,
    private val onAttribution: (List<String?>) -> Unit,
) : MapDrawer {
    var map: MapLibreMap? = null
    var style: Style? = null
    var controller: MapController? = null

    override fun loadStyle(url: String) {
        val m = map ?: return
        style = null
        m.setStyle(url) { s ->
            style = s
            install(s)
            onAttribution(s.sources.map { it.attribution })
            controller?.onStyleLoaded()
        }
    }

    fun refreshAttribution() {
        style?.let { s -> onAttribution(s.sources.map { it.attribution }) }
    }

    override fun draw(content: MapContent) {
        val s = style ?: return
        (s.getSource(CIRCLE) as? GeoJsonSource)?.setGeoJson(MapShapes.circleGeoJson(content))
        (s.getSource(TRAIL) as? GeoJsonSource)?.setGeoJson(MapShapes.trailGeoJson(content))
        (s.getSource(POINT) as? GeoJsonSource)?.setGeoJson(MapShapes.pointGeoJson(content))
    }

    override fun frame(content: MapContent) {
        val m = map ?: return
        val bounds = LatLngBounds.Builder().includes(MapShapes.framed(content).map { LatLng(it.lat, it.lon) }).build()
        m.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, FRAME_PADDING_PX))
    }

    private fun install(style: Style) {
        style.addSource(GeoJsonSource(CIRCLE))
        style.addSource(GeoJsonSource(TRAIL))
        style.addSource(GeoJsonSource(POINT))
        val fill = FillLayer("$CIRCLE-fill", CIRCLE)
        style.addLayer(fill.withProperties(PropertyFactory.fillColor(accent), PropertyFactory.fillOpacity(CIRCLE_OPACITY)))
        style.addLayer(LineLayer("$CIRCLE-line", CIRCLE).withProperties(PropertyFactory.lineColor(accent), PropertyFactory.lineWidth(1f)))
        val trail = LineLayer("$TRAIL-line", TRAIL)
        style.addLayer(trail.withProperties(PropertyFactory.lineColor(accent), PropertyFactory.lineWidth(TRAIL_WIDTH)))
        style.addLayer(
            CircleLayer("$POINT-dot", POINT).withProperties(
                PropertyFactory.circleColor(accent),
                PropertyFactory.circleRadius(POINT_RADIUS),
                PropertyFactory.circleStrokeColor(outline),
                PropertyFactory.circleStrokeWidth(POINT_STROKE),
            ),
        )
    }
}

@Composable
@Suppress("LongMethod")
private fun MapReady(content: MapContent, modifier: Modifier) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val accent = MaterialTheme.colorScheme.primary.toArgb()
    val outline = MaterialTheme.colorScheme.surface.toArgb()
    var attribution by remember { mutableStateOf("") }
    var failed by remember { mutableStateOf(false) }
    val fallback = stringResource(R.string.core_map_attribution_fallback)
    val view = remember(lifecycle) { newMapView(context) }
    val drawer = remember(view) { MapLibreDrawer(accent, outline) { attribution = MapAttribution.clean(it) } }
    val controller = remember(drawer) { MapController(drawer).also { drawer.controller = it } }
    // Every new content reaches the map (a moving share keeps a current dot).
    SideEffect { controller.onContent(content) }
    DisposableEffect(view) {
        view.onCreate(null)
        val observer = LifecycleEventObserver { _, event -> forward(view, event) }
        lifecycle.addObserver(observer)
        // addObserver replays ON_START and ON_RESUME for a screen already shown.
        val memory = object : ComponentCallbacks {
            override fun onConfigurationChanged(newConfig: Configuration) = Unit

            override fun onLowMemory() = view.onLowMemory()
        }
        context.registerComponentCallbacks(memory)
        view.addOnDidFailLoadingMapListener { failed = true }
        view.addOnDidFinishLoadingMapListener {
            failed = false
            // A TileJSON's own credit replaces the fallback once it has loaded.
            drawer.refreshAttribution()
        }
        view.getMapAsync { map ->
            drawer.map = map
            map.uiSettings.isCompassEnabled = false
            map.uiSettings.isRotateGesturesEnabled = false
            map.uiSettings.isTiltGesturesEnabled = false
            // The (i) dialog builds "improve this map" links from the camera's position: our own credit text is shown instead.
            map.uiSettings.isAttributionEnabled = false
            // The logo would sit on our credit line, which carries the attribution.
            map.uiSettings.isLogoEnabled = false
            map.addOnCameraMoveStartedListener { reason ->
                if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) controller.onUserMoved()
            }
            controller.onMapReady()
        }
        onDispose {
            context.unregisterComponentCallbacks(memory)
            lifecycle.removeObserver(observer)
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) view.onPause()
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) view.onStop()
            drawer.map = null
            drawer.style = null
            view.onDestroy()
        }
    }
    Box(modifier.testTag("position_map")) {
        AndroidView(factory = { view }, modifier = Modifier.matchParentSize())
        if (failed) {
            Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f), modifier = Modifier.matchParentSize()) {
                MapMessage(stringResource(R.string.core_map_load_failed), Modifier.testTag("position_map_failed"))
            }
        }
        Surface(
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
            contentColor = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 4.dp, bottom = 4.dp).testTag("position_map_attribution"),
        ) {
            Text(
                MapAttribution.combine(fallback, attribution),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}

private fun newMapView(context: Context): MapView = MapView(context).apply {
    layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
    // The view's state (camera, position) is not saved with the screen.
    isSaveEnabled = false
    isSaveFromParentEnabled = false
    // Inside a scrolling page the map takes its own drags.
    setOnTouchListener { v, e ->
        v.parent?.requestDisallowInterceptTouchEvent(e.actionMasked != MotionEvent.ACTION_UP && e.actionMasked != MotionEvent.ACTION_CANCEL)
        false
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
