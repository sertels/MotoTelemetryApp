package com.example.mototelemetryapp

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Looper
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

// The real thing: Google Play Services' fused location provider, wrapped so TelemetryService can
// hold it behind LocationSource without knowing which implementation it has.
class FusedLocationSource(context: Context, private val callbackLooper: Looper) : LocationSource {

    private val client = LocationServices.getFusedLocationProviderClient(context)
    private var callback: LocationCallback? = null

    @SuppressLint("MissingPermission")
    override fun start(onLocation: (Location) -> Unit) {
        stop()
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, UPDATE_INTERVAL_MS).build()
        val cb = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                onLocation(locationResult.lastLocation ?: return)
            }
        }
        callback = cb
        client.requestLocationUpdates(request, cb, callbackLooper)
    }

    override fun stop() {
        callback?.let { client.removeLocationUpdates(it) }
        callback = null
    }

    private companion object {
        const val UPDATE_INTERVAL_MS = 1000L
    }
}
