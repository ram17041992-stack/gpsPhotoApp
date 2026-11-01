package com.gpsphoto.app.data

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** Live location from the platform LocationManager (no Play Services needed). */
object DeviceLocation {
    /** Emits the last known fix, then GPS and network fixes every ~2 s. Caller must hold location permission. */
    @SuppressLint("MissingPermission")
    fun updates(context: Context): Flow<Location> = callbackFlow {
        val manager = context.getSystemService(LocationManager::class.java)
        if (manager == null) {
            close()
            return@callbackFlow
        }
        // All methods overridden: on Android 10 the non-location callbacks are not default methods.
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) { trySend(location) }
            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) = Unit
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        }
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { it in manager.allProviders }
        providers.mapNotNull { manager.getLastKnownLocation(it) }.maxByOrNull { it.time }?.let { trySend(it) }
        providers.forEach { manager.requestLocationUpdates(it, 2_000L, 0f, listener, Looper.getMainLooper()) }
        awaitClose { manager.removeUpdates(listener) }
    }
}
