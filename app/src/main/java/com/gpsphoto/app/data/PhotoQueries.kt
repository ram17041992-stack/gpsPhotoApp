package com.gpsphoto.app.data

import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Pure search / sort / group logic, kept free of Android types so it is unit-testable. */

val PhotoWithTags.tags: List<String> get() = tagRows.map { it.tag }.sorted()
val PhotoWithTags.hasLocation: Boolean get() = photo.lat != null && photo.lng != null

/** ~220 m grid cells: photos in the same cell count as "the same place". */
private const val CELL_DEG = 0.002

fun cellKeyOf(lat: Double, lng: Double): String =
    "${floor(lat / CELL_DEG).toLong()}:${floor(lng / CELL_DEG).toLong()}"

fun parseTags(text: String): List<String> =
    text.split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }

fun placeTitle(photo: Photo): String? = photo.placeName
    ?: if (photo.lat != null && photo.lng != null) "%.3f, %.3f".format(Locale.US, photo.lat, photo.lng) else null

fun localTime(millis: Long, zone: ZoneId = ZoneId.systemDefault()): ZonedDateTime =
    Instant.ofEpochMilli(millis).atZone(zone)

/** Material date pickers return UTC midnight; convert to the start/end of that day in local time. */
fun localDayStart(utcMidnight: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
    Instant.ofEpochMilli(utcMidnight).atZone(ZoneOffset.UTC).toLocalDate()
        .atStartOfDay(zone).toInstant().toEpochMilli()

fun localDayEnd(utcMidnight: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
    Instant.ofEpochMilli(utcMidnight).atZone(ZoneOffset.UTC).toLocalDate().plusDays(1)
        .atStartOfDay(zone).toInstant().toEpochMilli() - 1

data class PhotoFilter(
    val query: String = "",
    val withLocationOnly: Boolean = false,
    val from: Long? = null,
    val to: Long? = null,
    val placeKey: String? = null,
)

private val isoDay = DateTimeFormatter.ofPattern("yyyy-MM-dd")
private val longDay = DateTimeFormatter.ofPattern("d MMMM yyyy EEEE")
private val whitespace = Regex("\\s+")

/** Every search word must appear in the name, place, tags, category, description or date. */
fun PhotoWithTags.matches(f: PhotoFilter, zone: ZoneId = ZoneId.systemDefault()): Boolean {
    if (f.withLocationOnly && !hasLocation) return false
    if (f.placeKey != null && photo.cellKey != f.placeKey) return false
    if (f.from != null && photo.dateTaken < f.from) return false
    if (f.to != null && photo.dateTaken > f.to) return false
    val terms = f.query.lowercase().split(whitespace).filter { it.isNotEmpty() }
    if (terms.isEmpty()) return true
    val time = localTime(photo.dateTaken, zone)
    val haystack = listOf(
        photo.title, photo.fileName, photo.description, photo.category, photo.placeName.orEmpty(),
        tags.joinToString(" "), isoDay.format(time), longDay.format(time),
    ).joinToString(" ").lowercase()
    return terms.all { it in haystack }
}

enum class SortBy(val label: String, val comparator: Comparator<PhotoWithTags>) {
    NEWEST("Newest first", compareByDescending { it.photo.dateTaken }),
    OLDEST("Oldest first", compareBy { it.photo.dateTaken }),
    NAME("Name A–Z", compareBy(String.CASE_INSENSITIVE_ORDER) { it.photo.title }),
    PLACE(
        "Place",
        compareBy<PhotoWithTags, String?>(nullsLast(String.CASE_INSENSITIVE_ORDER)) { placeTitle(it.photo) }
            .thenByDescending { it.photo.dateTaken },
    ),
    CATEGORY(
        "Category",
        compareBy<PhotoWithTags, String?>(nullsLast(String.CASE_INSENSITIVE_ORDER)) { it.photo.category.ifEmpty { null } }
            .thenByDescending { it.photo.dateTaken },
    ),
}

enum class GroupBy(val label: String) { NONE("None"), DATE("Month"), PLACE("Place"), CATEGORY("Category"), TAG("Tag") }

data class PhotoGroup(val key: String, val label: String, val photos: List<PhotoWithTags>)

private val monthFormat = DateTimeFormatter.ofPattern("MMMM yyyy")

/** Groups keep the incoming (already sorted) photo order. */
fun groupPhotos(
    photos: List<PhotoWithTags>,
    by: GroupBy,
    zone: ZoneId = ZoneId.systemDefault(),
): List<PhotoGroup> = when (by) {
    GroupBy.NONE -> if (photos.isEmpty()) emptyList() else listOf(PhotoGroup("all", "All photos", photos))
    GroupBy.DATE -> photos.groupBy { YearMonth.from(localTime(it.photo.dateTaken, zone)) }
        .toSortedMap(reverseOrder())
        .map { (month, list) -> PhotoGroup(month.toString(), monthFormat.format(month), list) }
    GroupBy.PLACE -> bucket(photos, "No location") { listOfNotNull(placeTitle(it.photo)) }
    GroupBy.CATEGORY -> bucket(photos, "No category") { listOf(it.photo.category).filter(String::isNotBlank) }
    GroupBy.TAG -> bucket(photos, "No tags") { it.tags }
}

/** A photo can fall in several buckets (tags). Named buckets A–Z, the "missing" bucket last. */
private fun bucket(
    photos: List<PhotoWithTags>,
    missingLabel: String,
    keysOf: (PhotoWithTags) -> List<String>,
): List<PhotoGroup> {
    val buckets = LinkedHashMap<String, MutableList<PhotoWithTags>>()
    photos.forEach { p -> keysOf(p).ifEmpty { listOf("") }.forEach { buckets.getOrPut(it) { mutableListOf() }.add(p) } }
    return buckets.entries
        .sortedWith(compareBy<Map.Entry<String, List<PhotoWithTags>>>({ it.key.isEmpty() }, { it.key.lowercase() }))
        .map { PhotoGroup(it.key, it.key.ifEmpty { missingLabel }, it.value) }
}

/** A map pin: all visible photos taken in one grid cell. */
data class Place(val key: String, val name: String, val lat: Double, val lng: Double, val photos: List<PhotoWithTags>)

fun placesOf(photos: List<PhotoWithTags>): List<Place> = photos
    .filter { it.photo.cellKey != null && it.hasLocation }
    .groupBy { it.photo.cellKey!! }
    .map { (key, list) ->
        Place(
            key = key,
            name = placeTitle(list.first().photo)!!,
            lat = list.map { it.photo.lat!! }.average(),
            lng = list.map { it.photo.lng!! }.average(),
            photos = list,
        )
    }

/** Great-circle distance in metres. */
fun distanceM(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
    val dLat = Math.toRadians(lat2 - lat1)
    val dLng = Math.toRadians(lng2 - lng1)
    val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2).pow(2)
    return 6_371_000.0 * 2 * atan2(sqrt(a), sqrt(1 - a))
}

/** The closest named place whose radius covers the point, if any. */
fun nearestPlace(lat: Double, lng: Double, places: List<SavedPlace>): SavedPlace? = places
    .map { it to distanceM(lat, lng, it.lat, it.lng) }
    .filter { (place, d) -> d <= place.radiusM }
    .minByOrNull { it.second }?.first

/** Key used instead of the grid cell for photos inside a named place, so they share one group and map pin. */
fun savedPlaceKey(place: SavedPlace) = "saved:${place.id}"

/** Photos inside a named place take its name and are grouped together. Nothing is written to the database. */
fun applySavedPlaces(photos: List<PhotoWithTags>, places: List<SavedPlace>): List<PhotoWithTags> {
    if (places.isEmpty()) return photos
    return photos.map { p ->
        val lat = p.photo.lat
        val lng = p.photo.lng
        val place = if (lat != null && lng != null) nearestPlace(lat, lng, places) else null
        if (place == null) p else p.copy(photo = p.photo.copy(placeName = place.name, cellKey = savedPlaceKey(place)))
    }
}
