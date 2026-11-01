package com.gpsphoto.app.data

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.location.Location
import android.net.Uri
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import java.io.InputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** All file writes live here. Existing photos are only ever read; new files go to [ALBUM]. */
object PhotoFiles {
    const val ALBUM = "Pictures/GPS Camera"
    private val collection: Uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    private val unsafeNameChars = Regex("[\\\\/:*?\"<>|]")
    private val exifTime = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss")
    private val exifOffset = DateTimeFormatter.ofPattern("xxx")

    /** Opens the unredacted file so GPS tags survive (requires ACCESS_MEDIA_LOCATION). */
    fun openOriginal(context: Context, uri: Uri): InputStream? = try {
        context.contentResolver.openInputStream(MediaStore.setRequireOriginal(uri))
    } catch (e: SecurityException) {
        context.contentResolver.openInputStream(uri)
    } catch (e: UnsupportedOperationException) {
        context.contentResolver.openInputStream(uri)
    }

    /**
     * Saves a photo taken with the in-app camera as a new JPEG in [ALBUM], with GPS, local
     * capture time (and its UTC offset) and the note written into EXIF. Returns the new Uri.
     */
    fun saveCapture(context: Context, bitmap: Bitmap, name: String, location: Location?, takenAt: Long, note: String): Uri {
        val resolver = context.contentResolver
        val target = requireNotNull(resolver.insert(collection, ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, ALBUM)
            put(MediaStore.Images.Media.DATE_TAKEN, takenAt)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        })) { "Couldn't create the photo file" }
        try {
            requireNotNull(resolver.openOutputStream(target)).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            requireNotNull(resolver.openFileDescriptor(target, "rw")).use { pfd ->
                ExifInterface(pfd.fileDescriptor).apply {
                    val time = Instant.ofEpochMilli(takenAt).atZone(ZoneId.systemDefault())
                    setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, exifTime.format(time))
                    setAttribute(ExifInterface.TAG_DATETIME, exifTime.format(time))
                    setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, exifOffset.format(time))
                    if (note.isNotBlank()) setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, note)
                    location?.let { setGpsInfo(it) }
                    saveAttributes()
                }
            }
            resolver.update(target, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            return target
        } catch (e: Exception) {
            resolver.delete(target, null, null)
            throw e
        }
    }

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
