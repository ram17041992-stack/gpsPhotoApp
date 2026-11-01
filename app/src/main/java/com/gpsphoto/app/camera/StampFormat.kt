package com.gpsphoto.app.camera

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.tan

/** Pure text/geometry helpers for the photo stamp, free of Android types so they are unit-testable. */

enum class Template(val label: String) { ADVANCED("Advanced"), CLASSIC("Classic"), MINIMAL("Minimal") }

enum class CoordFormat(val label: String) { DECIMAL("Decimal"), DMS("Degrees °′″") }

enum class MapType(val label: String, val tileUrl: String, val attribution: String) {
    STREET("Street", "https://tile.openstreetmap.org/%1\$d/%2\$d/%3\$d.png", "© OpenStreetMap"),
    SATELLITE(
        "Satellite",
        "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/%1\$d/%3\$d/%2\$d",
        "© Esri",
    ),
}

data class StampSettings(
    val template: Template = Template.ADVANCED,
    val mapType: MapType = MapType.STREET,
    val coordFormat: CoordFormat = CoordFormat.DECIMAL,
    val showAddress: Boolean = true,
    val showCoords: Boolean = true,
    val showDateTime: Boolean = true,
    val showAltitude: Boolean = false,
    val showWeather: Boolean = true,
    val note: String = "",
    val keepOriginal: Boolean = false,
)

fun formatCoords(lat: Double, lng: Double, format: CoordFormat): String = when (format) {
    CoordFormat.DECIMAL -> "Lat %.6f°  Long %.6f°".format(Locale.US, lat, lng)
    CoordFormat.DMS -> "${dms(lat, "N", "S")}  ${dms(lng, "E", "W")}"
}

private fun dms(value: Double, positive: String, negative: String): String {
    val totalSeconds = (abs(value) * 3600 * 10).roundToInt() / 10.0
    val degrees = (totalSeconds / 3600).toInt()
    val minutes = ((totalSeconds - degrees * 3600) / 60).toInt()
    val seconds = totalSeconds - degrees * 3600 - minutes * 60
    return "%d°%02d′%04.1f″ %s".format(Locale.US, degrees, minutes, seconds, if (value >= 0) positive else negative)
}

private val stampTime = DateTimeFormatter.ofPattern("EEE, dd MMM yyyy  hh:mm a  'GMT' xxx", Locale.ENGLISH)

/** Phone-local time with its UTC offset, e.g. "Sat, 01 Nov 2026  09:34 PM  GMT +03:00". */
fun formatStampTime(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    stampTime.format(Instant.ofEpochMilli(millis).atZone(zone))

/** WMO weather codes as used by Open-Meteo. */
fun weatherText(temperatureC: Double, code: Int): String {
    val sky = when (code) {
        0 -> "Clear sky"
        1, 2 -> "Partly cloudy"
        3 -> "Cloudy"
        45, 48 -> "Fog"
        in 51..57 -> "Drizzle"
        in 61..67 -> "Rain"
        in 71..77 -> "Snow"
        in 80..82 -> "Rain showers"
        85, 86 -> "Snow showers"
        in 95..99 -> "Thunderstorm"
        else -> null
    }
    return listOfNotNull("%.0f°C".format(Locale.US, temperatureC), sky).joinToString(" · ")
}

/** Web-Mercator global pixel position of a point at [zoom] for 256 px tiles. */
fun tilePixel(lat: Double, lng: Double, zoom: Int): Pair<Double, Double> {
    val scale = 256.0 * (1 shl zoom)
    val latRad = Math.toRadians(lat)
    val x = (lng + 180.0) / 360.0 * scale
    val y = (1 - ln(tan(latRad) + 1 / cos(latRad)) / PI) / 2 * scale
    return x to y
}
