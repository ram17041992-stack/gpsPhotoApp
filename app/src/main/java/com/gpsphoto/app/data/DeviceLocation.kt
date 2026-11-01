package com.gpsphoto.app.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** Single location fix via the platform LocationManager (no Play Services needed). */
object DeviceLocation {
    @SuppressLint("MissingPermission")
    suspend fun current(context: Context): Location? {
        val granted = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            .any { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
        if (!granted) return null
        val manager = context.getSystemService(LocationManager::class.java) ?: return null
        val provider = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .firstOrNull { manager.isProviderEnabled(it) }
        val fresh = if (provider != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            withTimeoutOrNull(10_000) {
                suspendCancellableCoroutine<Location?> { cont ->
                    val signal = CancellationSignal()
                    cont.invokeOnCancellation { signal.cancel() }
                    manager.getCurrentLocation(provider, signal, context.mainExecutor) { cont.resume(it) }
                }
            }
        } else null
        return fresh ?: manager.getProviders(true)
            .mapNotNull { manager.getLastKnownLocation(it) }
            .maxByOrNull { it.time }
    }
}
