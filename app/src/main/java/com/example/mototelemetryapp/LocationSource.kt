package com.example.mototelemetryapp

import android.location.Location

// The location feed TelemetryService records against, so the real fused-location client and the
// simulator are interchangeable. Completes the set alongside ObdSource and OrientationSource: with
// all three simulated, a whole ride can be replayed with no bike, no sensors and no satellites.
//
// Deliberately a push callback rather than a StateFlow, to match how the platform actually
// delivers fixes - the service's distance accumulator needs every fix, and a conflating flow could
// drop one.
interface LocationSource {

    fun start(onLocation: (Location) -> Unit)

    fun stop()
}
