package com.gpsphoto.app.data

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.net.Uri
import android.provider.MediaStore
import androidx.core.content.edit
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.Locale

/** Mirrors the device photo library into the database. Reads files only; never writes them. */
object MediaSync {
    private val collection: Uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI

    private class MediaRow(
        val id: Long, val name: String, val mime: String, val date: Long,
        val size: Long, val width: Int, val height: Int,
    )

    suspend fun sync(context: Context, dao: PhotoDao) = withContext(Dispatchers.IO) {
        recoverLocationsIfNowAllowed(context, dao)
        val rows = queryMedia(context)
        val known = dao.allIds().toHashSet()
        val present = rows.mapTo(HashSet()) { it.id }
        dao.deleteAll(known.filterNot { it in present })
        // Small batches so the gallery fills in progressively on a first large import.
        rows.filterNot { it.id in known }.chunked(100).forEach { batch ->
            dao.insertPhotos(batch.map { readPhoto(context, it) })
        }
        nameNewPlaces(context, dao)
    }

    private fun queryMedia(context: Context): List<MediaRow> {
        val projection = arrayOf(
            MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.DATE_TAKEN, MediaStore.Images.Media.DATE_ADDED, MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.WIDTH, MediaStore.Images.Media.HEIGHT,
        )
        return context.contentResolver.query(collection, projection, null, null, null)?.use { c ->
            val id = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val name = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val mime = c.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
            val taken = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
            val added = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            val size = c.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            val width = c.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
            val height = c.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)
            buildList {
                while (c.moveToNext()) {
                    val rowId = c.getLong(id)
                    add(
                        MediaRow(
                            id = rowId,
                            name = c.getString(name) ?: "photo_$rowId.jpg",
                            mime = c.getString(mime) ?: "image/jpeg",
                            date = c.getLong(taken).takeIf { it > 0 } ?: (c.getLong(added) * 1000),
                            size = c.getLong(size),
                            width = c.getInt(width),
                            height = c.getInt(height),
                        )
                    )
                }
            }
        } ?: emptyList()
    }

    private fun readPhoto(context: Context, row: MediaRow): Photo {
        val uri = ContentUris.withAppendedId(collection, row.id)
        val latLng = readLatLng(context, uri)
        return Photo(
            id = row.id,
            uri = uri.toString(),
            fileName = row.name,
            mimeType = row.mime,
            dateTaken = row.date,
            sizeBytes = row.size,
            width = row.width,
            height = row.height,
            lat = latLng?.get(0),
            lng = latLng?.get(1),
            cellKey = latLng?.let { cellKeyOf(it[0], it[1]) },
            title = row.name.substringBeforeLast('.'),
        )
    }

    private fun readLatLng(context: Context, uri: Uri): DoubleArray? = runCatching {
        PhotoFiles.openOriginal(context, uri)?.use { ExifInterface(it).latLong }
    }.getOrNull()?.takeUnless { it[0] == 0.0 && it[1] == 0.0 }

    /**
     * Without ACCESS_MEDIA_LOCATION Android hides GPS data. If the user grants it later,
     * re-read the photos that were imported without a location (once).
     */
    private suspend fun recoverLocationsIfNowAllowed(context: Context, dao: PhotoDao) {
        val allowed = context.checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val prefs = context.getSharedPreferences("sync", Context.MODE_PRIVATE)
        if (!allowed || prefs.getBoolean("gpsReadable", false)) return
        dao.photosWithoutLocation().forEach { ref ->
            readLatLng(context, Uri.parse(ref.uri))?.let { (lat, lng) ->
                dao.setLocation(ref.id, lat, lng, cellKeyOf(lat, lng))
            }
        }
        prefs.edit { putBoolean("gpsReadable", true) }
    }

    /** Reverse-geocodes one point per grid cell. Needs internet; retried on the next sync if offline. */
    private suspend fun nameNewPlaces(context: Context, dao: PhotoDao) {
        if (!Geocoder.isPresent()) return
        val geocoder = Geocoder(context, Locale.getDefault())
        for (cell in dao.unnamedCells()) {
            val address = try {
                @Suppress("DEPRECATION")
                geocoder.getFromLocation(cell.lat, cell.lng, 1)?.firstOrNull()
            } catch (e: IOException) {
                return // offline: stop and try again next time
            }
            address?.let(::placeLabel)?.let { dao.setPlaceName(cell.cellKey, it) }
        }
    }

    private fun placeLabel(a: Address): String? =
        listOfNotNull(a.subLocality ?: a.locality ?: a.subAdminArea, a.adminArea ?: a.countryName)
            .distinct().joinToString(", ").ifBlank { a.featureName }
}
