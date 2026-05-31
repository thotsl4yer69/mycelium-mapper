package com.example.util

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure, side-effect-free helpers used by the hotspot prediction engine.
 *
 * Extracted into a standalone object so the core geospatial/seasonal logic
 * can be unit-tested on the JVM without Android or Room dependencies.
 */
object MycoMath {

    /**
     * Returns true if [month] (1-12) falls within the fruiting window
     * [start]..[end] (1-12), correctly handling windows that wrap across
     * the new year (e.g. start = 11 (Nov) through end = 2 (Feb)).
     */
    fun isMonthInSeason(month: Int, start: Int, end: Int): Boolean {
        return if (start <= end) {
            month in start..end
        } else {
            month >= start || month <= end
        }
    }

    /**
     * Great-circle distance between two lat/lng points in metres
     * using the haversine formula.
     */
    fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371e3 // Earth radius in metres
        val phi1 = lat1 * PI / 180.0
        val phi2 = lat2 * PI / 180.0
        val deltaPhi = (lat2 - lat1) * PI / 180.0
        val deltaLambda = (lon2 - lon1) * PI / 180.0

        val a = sin(deltaPhi / 2).pow(2) +
                cos(phi1) * cos(phi2) * sin(deltaLambda / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return r * c
    }
}
