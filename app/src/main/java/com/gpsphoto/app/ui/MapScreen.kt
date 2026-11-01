package com.gpsphoto.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.ZoomOutMap
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.doOnLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.gpsphoto.app.data.Place
import com.gpsphoto.app.data.hasLocation
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import java.io.File

@Composable
fun MapScreen(vm: PhotoViewModel, onOpen: (Long) -> Unit, onShowInGallery: (Place) -> Unit) {
    val places by vm.places.collectAsStateWithLifecycle()
    val visible by vm.visible.collectAsStateWithLifecycle()
    val focus by vm.mapFocus.collectAsStateWithLifecycle()
    val placeFilter by vm.placeFilter.collectAsStateWithLifecycle()
    val loaded by vm.loaded.collectAsStateWithLifecycle()
    val withoutLocation = remember(visible) { visible.count { !it.hasLocation } }
    var selectedKey by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = places.find { it.key == selectedKey }

    val context = LocalContext.current
    val mapView = remember { createMapView(context) }
    val badges = remember { HashMap<Int, Drawable>() }
    var fittedKeys by remember { mutableStateOf<Set<String>?>(null) }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            mapView.onDetach()
        }
    }

    // One numbered pin per place; refit the camera only when the set of places changes.
    LaunchedEffect(places) {
        mapView.overlays.removeAll { it is Marker }
        places.forEach { place ->
            mapView.overlays.add(Marker(mapView).apply {
                position = GeoPoint(place.lat, place.lng)
                title = place.name
                icon = badges.getOrPut(place.photos.size) { countBadge(context, place.photos.size) }
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                setOnMarkerClickListener { _, _ -> selectedKey = place.key; true }
            })
        }
        mapView.invalidate()
        val keys = places.mapTo(HashSet()) { it.key }
        if (keys != fittedKeys) {
            fittedKeys = keys
            mapView.doOnLayout { mapView.fitTo(places) }
        }
    }

    // "Show on map" from a photo: zoom to its place and open it.
    LaunchedEffect(focus, places) {
        val target = places.find { it.key == focus } ?: return@LaunchedEffect
        selectedKey = target.key
        mapView.doOnLayout {
            mapView.controller.setZoom(17.0)
            mapView.controller.animateTo(GeoPoint(target.lat, target.lng))
        }
        vm.mapFocus.value = null
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(12.dp),
        ) {
            placeFilter?.let {
                Surface(shape = RoundedCornerShape(8.dp), tonalElevation = 3.dp, shadowElevation = 3.dp) {
                    InputChip(
                        selected = true,
                        onClick = { vm.placeFilter.value = null },
                        label = { Text("Only: ${it.name}") },
                        trailingIcon = { Icon(Icons.Default.Close, contentDescription = "Show all places") },
                    )
                }
            }
            val note = when {
                loaded && places.isEmpty() -> "No photos with a location to show"
                withoutLocation > 0 -> "$withoutLocation photo${if (withoutLocation == 1) "" else "s"} without location not shown"
                else -> null
            }
            note?.let { Pill(it) }
        }

        SmallFloatingActionButton(
            onClick = { mapView.fitTo(places) },
            modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(top = 72.dp, end = 12.dp),
        ) { Icon(Icons.Default.ZoomOutMap, contentDescription = "Show all places") }

        selected?.let { place ->
            PlaceCard(
                place = place,
                onOpen = onOpen,
                onShowInGallery = { onShowInGallery(place) },
                onClose = { selectedKey = null },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

@Composable
private fun Pill(text: String) {
    Surface(shape = RoundedCornerShape(50), tonalElevation = 3.dp, shadowElevation = 3.dp) {
        Text(text, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
    }
}

@Composable
private fun PlaceCard(
    place: Place,
    onOpen: (Long) -> Unit,
    onShowInGallery: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier,
) {
    val dates = place.photos.map { it.photo.dateTaken }
    val first = formatDay(dates.min())
    val last = formatDay(dates.max())
    Card(modifier.fillMaxWidth().padding(12.dp)) {
        Column(Modifier.padding(vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp, end = 4.dp)) {
                Icon(Icons.Default.Place, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f).padding(start = 8.dp)) {
                    Text(place.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${place.photos.size} photo${if (place.photos.size == 1) "" else "s"} · " +
                            if (first == last) first else "$first – $last",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = "Close") }
            }
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(horizontal = 16.dp),
                modifier = Modifier.padding(vertical = 8.dp),
            ) {
                items(place.photos, key = { it.photo.id }) { p ->
                    AsyncImage(
                        model = p.photo.uri,
                        contentDescription = p.photo.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(88.dp).clip(RoundedCornerShape(8.dp)).clickable { onOpen(p.photo.id) },
                    )
                }
            }
            FilledTonalButton(onClick = onShowInGallery, modifier = Modifier.padding(horizontal = 16.dp)) {
                Icon(Icons.Default.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Show in gallery")
            }
        }
    }
}

private fun createMapView(context: Context): MapView {
    Configuration.getInstance().apply {
        load(context, context.getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
        userAgentValue = context.packageName
        osmdroidBasePath = File(context.cacheDir, "osmdroid")
        osmdroidTileCache = File(osmdroidBasePath, "tiles")
    }
    return MapView(context).apply {
        setTileSource(TileSourceFactory.MAPNIK)
        setMultiTouchControls(true)
        zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
        isTilesScaledToDpi = true
        setMinZoomLevel(3.0)
        setMaxZoomLevel(19.0)
        controller.setZoom(3.0)
    }
}

private fun MapView.fitTo(places: List<Place>) {
    when (places.size) {
        0 -> Unit
        1 -> {
            controller.setZoom(16.0)
            controller.setCenter(GeoPoint(places[0].lat, places[0].lng))
        }
        else -> zoomToBoundingBox(
            BoundingBox.fromGeoPoints(places.map { GeoPoint(it.lat, it.lng) }), false, (64 * resources.displayMetrics.density).toInt(),
        )
    }
}

/** Round pin showing how many photos were taken at the place. */
private fun countBadge(context: Context, count: Int): Drawable {
    val density = context.resources.displayMetrics.density
    val label = if (count > 999) "999+" else count.toString()
    val size = ((if (label.length > 2) 44 else 36) * density).toInt()
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val r = size / 2f
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    paint.color = 0xFFFFFFFF.toInt()
    canvas.drawCircle(r, r, r, paint)
    paint.color = 0xFF1565C0.toInt()
    canvas.drawCircle(r, r, r - 3 * density, paint)
    paint.color = 0xFFFFFFFF.toInt()
    paint.textSize = 13 * density
    paint.isFakeBoldText = true
    paint.textAlign = Paint.Align.CENTER
    canvas.drawText(label, r, r - (paint.descent() + paint.ascent()) / 2, paint)
    return BitmapDrawable(context.resources, bitmap)
}
