package com.gpsphoto.app.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class StampFormatTest {
    @Test fun decimalCoordinates() {
        assertEquals("Lat -6.792354°  Long 39.208328°", formatCoords(-6.792354, 39.208328, CoordFormat.DECIMAL))
    }

    @Test fun dmsCoordinatesUseHemispheres() {
        assertEquals("6°47′32.5″ S  39°12′30.0″ E", formatCoords(-6.792354, 39.208328, CoordFormat.DMS))
    }

    @Test fun stampTimeShowsLocalTimeAndOffset() {
        val dar = ZoneId.of("Africa/Dar_es_Salaam")
        val millis = ZonedDateTime.of(2026, 11, 1, 21, 34, 0, 0, dar).toInstant().toEpochMilli()
        assertEquals("Sun, 01 Nov 2026  09:34 PM  GMT +03:00", formatStampTime(millis, dar))
    }

    @Test fun weatherCodes() {
        assertEquals("29°C · Partly cloudy", weatherText(28.6, 2))
        assertEquals("18°C · Thunderstorm", weatherText(18.0, 95))
        assertEquals("5°C", weatherText(5.0, 999))
    }

    @Test fun tilePixelMatchesKnownTile() {
        // Dar es Salaam city centre lies in OSM tile 16/39918/34011.
        val (x, y) = tilePixel(-6.8161, 39.2803, 16)
        assertEquals(39918, (x / 256).toInt())
        assertEquals(34011, (y / 256).toInt())
        val (ex, ey) = tilePixel(0.0, 0.0, 1)
        assertTrue(ex == 256.0 && ey == 256.0)
    }
}
