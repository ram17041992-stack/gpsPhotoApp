package com.gpsphoto.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class PhotoQueriesTest {
    private val dar = ZoneId.of("Africa/Dar_es_Salaam")

    private fun photo(
        id: Long, title: String, date: String, lat: Double? = null, lng: Double? = null,
        place: String? = null, category: String = "", tags: List<String> = emptyList(),
    ) = PhotoWithTags(
        Photo(
            id = id, uri = "content://media/$id", fileName = "$title.jpg", mimeType = "image/jpeg",
            dateTaken = LocalDate.parse(date).atTime(12, 0).atZone(dar).toInstant().toEpochMilli(),
            sizeBytes = 1, width = 1, height = 1, lat = lat, lng = lng,
            cellKey = if (lat != null && lng != null) cellKeyOf(lat, lng) else null,
            placeName = place, title = title, category = category,
        ),
        tags.map { PhotoTag(id, it) },
    )

    private val beach = photo(1, "Beach sunset", "2026-03-14", -6.7924, 39.2083, "Kivukoni, Dar es Salaam", "Holiday", listOf("beach", "family"))
    private val beach2 = photo(2, "Beach walk", "2026-03-15", -6.7930, 39.2090, "Kivukoni, Dar es Salaam", tags = listOf("beach"))
    private val kili = photo(3, "Summit", "2025-12-01", -3.0674, 37.3556, "Kilimanjaro", "Trek")
    private val noGps = photo(4, "Receipt", "2026-01-05")
    private val all = listOf(beach, beach2, kili, noGps)

    @Test fun nearbyPhotosShareACell() {
        assertEquals(beach.photo.cellKey, beach2.photo.cellKey)
        assertTrue(beach.photo.cellKey != kili.photo.cellKey)
    }

    @Test fun searchMatchesPlaceTagCategoryAndDate() {
        fun find(q: String) = all.filter { it.matches(PhotoFilter(query = q), dar) }.map { it.photo.id }
        assertEquals(listOf(1L, 2L), find("dar es salaam"))
        assertEquals(listOf(1L), find("family"))
        assertEquals(listOf(3L), find("trek"))
        assertEquals(listOf(1L, 2L), find("march 2026"))
        assertEquals(listOf(4L), find("2026-01-05"))
        assertEquals(listOf(2L), find("beach walk"))
    }

    @Test fun filtersByLocationPlaceAndDateRange() {
        assertFalse(noGps.matches(PhotoFilter(withLocationOnly = true), dar))
        assertTrue(beach2.matches(PhotoFilter(placeKey = beach.photo.cellKey), dar))
        assertFalse(kili.matches(PhotoFilter(placeKey = beach.photo.cellKey), dar))
        val march14Utc = LocalDate.parse("2026-03-14").atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli()
        val range = PhotoFilter(from = localDayStart(march14Utc, dar), to = localDayEnd(march14Utc, dar))
        assertEquals(listOf(1L), all.filter { it.matches(range, dar) }.map { it.photo.id })
    }

    @Test fun groupsByMonthNewestFirst() {
        val groups = groupPhotos(all.sortedWith(SortBy.NEWEST.comparator), GroupBy.DATE, dar)
        assertEquals(listOf("March 2026", "January 2026", "December 2025"), groups.map { it.label })
    }

    @Test fun groupsByTagWithUntaggedLast() {
        val groups = groupPhotos(all, GroupBy.TAG, dar)
        assertEquals(listOf("beach", "family", "No tags"), groups.map { it.label })
        assertEquals(2, groups[0].photos.size)
    }

    @Test fun groupsByPlaceAndBuildsMapPins() {
        assertEquals(listOf("Kilimanjaro", "Kivukoni, Dar es Salaam", "No location"), groupPhotos(all, GroupBy.PLACE, dar).map { it.label })
        val pins = placesOf(all)
        assertEquals(2, pins.size)
        assertEquals(2, pins.first { it.name.startsWith("Kivukoni") }.photos.size)
    }

    @Test fun sortsByNameAndCategory() {
        assertEquals(listOf(1L, 2L, 4L, 3L), all.sortedWith(SortBy.NAME.comparator).map { it.photo.id })
        assertEquals(listOf(1L, 3L, 2L, 4L), all.sortedWith(SortBy.CATEGORY.comparator).map { it.photo.id })
    }

    @Test fun parsesTagsWithoutDuplicates() {
        assertEquals(listOf("Beach", "family"), parseTags(" Beach, family,, beach "))
    }

    @Test fun distanceIsInMetres() {
        // 0.001° of latitude ≈ 111 m.
        assertEquals(111.2, distanceM(-6.0, 39.0, -6.001, 39.0), 0.5)
    }

    @Test fun namedPlaceCoversPhotosWithin50m() {
        val site = SavedPlace(id = 7, name = "Site A", lat = -6.7924, lng = 39.2083)
        assertEquals(site, nearestPlace(-6.7927, 39.2083, listOf(site)))      // ~33 m away
        assertEquals(null, nearestPlace(-6.7930, 39.2083, listOf(site)))      // ~67 m away
        val named = applySavedPlaces(all, listOf(site))
        assertEquals("Site A", named[0].photo.placeName)                       // beach, same spot
        assertEquals("Kivukoni, Dar es Salaam", named[1].photo.placeName)      // beach2, ~100 m away
        assertEquals(savedPlaceKey(site), named[0].photo.cellKey)
        assertEquals(listOf(1L), named.filter { it.matches(PhotoFilter(query = "site a"), dar) }.map { it.photo.id })
    }

    @Test fun nearestNamedPlaceWins() {
        val a = SavedPlace(id = 1, name = "Gate", lat = -6.79240, lng = 39.2083)
        val b = SavedPlace(id = 2, name = "Office", lat = -6.79270, lng = 39.2083)
        assertEquals("Office", nearestPlace(-6.79262, 39.2083, listOf(a, b))?.name)
    }
}
