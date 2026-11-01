package com.gpsphoto.app.data

import android.content.ContentValues
import android.content.Context
import android.location.Location
import android.net.Uri
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import java.io.InputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** All file writes live here. Existing photos are only ever read; new files go to [ALBUM]. */
object PhotoFiles {
    const val ALBUM = "Pictures/GPS Camera"
    private val collection: Uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    private val unsafeNameChars = Regex("[\\\\/:*?\"<>|]")

    /** Opens the unredacted file so GPS tags survive (requires ACCESS_MEDIA_LOCATION). */
    fun openOriginal(context: Context, uri: Uri): InputStream? = try {
        context.contentResolver.openInputStream(MediaStore.setRequireOriginal(uri))
    } catch (e: SecurityException) {
        context.contentResolver.openInputStream(uri)
    } catch (e: UnsupportedOperationException) {
        context.contentResolver.openInputStream(uri)
    }

    fun newCaptureUri(context: Context): Uri? {
        val name = "GPS_" + DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").format(LocalDateTime.now()) + ".jpg"
        return context.contentResolver.insert(collection, ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, ALBUM)
        })
    }

    fun discard(context: Context, uri: Uri) {
        context.contentResolver.delete(uri, null, null)
    }

    /**
     * For a photo this app just captured: writes GPS into its EXIF if the camera app did not.
     * Returns true when the photo ends up with a location.
     */
    fun ensureLocation(context: Context, uri: Uri, location: Location?): Boolean =
        context.contentResolver.openFileDescriptor(uri, "rw")?.use { pfd ->
            val exif = ExifInterface(pfd.fileDescriptor)
            when {
                exif.latLong != null -> true
                location == null -> false
                else -> {
                    exif.setGpsInfo(location)
                    exif.saveAttributes()
                    true
                }
            }
        } ?: false

    /**
     * Safe "rename": copies the original bytes (GPS and all EXIF intact) to a new file named
     * after the photo's title, adding the description and tags to the copy's EXIF.
     */
    fun saveEditedCopy(context: Context, photo: Photo, tags: List<String>): Boolean {
        val resolver = context.contentResolver
        val ext = photo.fileName.substringAfterLast('.', "jpg")
        val name = photo.title.replace(unsafeNameChars, "_").trim().ifEmpty { "photo" } + "." + ext
        val target = resolver.insert(collection, ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, photo.mimeType)
            put(MediaStore.Images.Media.RELATIVE_PATH, ALBUM)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }) ?: return false
        return try {
            requireNotNull(openOriginal(context, Uri.parse(photo.uri))).use { input ->
                requireNotNull(resolver.openOutputStream(target)).use { input.copyTo(it) }
            }
            if (photo.mimeType == "image/jpeg") {
                resolver.openFileDescriptor(target, "rw")?.use { pfd ->
                    ExifInterface(pfd.fileDescriptor).apply {
                        if (photo.description.isNotBlank()) setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, photo.description)
                        if (tags.isNotEmpty()) setAttribute(ExifInterface.TAG_USER_COMMENT, tags.joinToString(", "))
                        saveAttributes()
                    }
                }
            }
            resolver.update(target, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            true
        } catch (e: Exception) {
            resolver.delete(target, null, null)
            false
        }
    }
}
