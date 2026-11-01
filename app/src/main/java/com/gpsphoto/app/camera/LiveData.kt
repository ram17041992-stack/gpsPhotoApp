package com.gpsphoto.app.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.floor

/** Small network lookups for the stamp. Each returns null when offline; callers retry later. */

object MapSnapshot {
    private const val TILE = 256

    /** A [size]×[size] map image centred on the point, stitched from 256 px tiles. */
    fun fetch(context: Context, lat: Double, lng: Double, type: MapType, zoom: Int = 16, size: Int = 320): Bitmap? =
        runCatching {
            val (cx, cy) = tilePixel(lat, lng, zoom)
            val left = cx - size / 2.0
            val top = cy - size / 2.0
            val tiles = 1 shl zoom
            val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(out)
            for (tx in floor(left / TILE).toInt()..floor((left + size - 1) / TILE).toInt()) {
                for (ty in floor(top / TILE).toInt()..floor((top + size - 1) / TILE).toInt()) {
                    if (ty !in 0 until tiles) continue
                    val url = type.tileUrl.format(Locale.US, zoom, Math.floorMod(tx, tiles), ty)
                    val tile = download(context, url) ?: return@runCatching null
                    canvas.drawBitmap(tile, (tx * TILE - left).toFloat(), (ty * TILE - top).toFloat(), null)
                    tile.recycle()
                }
            }
            out
        }.getOrNull()

    private fun download(context: Context, url: String): Bitmap? =
        (URL(url).openConnection() as HttpURLConnection).run {
            setRequestProperty("User-Agent", "${context.packageName}/1.0 (Android)")
            connectTimeout = 8_000
            readTimeout = 8_000
            try {
                if (responseCode == 200) inputStream.use(BitmapFactory::decodeStream) else null
            } finally {
                disconnect()
            }
        }
}

object Weather {
    /** Current temperature and sky from Open-Meteo (free, no API key). */
    fun current(lat: Double, lng: Double): String? = runCatching {
        val url = URL(
            "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f&current=temperature_2m,weather_code"
                .format(Locale.US, lat, lng),
        )
        val body = (url.openConnection() as HttpURLConnection).run {
            connectTimeout = 8_000
            readTimeout = 8_000
            try { inputStream.bufferedReader().use { it.readText() } } finally { disconnect() }
        }
        val current = JSONObject(body).getJSONObject("current")
        weatherText(current.getDouble("temperature_2m"), current.getInt("weather_code"))
    }.getOrNull()
}

object StampSettingsStore {
    private const val PREFS = "stamp"

    fun load(context: Context): StampSettings {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val d = StampSettings()
        return StampSettings(
            template = enumOr(p.getString("template", null), d.template),
            mapType = enumOr(p.getString("mapType", null), d.mapType),
            coordFormat = enumOr(p.getString("coordFormat", null), d.coordFormat),
            showAddress = p.getBoolean("showAddress", d.showAddress),
            showCoords = p.getBoolean("showCoords", d.showCoords),
            showDateTime = p.getBoolean("showDateTime", d.showDateTime),
            showAltitude = p.getBoolean("showAltitude", d.showAltitude),
            showWeather = p.getBoolean("showWeather", d.showWeather),
            note = p.getString("note", d.note).orEmpty(),
            keepOriginal = p.getBoolean("keepOriginal", d.keepOriginal),
        )
    }

    fun save(context: Context, s: StampSettings) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("template", s.template.name)
            .putString("mapType", s.mapType.name)
            .putString("coordFormat", s.coordFormat.name)
            .putBoolean("showAddress", s.showAddress)
            .putBoolean("showCoords", s.showCoords)
            .putBoolean("showDateTime", s.showDateTime)
            .putBoolean("showAltitude", s.showAltitude)
            .putBoolean("showWeather", s.showWeather)
            .putString("note", s.note)
            .putBoolean("keepOriginal", s.keepOriginal)
            .apply()
    }

    private inline fun <reified T : Enum<T>> enumOr(name: String?, default: T): T =
        enumValues<T>().firstOrNull { it.name == name } ?: default
}
