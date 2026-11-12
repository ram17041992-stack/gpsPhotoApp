package com.gpsphoto.app.camera

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Matrix
import android.location.Geocoder
import android.location.Location
import android.net.Uri
import android.util.Log
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gpsphoto.app.data.PhotoDatabase
import com.gpsphoto.app.data.PhotoFiles
import com.gpsphoto.app.data.SavedPlace
import com.gpsphoto.app.data.nearestPlace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/** What the camera currently knows about where the phone is. */
data class LiveInfo(
    val location: Location? = null,
    val place: String? = null,
    val addressLine: String? = null,
    val weather: String? = null,
    val map: Bitmap? = null,
)

/** [named] is the user's own name for this spot (see [SavedPlace]); it replaces the looked-up place name. */
fun stampContent(info: LiveInfo, s: StampSettings, now: Long, named: SavedPlace? = null): StampContent {
    val loc = info.location
    val title = named?.name
        ?: info.place
        ?: loc?.let { formatCoords(it.latitude, it.longitude, s.coordFormat) }
        ?: "Waiting for GPS…"
    val lines = buildList {
        if (s.showAddress) info.addressLine?.let(::add)
        if (s.showCoords && loc != null) add(formatCoords(loc.latitude, loc.longitude, s.coordFormat))
        if (s.showAltitude && loc != null && loc.hasAltitude()) {
            add("Altitude ${loc.altitude.roundToInt()} m  ·  Accuracy ±${loc.accuracy.roundToInt()} m")
        }
        if (s.showDateTime) add(formatStampTime(now))
        if (s.showWeather) info.weather?.let(::add)
        if (s.note.isNotBlank()) add(s.note.trim())
    }
    return StampContent(title, lines, info.map, s.mapType.attribution)
}

/** Re-fetch when the phone has moved [meters] or the data is older than [maxAgeMs]; retry failures after 30 s. */
private class Refresher(private val meters: Float, private val maxAgeMs: Long) {
    private var at: Location? = null
    private var time = 0L
    @Volatile var succeeded = false

    fun due(loc: Location, now: Long): Boolean {
        val last = at ?: return true
        return last.distanceTo(loc) > meters || now - time > if (succeeded) maxAgeMs else 30_000
    }

    fun start(loc: Location, now: Long) {
        at = loc
        time = now
        succeeded = false
    }

    fun reset() { at = null }
}

private const val TAG = "GpsCamera"

class CameraViewModel(app: Application) : AndroidViewModel(app) {
    val settings = MutableStateFlow(StampSettingsStore.load(app))
    val info = MutableStateFlow(LiveInfo())
    val busy = MutableStateFlow(false)
    val lastPhoto = MutableStateFlow<Uri?>(null)
    private val dao = PhotoDatabase.get(app).photoDao()
    val savedPlaces: StateFlow<List<SavedPlace>> = dao.observePlaces()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** The named place covering the current GPS fix, if any. */
    fun placeHere(loc: Location? = info.value.location): SavedPlace? =
        loc?.let { nearestPlace(it.latitude, it.longitude, savedPlaces.value) }

    /** Names the spot the phone is at now, or renames [existing]. */
    fun namePlace(existing: SavedPlace?, name: String) {
        val loc = info.value.location ?: return
        viewModelScope.launch {
            dao.upsertPlace(existing?.copy(name = name) ?: SavedPlace(name = name, lat = loc.latitude, lng = loc.longitude))
        }
    }

    fun removePlace(place: SavedPlace) {
        viewModelScope.launch { dao.deletePlace(place.id) }
    }

    private val place = Refresher(30f, 60 * 60_000L)
    private val map = Refresher(30f, 24 * 60 * 60_000L)
    private val weather = Refresher(2_000f, 30 * 60_000L)

    fun updateSettings(change: (StampSettings) -> StampSettings) {
        val old = settings.value
        val new = change(old)
        settings.value = new
        StampSettingsStore.save(getApplication(), new)
        if (new.mapType != old.mapType) {
            map.reset()
            info.update { it.copy(map = null) }
        }
        refresh()
    }

    fun onLocation(loc: Location) {
        val current = info.value.location
        // Keep a recent precise GPS fix rather than a coarser network fix that arrives just after it.
        if (current != null && loc.accuracy > current.accuracy && loc.time - current.time < 10_000) return
        info.update { it.copy(location = loc) }
        refresh()
    }

    private fun refresh() {
        val loc = info.value.location ?: return
        val s = settings.value
        val now = System.currentTimeMillis()
        if (place.due(loc, now)) {
            place.start(loc, now)
            launchIo { lookUpAddress(loc)?.let { (name, line) -> info.update { it.copy(place = name, addressLine = line) }; place.succeeded = true } }
        }
        if (s.template == Template.ADVANCED && map.due(loc, now)) {
            map.start(loc, now)
            launchIo {
                MapSnapshot.fetch(getApplication(), loc.latitude, loc.longitude, s.mapType)?.let { bmp ->
                    info.update { it.copy(map = bmp) }
                    map.succeeded = true
                }
            }
        }
        if (s.showWeather && weather.due(loc, now)) {
            weather.start(loc, now)
            launchIo { Weather.current(loc.latitude, loc.longitude)?.let { w -> info.update { it.copy(weather = w) }; weather.succeeded = true } }
        }
    }

    private fun launchIo(block: suspend () -> Unit) { viewModelScope.launch(Dispatchers.IO) { block() } }

    private fun lookUpAddress(loc: Location): Pair<String, String?>? {
        if (!Geocoder.isPresent()) return null
        val a = runCatching {
            @Suppress("DEPRECATION")
            Geocoder(getApplication(), Locale.getDefault()).getFromLocation(loc.latitude, loc.longitude, 1)?.firstOrNull()
        }.getOrNull() ?: return null
        val name = listOfNotNull(a.locality ?: a.subAdminArea, a.adminArea, a.countryName).distinct().joinToString(", ")
        return name.ifBlank { a.featureName ?: return null } to a.getAddressLine(0)
    }

    /** Captures, stamps and saves. [onDone] runs on the main thread with true on success. */
    fun capture(imageCapture: ImageCapture, onDone: (Boolean) -> Unit) {
        if (busy.value) return
        busy.value = true
        val takenAt = System.currentTimeMillis()
        val s = settings.value
        val snapshot = info.value
        val content = stampContent(snapshot, s, takenAt, placeHere(snapshot.location))
        val context = getApplication<Application>()
        imageCapture.takePicture(ContextCompat.getMainExecutor(context), object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                viewModelScope.launch(Dispatchers.Default) {
                    val ok = runCatching {
                        val photo = image.use { it.toUprightBitmap() }
                        val name = "GPS_" + fileTime.format(Instant.ofEpochMilli(takenAt).atZone(ZoneId.systemDefault()))
                        if (s.keepOriginal) {
                            PhotoFiles.saveCapture(context, photo, "${name}_original.jpg", snapshot.location, takenAt, s.note)
                        }
                        val stamped = StampRenderer.stamp(photo, content, s.template)
                        lastPhoto.value = PhotoFiles.saveCapture(context, stamped, "$name.jpg", snapshot.location, takenAt, s.note)
                        Log.i(TAG, "Saved $name.jpg ${stamped.width}x${stamped.height}, GPS ${if (snapshot.location != null) "yes" else "no"}")
                        stamped.recycle()
                    }.onFailure { Log.e(TAG, "Saving photo failed", it) }.isSuccess
                    busy.value = false
                    withContext(Dispatchers.Main) { onDone(ok) }
                }
            }

            override fun onError(exception: ImageCaptureException) {
                Log.e(TAG, "Capture failed", exception)
                busy.value = false
                onDone(false)
            }
        })
    }

    private companion object {
        val fileTime: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
    }
}

private fun ImageProxy.toUprightBitmap(): Bitmap {
    val raw = toBitmap()
    val degrees = imageInfo.rotationDegrees
    if (degrees == 0) return raw
    val rotated = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, Matrix().apply { postRotate(degrees.toFloat()) }, true)
    raw.recycle()
    return rotated
}
