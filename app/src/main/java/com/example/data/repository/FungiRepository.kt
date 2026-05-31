package com.example.data.repository

import android.content.Context
import android.util.Log
import com.example.data.local.FungiDao
import com.example.data.remote.INatObservation
import com.example.data.remote.INaturalistApi
import com.example.data.remote.OpenMeteoApi
import com.example.model.HotspotCell
import com.example.model.Observation
import com.example.model.Species
import com.example.model.UserSighting
import com.example.util.MycoMath
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.*

class FungiRepository(
    private val context: Context,
    private val dao: FungiDao,
    private val iNatApi: INaturalistApi,
    private val openMeteoApi: OpenMeteoApi
) {
    private val moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()
    private val TAG = "FungiRepository"

    // TTL for iNaturalist observations cache (24 hours)
    private val CACHE_TTL_MS = 24 * 60 * 60 * 1000L

    val allSpeciesFlow: Flow<List<Species>> = dao.getAllSpeciesFlow()
    val allUserSightingsFlow: Flow<List<UserSighting>> = dao.getAllUserSightingsFlow()

    /**
     * Seeds the local database with species from the bundled assets species.json.
     */
    suspend fun seedDatabase() = withContext(Dispatchers.IO) {
        try {
            val existing = dao.getAllSpecies()
            if (existing.isEmpty()) {
                context.assets.open("species.json").use { inputStream ->
                    val reader = InputStreamReader(inputStream)
                    val jsonString = reader.readText()
                    val listType = Types.newParameterizedType(List::class.java, Species::class.java)
                    val adapter = moshi.adapter<List<Species>>(listType)
                    val speciesList = adapter.fromJson(jsonString)
                    if (speciesList != null) {
                        dao.insertSpecies(speciesList)
                        Log.d(TAG, "Successfully seeded ${speciesList.size} species to Room database.")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to seed species database from assets or check existing record schema", e)
        }
    }

    suspend fun getSpeciesById(id: String): Species? = withContext(Dispatchers.IO) {
        dao.getSpeciesById(id)
    }

    suspend fun getAllSpecies(): List<Species> = withContext(Dispatchers.IO) {
        dao.getAllSpecies()
    }

    suspend fun insertUserSighting(sighting: UserSighting) = withContext(Dispatchers.IO) {
        dao.insertUserSighting(sighting)
    }

    suspend fun deleteUserSighting(sighting: UserSighting) = withContext(Dispatchers.IO) {
        dao.deleteUserSighting(sighting)
    }

    suspend fun getAllUserSightings(): List<UserSighting> = withContext(Dispatchers.IO) {
        dao.getAllUserSightings()
    }

    /**
     * Fetch iNaturalist observations with offline-first Room cache with TTL.
     */
    suspend fun getObservations(
        species: Species,
        lat: Double,
        lng: Double,
        radiusKm: Double,
        forceRefresh: Boolean = false
    ): List<Observation> = withContext(Dispatchers.IO) {
        // 1. Check cached observations in Room
        val cached = dao.getCachedObservations(species.id)
        val now = System.currentTimeMillis()

        // Filter those within radius distance from the center target (cached observations might be broader)
        val inRadiusCached = cached.filter {
            calculateDistanceMeters(lat, lng, it.lat, it.lng) <= radiusKm * 1000.0
        }

        val isCacheValid = cached.isNotEmpty() && (now - cached.maxOf { it.cachedAt }) < CACHE_TTL_MS

        if (isCacheValid && !forceRefresh) {
            Log.d(TAG, "Returning ${inRadiusCached.size} cached observations from Room (cache fresh)")
            return@withContext inRadiusCached
        }

        // 2. Cache is stale or empty or forceRefresh requested: Fetch from network
        try {
            Log.d(TAG, "Fetching observations from iNaturalist API for ${species.scientificName} at ($lat, $lng)")
            // 5 years ago date
            val cal = Calendar.getInstance()
            cal.add(Calendar.YEAR, -5)
            val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            val sinceDate = sdf.format(cal.time)

            val response = iNatApi.getObservations(
                taxonName = species.scientificName,
                lat = lat,
                lng = lng,
                radiusKm = radiusKm,
                sinceDate = sinceDate
            )

            val iNatObsList = response.results
            val freshObservations = iNatObsList.mapNotNull { iObs ->
                // Parse coordinates. iNaturalist usually returns coordinates in the
                // `geojson` field as [lng, lat], and/or `location` as "lat,lng".
                // Try the explicit lat/lng first, then location string, then geojson.
                val geoLng = iObs.geojson?.coordinates?.getOrNull(0)
                val geoLat = iObs.geojson?.coordinates?.getOrNull(1)
                val parsedLat = iObs.latitude
                    ?: iObs.location?.split(",")?.getOrNull(0)?.trim()?.toDoubleOrNull()
                    ?: geoLat
                val parsedLng = iObs.longitude
                    ?: iObs.location?.split(",")?.getOrNull(1)?.trim()?.toDoubleOrNull()
                    ?: geoLng

                if (parsedLat != null && parsedLng != null) {
                    val obsTime = parseObsDate(iObs.observedOn)
                    Observation(
                        id = iObs.id,
                        speciesId = species.id,
                        lat = parsedLat,
                        lng = parsedLng,
                        observedAt = obsTime,
                        source = "iNaturalist",
                        photoUrl = iObs.photos?.firstOrNull()?.url,
                        qualityGrade = iObs.qualityGrade ?: "research",
                        cachedAt = now
                    )
                } else {
                    null
                }
            }

            // Save new network result to local Room Cache
            if (freshObservations.isNotEmpty()) {
                // Clear and replace cache for this species to maintain fresh cache representation
                dao.clearObservationsForSpecies(species.id)
                dao.insertObservations(freshObservations)
                Log.d(TAG, "Fetched and cached ${freshObservations.size} fresh observations in Room.")
            }
            
            // Return observations filtered by radius
            return@withContext freshObservations.filter {
                calculateDistanceMeters(lat, lng, it.lat, it.lng) <= radiusKm * 1000.0
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch from iNaturalist API, returning stale cache", e)
            // Offline fallback: return whatever elements we have cached (even if stale) as it is offline-first!
            return@withContext inRadiusCached
        }
    }

    /**
     * Fetches rainfall and temperature in past 30 days from Open-Meteo.
     * Returns total rainfall in mm, and average max recorded temperature in C.
     */
    suspend fun getWeatherLast30Days(lat: Double, lng: Double): Pair<Double, Double> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Fetching rainfall and temperature from Open-Meteo for ($lat, $lng)")
            val response = openMeteoApi.getPastWeather(limitLat = lat, limitLng = lng)
            val sumList = response.daily.precipitationSum
            val maxList = response.daily.temperatureMax

            // Sum of last 30 days rainfall
            val totalRainfall = if (sumList != null && sumList.isNotEmpty()) {
                sumList.sum()
            } else {
                0.0
            }

            // Average max temp over past 30 days
            val avgMaxTemp = if (maxList != null && maxList.isNotEmpty()) {
                maxList.average()
            } else {
                15.0
            }

            Log.d(TAG, "Historical weather (last 30 days): Rainfall: $totalRainfall mm, Avg Max Temp: $avgMaxTemp C")
            return@withContext Pair(totalRainfall, avgMaxTemp)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch weather from Open-Meteo, using default fallback (95.0mm, 15.5C)", e)
            // Returning a typical Victorian autumn default which permits moderate fruiting
            return@withContext Pair(95.0, 15.5)
        }
    }

    /**
     * Clear all caches for management.
     */
    suspend fun clearCaches() = withContext(Dispatchers.IO) {
        dao.clearAllCachedObservations()
    }

    /**
     * Hotspot Grid Calculation Algorithm (COMPUTED AT 500M RESOLUTION)
     */
    suspend fun generateHotspots(
        species: Species,
        centerLat: Double,
        centerLng: Double,
        radiusKm: Double,
        forceRefresh: Boolean = false
    ): List<HotspotCell> = withContext(Dispatchers.Default) {
        // 1. Get observations (including user sightings + iNaturalist)
        val iNatObs = getObservations(species, centerLat, centerLng, radiusKm, forceRefresh)
        val userSightings = dao.getAllUserSightings().filter {
            it.speciesId == species.id && calculateDistanceMeters(centerLat, centerLng, it.lat, it.lng) <= radiusKm * 1000.0
        }

        // 2. Fetch the weather (rainfall and max temp in last 30 days)
        val (rainfall, maxTemp) = getWeatherLast30Days(centerLat, centerLng)

        // Generate grid coordinates centered on centerLat, centerLng
        // 1 deg lat is ~111km, 500m is 0.0045 deg lat.
        // 1 deg lng at -37.8 deg lat is ~87.7km, 500m is 0.0057 deg lng.
        val latStep = 0.0045
        val lngStep = 0.0057

        // Number of steps in each direction based on search radius
        val latRangeSteps = ceil((radiusKm * 1000.0) / 500.0).toInt()
        val cells = mutableListOf<HotspotCell>()

        val nowMs = System.currentTimeMillis()

        // Pre-evaluate seasonScore
        val calendar = Calendar.getInstance()
        val currentMonth = calendar.get(Calendar.MONTH) + 1 // 1-12
        val isInSeason = isMonthInSeason(currentMonth, species.seasonStart, species.seasonEnd)
        val seasonScore = if (isInSeason) 1.0 else 0.4

        // Pre-evaluate rainfallScore (concerning both 30-day precipitation and 30-day average max temperature limits)
        // Optimal range for past 30 days rainfall: 60.0mm to 200.0mm
        val rainfallFactor = when {
            rainfall in 60.0..200.0 -> 1.0
            rainfall < 60.0 -> {
                rainfall / 60.0
            }
            else -> {
                maxOf(0.0, 1.0 - (rainfall - 200.0) / 200.0)
            }
        }

        // Optimal range for past 30 days average max temperature: 10.0C to 20.0C (ideal conditions for fungi growth)
        val tempFactor = when {
            maxTemp in 10.0..20.0 -> 1.0
            maxTemp < 10.0 -> {
                maxOf(0.0, maxTemp / 10.0)
            }
            else -> {
                maxOf(0.0, 1.0 - (maxTemp - 20.0) / 12.0)
            }
        }

        // Combine factors: Rainfall defines 60% of moisture suitability, and Temperature defines 40% of microclimate fit
        val rainfallScore = 0.6 * rainfallFactor + 0.4 * tempFactor

        // Generate grid
        for (i in -latRangeSteps..latRangeSteps) {
            coroutineContext.ensureActive()
            for (j in -latRangeSteps..latRangeSteps) {
                val cellLat = centerLat + i * latStep
                val cellLng = centerLng + j * lngStep

                // Check if grid point is within the radius circle
                val distanceToCenter = calculateDistanceMeters(centerLat, centerLng, cellLat, cellLng)
                if (distanceToCenter <= radiusKm * 1000.0) {
                    
                    // A. Local-evidence score: nearby research observations and
                    // user sightings, weighted by BOTH recency (365-day half-life)
                    // and proximity (Gaussian kernel, sigma 800 m, 2 km window).
                    val lambda = ln(2.0) / 365.0
                    val sigmaMeters = 800.0
                    val kernelRadiusMeters = 2000.0
                    val maxDaysBack = 5.0 * 365.0
                    var weightedCount = 0.0
                    var nearbyRecords = 0

                    // iNaturalist observations
                    for (obs in iNatObs) {
                        if (abs(obs.lat - cellLat) > 0.02 || abs(obs.lng - cellLng) > 0.03) continue
                        val d = calculateDistanceMeters(cellLat, cellLng, obs.lat, obs.lng)
                        if (d > kernelRadiusMeters) continue
                        val diffDays = (nowMs - obs.observedAt).toDouble() / (1000.0 * 60.0 * 60.0 * 24.0)
                        if (diffDays !in 0.0..maxDaysBack) continue
                        val timeWeight = exp(-lambda * diffDays)
                        val spaceWeight = exp(-(d * d) / (2.0 * sigmaMeters * sigmaMeters))
                        weightedCount += timeWeight * spaceWeight
                        nearbyRecords++
                    }

                    // User sightings weigh slightly higher (first-hand, georeferenced)
                    for (sig in userSightings) {
                        if (abs(sig.lat - cellLat) > 0.02 || abs(sig.lng - cellLng) > 0.03) continue
                        val d = calculateDistanceMeters(cellLat, cellLng, sig.lat, sig.lng)
                        if (d > kernelRadiusMeters) continue
                        val diffDays = (nowMs - sig.timestamp).toDouble() / (1000.0 * 60.0 * 60.0 * 24.0)
                        if (diffDays !in 0.0..maxDaysBack) continue
                        val timeWeight = exp(-lambda * diffDays)
                        val spaceWeight = exp(-(d * d) / (2.0 * sigmaMeters * sigmaMeters))
                        weightedCount += 1.5 * timeWeight * spaceWeight
                        nearbyRecords++
                    }

                    // ~3 strong, recent, nearby records saturate the local score.
                    val observationScore = minOf(1.0, weightedCount / 3.0)

                    // B. Combine multiplicatively. Conditions (season x weather)
                    // set the ceiling; local evidence drives most of the signal.
                    // With no nearby records the score stays low — an honest
                    // "no evidence here" rather than a flat medium everywhere.
                    val environmental = seasonScore * rainfallScore
                    val finalScore = environmental * (0.20 + 0.80 * observationScore)

                    // C. Determine Tier: >=0.66 High, >=0.33 Medium, else Low
                    val tier = when {
                        finalScore >= 0.66 -> "High"
                        finalScore >= 0.33 -> "Medium"
                        else -> "Low"
                    }

                    // D. Contributing factors (descriptive, not additive percentages)
                    val factors = mutableListOf<String>()
                    factors.add("Grid cell at (${String.format(Locale.US, "%.4f", cellLat)}, ${String.format(Locale.US, "%.4f", cellLng)})")
                    factors.add("Local evidence: $nearbyRecords record(s) within 2 km, recency + proximity weighted to ${String.format(Locale.US, "%.0f", observationScore * 100)}% of saturation")
                    if (isInSeason) {
                        factors.add("In season: ${monthName(species.seasonStart)}–${monthName(species.seasonEnd)} (current ${monthName(currentMonth)}) — full seasonal weighting")
                    } else {
                        factors.add("Out of season: typical window ${monthName(species.seasonStart)}–${monthName(species.seasonEnd)}, current ${monthName(currentMonth)} — score reduced")
                    }
                    factors.add("Conditions (past 30d): ${String.format(Locale.US, "%.1f", rainfall)} mm rain (ideal 60–200) and ${String.format(Locale.US, "%.1f", maxTemp)}°C avg max (ideal 10–20) → suitability ${String.format(Locale.US, "%.0f", rainfallScore * 100)}%")
                    factors.add("Heuristic estimate from sparse data — not a guarantee of presence.")

                    cells.add(
                        HotspotCell(
                            lat = cellLat,
                            lng = cellLng,
                            score = finalScore,
                            tier = tier,
                            contributingFactors = factors
                        )
                    )
                }
            }
        }
        return@withContext cells
    }


    /**
     * Aggregate hotspot scoring across the entire seeded catalogue.
     *
     * Answers the question "where am I likely to find ANY fungi here", which is
     * the right framing during peak season: a single rare species may have
     * sparse local records, but combined evidence across 15+ species lights
     * the map up. Diversity (how many species are recorded nearby) becomes a
     * first-class signal.
     */
    suspend fun generateMultiSpeciesHotspots(
        centerLat: Double,
        centerLng: Double,
        radiusKm: Double,
        forceRefresh: Boolean = false
    ): List<HotspotCell> = withContext(Dispatchers.Default) {
        val allSpecies = dao.getAllSpecies()
        if (allSpecies.isEmpty()) return@withContext emptyList()

        // Pull observations for every species. The repository's per-species TTL
        // cache covers us; on a warm cache this completes immediately.
        val allObservations = mutableListOf<Observation>()
        for (species in allSpecies) {
            coroutineContext.ensureActive()
            try {
                val obs = getObservations(species, centerLat, centerLng, radiusKm, forceRefresh)
                allObservations.addAll(obs)
            } catch (e: Exception) {
                Log.w(TAG, "Skipping ${species.scientificName} in aggregate fetch: ${e.message}")
            }
        }
        val userSightings = dao.getAllUserSightings().filter {
            calculateDistanceMeters(centerLat, centerLng, it.lat, it.lng) <= radiusKm * 1000.0
        }

        val (rainfall, maxTemp) = getWeatherLast30Days(centerLat, centerLng)

        // Seasonal factor across the whole catalogue: how many species are
        // fruiting *right now*. In a Victorian autumn this is usually most of them.
        val currentMonth = Calendar.getInstance().get(Calendar.MONTH) + 1
        val inSeasonCount = allSpecies.count { isMonthInSeason(currentMonth, it.seasonStart, it.seasonEnd) }
        val inSeasonFraction = inSeasonCount.toDouble() / allSpecies.size
        val seasonScore = (0.4 + 0.6 * inSeasonFraction).coerceIn(0.0, 1.0)

        val rainfallFactor = when {
            rainfall in 60.0..200.0 -> 1.0
            rainfall < 60.0 -> rainfall / 60.0
            else -> maxOf(0.0, 1.0 - (rainfall - 200.0) / 200.0)
        }
        val tempFactor = when {
            maxTemp in 10.0..20.0 -> 1.0
            maxTemp < 10.0 -> maxOf(0.0, maxTemp / 10.0)
            else -> maxOf(0.0, 1.0 - (maxTemp - 20.0) / 12.0)
        }
        val weatherScore = 0.6 * rainfallFactor + 0.4 * tempFactor
        val environmental = seasonScore * weatherScore

        val latStep = 0.0045
        val lngStep = 0.0057
        val latRangeSteps = ceil((radiusKm * 1000.0) / 500.0).toInt()
        val cells = mutableListOf<HotspotCell>()
        val nowMs = System.currentTimeMillis()
        val lambda = ln(2.0) / 365.0
        val sigmaMeters = 800.0
        val kernelRadiusMeters = 2000.0
        val maxDaysBack = 5.0 * 365.0

        for (i in -latRangeSteps..latRangeSteps) {
            coroutineContext.ensureActive()
            for (j in -latRangeSteps..latRangeSteps) {
                val cellLat = centerLat + i * latStep
                val cellLng = centerLng + j * lngStep
                if (calculateDistanceMeters(centerLat, centerLng, cellLat, cellLng) > radiusKm * 1000.0) continue

                var weightedCount = 0.0
                var nearbyRecords = 0
                val nearbySpecies = mutableSetOf<String>()

                for (obs in allObservations) {
                    if (abs(obs.lat - cellLat) > 0.02 || abs(obs.lng - cellLng) > 0.03) continue
                    val d = calculateDistanceMeters(cellLat, cellLng, obs.lat, obs.lng)
                    if (d > kernelRadiusMeters) continue
                    val diffDays = (nowMs - obs.observedAt).toDouble() / (1000.0 * 60.0 * 60.0 * 24.0)
                    if (diffDays !in 0.0..maxDaysBack) continue
                    val timeWeight = exp(-lambda * diffDays)
                    val spaceWeight = exp(-(d * d) / (2.0 * sigmaMeters * sigmaMeters))
                    weightedCount += timeWeight * spaceWeight
                    nearbyRecords++
                    nearbySpecies.add(obs.speciesId)
                }
                for (sig in userSightings) {
                    if (abs(sig.lat - cellLat) > 0.02 || abs(sig.lng - cellLng) > 0.03) continue
                    val d = calculateDistanceMeters(cellLat, cellLng, sig.lat, sig.lng)
                    if (d > kernelRadiusMeters) continue
                    val diffDays = (nowMs - sig.timestamp).toDouble() / (1000.0 * 60.0 * 60.0 * 24.0)
                    if (diffDays !in 0.0..maxDaysBack) continue
                    val timeWeight = exp(-lambda * diffDays)
                    val spaceWeight = exp(-(d * d) / (2.0 * sigmaMeters * sigmaMeters))
                    weightedCount += 1.5 * timeWeight * spaceWeight
                    nearbyRecords++
                    nearbySpecies.add(sig.speciesId)
                }

                // Aggregate evidence saturates more slowly than single-species
                // (more sources contributing) — divide by 5 instead of 3.
                val observationScore = minOf(1.0, weightedCount / 5.0)

                // Slightly more generous baseline (0.25) for the aggregate mode
                // so peak-season + good-weather areas surface as plausible even
                // before evidence kicks in.
                val finalScore = environmental * (0.25 + 0.75 * observationScore)

                val tier = when {
                    finalScore >= 0.66 -> "High"
                    finalScore >= 0.33 -> "Medium"
                    else -> "Low"
                }

                val factors = mutableListOf<String>()
                factors.add("Grid cell at (${String.format(Locale.US, "%.4f", cellLat)}, ${String.format(Locale.US, "%.4f", cellLng)})")
                factors.add("Local evidence: $nearbyRecords record(s) across ${nearbySpecies.size} species within 2 km")
                factors.add("$inSeasonCount of ${allSpecies.size} catalogued species are fruiting in ${monthName(currentMonth)}")
                factors.add("Conditions (past 30d): ${String.format(Locale.US, "%.1f", rainfall)} mm rain (ideal 60-200) and ${String.format(Locale.US, "%.1f", maxTemp)}°C avg max (ideal 10-20)")
                factors.add("Aggregate heuristic across all catalogued species — not species-specific.")

                cells.add(HotspotCell(cellLat, cellLng, finalScore, tier, factors))
            }
        }
        return@withContext cells
    }

    // --- Support Math & Date Help Helpers ---

    private fun parseObsDate(dateStr: String?): Long {
        if (dateStr.isNullOrEmpty()) return System.currentTimeMillis() - 365L * 24 * 60 * 60 * 1000 // default 1 year ago
        return try {
            val formats = listOf(
                SimpleDateFormat("yyyy-MM-dd", Locale.US),
                SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US),
                SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            )
            var parsedDate: Date? = null
            for (f in formats) {
                try {
                    parsedDate = f.parse(dateStr)
                    if (parsedDate != null) break
                } catch (e: Exception) { /* continue */ }
            }
            parsedDate?.time ?: (System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000)
        } catch (e: Exception) {
            System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000
        }
    }

    private fun calculateDistanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double =
        MycoMath.haversineMeters(lat1, lon1, lat2, lon2)

    private fun isMonthInSeason(month: Int, start: Int, end: Int): Boolean =
        MycoMath.isMonthInSeason(month, start, end)

    private fun monthName(month: Int): String {
        return when (month) {
            1 -> "Jan"
            2 -> "Feb"
            3 -> "Mar"
            4 -> "Apr"
            5 -> "May"
            6 -> "Jun"
            7 -> "Jul"
            8 -> "Aug"
            9 -> "Sep"
            10 -> "Oct"
            11 -> "Nov"
            12 -> "Dec"
            else -> ""
        }
    }
}
