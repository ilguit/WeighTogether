package com.palixander.scalesync.core.reference

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.palixander.scalesync.core.breed.BreedCatalog
import com.palixander.scalesync.core.breed.BreedSpecies
import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.Collections

enum class ReferenceSpecies { CAT, DOG }
enum class ReferenceSex { FEMALE, MALE }
enum class ReferenceBasis { BREED, WEIGHT_CATEGORY, POPULATION }
enum class ReferenceKind {
    EMPIRICAL_OBSERVATION_QUARTILES,
    EMPIRICAL_OBSERVATION_MEAN_SD,
    FITTED_BCCG_PERCENTILES,
    MODELLED_BREED_ADULT_RANGE,
}
enum class ReferenceCenterStatistic { MEDIAN, MEAN }
enum class ReferenceBoundsStatistic { QUARTILES, ONE_STANDARD_DEVIATION, P9_P91, ADULT_TYPICAL_RANGE }
enum class NumericalAvailability { AVAILABLE, NOT_REPRODUCIBLE_FROM_PUBLISHED_ARTIFACTS }
enum class ReferenceAgeAvailability { CARRY_FORWARD, DECLARED_RANGE_ONLY, EXACT_OBSERVATIONS }
enum class NumericalUnavailabilityReason {
    UNSUPPORTED_STATISTIC,
    MIXED_BREED_GROUP,
    INSUFFICIENT_AGE_SERIES,
    SOURCE_NOT_REDISTRIBUTABLE,
}

data class ReferencePoint(
    val ageDays: Int,
    val lowerKg: Double,
    val medianKg: Double,
    val upperKg: Double,
    val sourceId: String? = null,
    val empirical: Boolean = false,
)
data class ReferenceSource(
    val id: String,
    val citation: String,
    val publicationDoi: String,
    val dataDoi: String,
    val dataUrl: String,
    val upstreamArtifactSha256: String,
    val license: String,
    val licenseUrl: String,
    val accessedDate: String?,
    val correctionDoi: String?,
    val derivedArtifact: String?,
    val derivedArtifactSha256: String?,
    val derivationSoftware: String?,
)
data class ReferenceScope(
    val id: String,
    val species: ReferenceSpecies,
    val sex: ReferenceSex,
    val basis: ReferenceBasis,
    val weightCategory: String?,
    val breedId: String?,
    val minimumAdultWeightKg: Double?,
    val maximumAdultWeightKg: Double?,
    val minimumAgeDays: Int,
    val maximumAgeDays: Int,
    val constraints: List<String>,
    val sourceId: String?,
    val numericalAvailability: NumericalAvailability,
    val ageAvailability: ReferenceAgeAvailability = ReferenceAgeAvailability.CARRY_FORWARD,
    val unavailabilityReason: NumericalUnavailabilityReason? = null,
)
data class ReferenceProfile(
    val id: String,
    val species: ReferenceSpecies,
    val sex: ReferenceSex,
    val basis: ReferenceBasis,
    val weightCategory: String?,
    val breedId: String?,
    val sourceId: String,
    val citation: String,
    val license: String,
    val constraints: List<String>,
    val referenceKind: ReferenceKind,
    val minimumBinN: Int,
    val points: List<ReferencePoint>,
    val ageAvailability: ReferenceAgeAvailability = ReferenceAgeAvailability.CARRY_FORWARD,
    val centerStatistic: ReferenceCenterStatistic = ReferenceCenterStatistic.MEDIAN,
    val boundsStatistic: ReferenceBoundsStatistic = ReferenceBoundsStatistic.QUARTILES,
)
data class WeightReferenceManifest(
    val schemaVersion: Int,
    val snapshotVersion: String,
    val snapshotDate: String,
    val numericalDataSha256: String,
    val sources: List<ReferenceSource>,
    val scopes: List<ReferenceScope>,
)

/** Immutable presentation-safe metadata for one resolved reference profile. */
data class ReferenceProfileMetadata(
    val profileId: String,
    val basis: ReferenceBasis,
    val source: ReferenceSource,
    val constraints: List<String>,
    val referenceKind: ReferenceKind,
    val minimumBinN: Int,
    val centerStatistic: ReferenceCenterStatistic,
    val boundsStatistic: ReferenceBoundsStatistic,
)

class WeightReferenceSnapshot private constructor(
    val manifest: WeightReferenceManifest,
    val profiles: List<ReferenceProfile>,
) {
    /**
     * Looks up provenance and constraints after a profile has been resolved. The returned list is
     * detached and unmodifiable so presentation code cannot mutate the validated snapshot.
     */
    fun metadataFor(profileId: String): ReferenceProfileMetadata? {
        val profile = profiles.singleOrNull { it.id == profileId } ?: return null
        val source = manifest.sources.singleOrNull { it.id == profile.sourceId } ?: return null
        return ReferenceProfileMetadata(
            profileId = profile.id,
            basis = profile.basis,
            source = source.copy(),
            constraints = Collections.unmodifiableList(profile.constraints.toList()),
            referenceKind = profile.referenceKind,
            minimumBinN = profile.minimumBinN,
            centerStatistic = profile.centerStatistic,
            boundsStatistic = profile.boundsStatistic,
        )
    }

    /**
     * Returns no data before the first observation, linearly interpolates between every pair of
     * observations. Legacy profiles carry the final observation forward; declared-range profiles
     * stop at their final point, while exact-observation profiles resolve published ages only.
     */
    fun interpolate(profileId: String, ageDays: Int): ReferencePoint? {
        val profile = profiles.singleOrNull { it.id == profileId } ?: return null
        val points = profile.points
        if (profile.ageAvailability == ReferenceAgeAvailability.EXACT_OBSERVATIONS) {
            return points.singleOrNull { it.ageDays == ageDays }
        }
        // Maine Coon and Siberian retain a direct birth observation, but the breed model starts
        // at eight weeks. Never manufacture a bridge across the unsupported neonatal interval.
        if (profile.referenceKind == ReferenceKind.MODELLED_BREED_ADULT_RANGE &&
            points.firstOrNull()?.let { it.ageDays == 0 && it.empirical } == true && ageDays in 1 until 56
        ) return null
        if (ageDays < points.first().ageDays) return null
        if (ageDays > points.last().ageDays && profile.ageAvailability == ReferenceAgeAvailability.DECLARED_RANGE_ONLY) return null
        if (ageDays >= points.last().ageDays) return points.last().copy(ageDays = ageDays)
        points.binarySearch { it.ageDays.compareTo(ageDays) }.let { index ->
            if (index >= 0) return points[index]
            val upperIndex = -index - 1
            val lower = points[upperIndex - 1]
            val upper = points[upperIndex]
            val fraction = (ageDays - lower.ageDays).toDouble() / (upper.ageDays - lower.ageDays)
            fun between(a: Double, b: Double) = a + (b - a) * fraction
            return ReferencePoint(ageDays, between(lower.lowerKg, upper.lowerKg), between(lower.medianKg, upper.medianKg), between(lower.upperKg, upper.upperKg))
        }
    }

    companion object {
        const val RESOURCE_PATH = "weight_references.json"
        const val SUPPORTED_SCHEMA_VERSION = 3
        private val sha256Pattern = Regex("[0-9a-f]{64}")

        fun bundled(breedCatalog: BreedCatalog = BreedCatalog.bundled()): WeightReferenceSnapshot = load(
            streamProvider = {
                WeightReferenceSnapshot::class.java.classLoader.getResourceAsStream(RESOURCE_PATH)
                    ?: error("Bundled weight reference resource is missing: $RESOURCE_PATH")
            },
            breedCatalog = breedCatalog,
        )

        fun load(streamProvider: () -> InputStream, breedCatalog: BreedCatalog = BreedCatalog.bundled()): WeightReferenceSnapshot {
            val bytes = streamProvider().use(InputStream::readBytes)
            val root = JsonParser.parseString(bytes.toString(Charsets.UTF_8)).asJsonObject
            val document = Gson().fromJson(root, SnapshotJson::class.java)
                ?: throw IllegalArgumentException("Weight reference snapshot is empty")
            val canonicalProfiles = root.getAsJsonArray("profiles").toString().toByteArray(Charsets.UTF_8)
            require(sha256(canonicalProfiles) == document.manifest.numericalDataSha256) {
                "Numerical data checksum mismatch"
            }
            val snapshot = WeightReferenceSnapshot(document.manifest.toModel(), document.profiles.map(ProfileJson::toModel))
            snapshot.validate(breedCatalog, canonicalProfiles)
            return snapshot
        }

        private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(Locale.ROOT, it) }
    }

    private fun validate(breedCatalog: BreedCatalog, canonicalProfiles: ByteArray) {
        require(manifest.schemaVersion == SUPPORTED_SCHEMA_VERSION) { "Unsupported weight reference schema: ${manifest.schemaVersion}" }
        require(manifest.snapshotVersion.isNotBlank() && manifest.snapshotDate.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) { "Invalid snapshot identity" }
        require(manifest.numericalDataSha256.matches(sha256Pattern)) { "Invalid numerical data checksum" }
        require(sha256(canonicalProfiles) == manifest.numericalDataSha256) { "Numerical data checksum mismatch" }
        val sources = manifest.sources.associateBy(ReferenceSource::id)
        require(sources.size == manifest.sources.size && sources.isNotEmpty()) { "Source IDs must be unique" }
        manifest.sources.forEach { source ->
            require(source.id.isNotBlank() && source.citation.isNotBlank()) { "Incomplete source ${source.id}" }
            require(source.dataUrl.startsWith("https://") && source.license.isNotBlank() && source.licenseUrl.startsWith("https://")) { "Invalid provenance for ${source.id}" }
            require(source.upstreamArtifactSha256.matches(sha256Pattern)) { "Invalid upstream checksum for ${source.id}" }
            require(source.derivedArtifactSha256 == null || source.derivedArtifactSha256.matches(sha256Pattern)) { "Invalid derived-artifact checksum for ${source.id}" }
            if (source.id.startsWith("wikipedia-")) {
                require(source.accessedDate?.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) == true) {
                    "Wikipedia source ${source.id} must declare its access date"
                }
            }
            require((source.derivedArtifact == null) == (source.derivedArtifactSha256 == null)) { "Incomplete derived-artifact provenance for ${source.id}" }
        }
        require(manifest.scopes.map(ReferenceScope::id).distinct().size == manifest.scopes.size) { "Scope IDs must be unique" }
        manifest.scopes.forEach { scope -> validateScope(scope, sources, breedCatalog) }
        require(profiles.map(ReferenceProfile::id).distinct().size == profiles.size) { "Profile IDs must be unique" }
        val scopes = manifest.scopes.associateBy(ReferenceScope::id)
        profiles.forEach { profile -> validateProfile(profile, sources, scopes, breedCatalog) }
        val profilesById = profiles.groupBy(ReferenceProfile::id)
        manifest.scopes.forEach { scope ->
            val matching = profilesById[scope.id].orEmpty()
            val expected = if (scope.numericalAvailability == NumericalAvailability.AVAILABLE) 1 else 0
            require(matching.size == expected) {
                "Scope ${scope.id} availability requires $expected matching profile(s), found ${matching.size}"
            }
        }
    }

    private fun validateScope(scope: ReferenceScope, sources: Map<String, ReferenceSource>, breeds: BreedCatalog) {
        if (scope.numericalAvailability == NumericalAvailability.AVAILABLE) {
            require(scope.sourceId != null && scope.sourceId in sources) { "Available scope ${scope.id} requires a known source" }
        } else {
            require(scope.sourceId == null || scope.sourceId in sources) { "Unknown source in scope ${scope.id}" }
        }
        require(scope.minimumAgeDays >= 0 && scope.maximumAgeDays >= scope.minimumAgeDays) { "Invalid age range in scope ${scope.id}" }
        require(scope.constraints.isNotEmpty() && scope.constraints.none(String::isBlank)) { "Scope ${scope.id} requires constraints" }
        when (scope.numericalAvailability) {
            NumericalAvailability.AVAILABLE -> require(scope.unavailabilityReason == null) {
                "Available scope ${scope.id} cannot declare an unavailability reason"
            }
            NumericalAvailability.NOT_REPRODUCIBLE_FROM_PUBLISHED_ARTIFACTS -> require(scope.unavailabilityReason != null) {
                "Unavailable scope ${scope.id} requires a typed reason"
            }
        }
        when (scope.basis) {
            ReferenceBasis.WEIGHT_CATEGORY -> {
                require(scope.species == ReferenceSpecies.DOG && scope.weightCategory != null && scope.breedId == null) { "Invalid weight-category scope ${scope.id}" }
                require(scope.maximumAdultWeightKg != null && scope.maximumAdultWeightKg <= 40.0) { "Dog source does not support adult weight above 40 kg" }
            }
            ReferenceBasis.BREED -> {
                require(scope.weightCategory == null && scope.breedId != null) { "Invalid breed scope ${scope.id}" }
                val breed = breeds.findById(scope.breedId) ?: error("Unknown VBO breed ID ${scope.breedId}")
                require((scope.species == ReferenceSpecies.CAT && breed.species == BreedSpecies.CAT) || (scope.species == ReferenceSpecies.DOG && breed.species == BreedSpecies.DOG)) { "Breed species mismatch in ${scope.id}" }
            }
            ReferenceBasis.POPULATION -> {
                require(scope.species == ReferenceSpecies.CAT && scope.weightCategory == null && scope.breedId == null) { "Invalid population scope ${scope.id}" }
                require(scope.minimumAdultWeightKg == null && scope.maximumAdultWeightKg == null) { "Population scope ${scope.id} cannot impose adult-weight bounds" }
            }
        }
    }

    private fun validateProfile(profile: ReferenceProfile, sources: Map<String, ReferenceSource>, scopes: Map<String, ReferenceScope>, breeds: BreedCatalog) {
        require(profile.sourceId in sources) { "Unknown source in profile ${profile.id}" }
        require(profile.citation == sources.getValue(profile.sourceId).citation && profile.license == sources.getValue(profile.sourceId).license) { "Profile ${profile.id} provenance differs from its source" }
        require(profile.constraints.isNotEmpty()) { "Profile ${profile.id} requires constraints" }
        when (profile.referenceKind) {
            ReferenceKind.EMPIRICAL_OBSERVATION_QUARTILES -> require(profile.minimumBinN > 0) { "Profile ${profile.id} must declare its empirical derivation" }
            ReferenceKind.EMPIRICAL_OBSERVATION_MEAN_SD -> require(profile.minimumBinN > 0) { "Profile ${profile.id} must declare its empirical sample size" }
            ReferenceKind.FITTED_BCCG_PERCENTILES -> require(profile.minimumBinN == 0) { "Profile ${profile.id} fitted reference must not claim a bin minimum" }
            ReferenceKind.MODELLED_BREED_ADULT_RANGE -> require(profile.minimumBinN == 0) { "Profile ${profile.id} model must not claim a sample size" }
        }
        when (profile.referenceKind) {
            ReferenceKind.EMPIRICAL_OBSERVATION_MEAN_SD -> require(
                profile.centerStatistic == ReferenceCenterStatistic.MEAN &&
                    profile.boundsStatistic == ReferenceBoundsStatistic.ONE_STANDARD_DEVIATION &&
                    profile.ageAvailability == ReferenceAgeAvailability.EXACT_OBSERVATIONS,
            ) { "Mean/SD profile ${profile.id} must expose exact mean plus or minus one SD observations" }
            ReferenceKind.EMPIRICAL_OBSERVATION_QUARTILES -> require(
                profile.centerStatistic == ReferenceCenterStatistic.MEDIAN && profile.boundsStatistic == ReferenceBoundsStatistic.QUARTILES,
            ) { "Quartile profile ${profile.id} must expose median and quartiles" }
            ReferenceKind.FITTED_BCCG_PERCENTILES -> require(
                profile.centerStatistic == ReferenceCenterStatistic.MEDIAN && profile.boundsStatistic == ReferenceBoundsStatistic.P9_P91,
            ) { "Fitted profile ${profile.id} must expose P50 and P9/P91" }
            ReferenceKind.MODELLED_BREED_ADULT_RANGE -> require(
                profile.centerStatistic == ReferenceCenterStatistic.MEDIAN && profile.boundsStatistic == ReferenceBoundsStatistic.ADULT_TYPICAL_RANGE,
            ) { "Modelled breed profile ${profile.id} must expose the scaled adult typical range" }
        }
        val scope = scopes[profile.id] ?: error("Profile ${profile.id} has no declared scope")
        require(scope.numericalAvailability == NumericalAvailability.AVAILABLE) { "Profile ${profile.id} scope is not numerically available" }
        require(profile.species == scope.species && profile.sex == scope.sex && profile.basis == scope.basis && profile.weightCategory == scope.weightCategory && profile.breedId == scope.breedId && profile.sourceId == scope.sourceId && profile.constraints == scope.constraints && profile.ageAvailability == scope.ageAvailability) { "Profile ${profile.id} differs from its declared scope" }
        validateScope(scope, sources, breeds)
        require(profile.points.isNotEmpty()) { "Profile ${profile.id} contains no points" }
        var previousAge = -1
        profile.points.forEach { point ->
            require(point.ageDays > previousAge) { "Profile ${profile.id} ages must be strictly increasing" }
            require(point.ageDays in scope.minimumAgeDays..scope.maximumAgeDays) { "Profile ${profile.id} point is outside its declared age range" }
            require(listOf(point.lowerKg, point.medianKg, point.upperKg).all { it.isFinite() && it > 0.0 }) { "Profile ${profile.id} weights must be finite and positive" }
            require(point.lowerKg <= point.medianKg && point.medianKg <= point.upperKg) { "Profile ${profile.id} has unordered bounds" }
            require(point.sourceId == null || point.sourceId in sources) { "Profile ${profile.id} point has unknown source" }
            if (point.empirical) require(point.sourceId != null) { "Empirical point in ${profile.id} requires provenance" }
            previousAge = point.ageDays
        }
    }
}

private data class SnapshotJson(val manifest: ManifestJson, val profiles: List<ProfileJson>)
private data class ManifestJson(val schemaVersion: Int, val snapshotVersion: String, val snapshotDate: String, val numericalDataSha256: String, val sources: List<SourceJson>, val scopes: List<ScopeJson>) {
    fun toModel() = WeightReferenceManifest(schemaVersion, snapshotVersion, snapshotDate, numericalDataSha256, sources.map(SourceJson::toModel), scopes.map(ScopeJson::toModel))
}
private data class SourceJson(val id: String, val citation: String, val publicationDoi: String, val dataDoi: String, val dataUrl: String, val upstreamArtifactSha256: String, val license: String, val licenseUrl: String, val accessedDate: String?, val correctionDoi: String?, val derivedArtifact: String?, val derivedArtifactSha256: String?, val derivationSoftware: String?) {
    fun toModel() = ReferenceSource(id, citation, publicationDoi, dataDoi, dataUrl, upstreamArtifactSha256, license, licenseUrl, accessedDate, correctionDoi, derivedArtifact, derivedArtifactSha256, derivationSoftware)
}
private data class ScopeJson(val id: String, val species: String, val sex: String, val basis: String, val weightCategory: String?, val breedId: String?, val minimumAdultWeightKg: Double?, val maximumAdultWeightKg: Double?, val minimumAgeDays: Int, val maximumAgeDays: Int, val constraints: List<String>, val sourceId: String?, val numericalAvailability: String, val ageAvailability: String?, val unavailabilityReason: String?) {
    fun toModel() = ReferenceScope(id, enumValue(species), enumValue(sex), enumValue(basis), weightCategory, breedId, minimumAdultWeightKg, maximumAdultWeightKg, minimumAgeDays, maximumAgeDays, constraints, sourceId, enumValue(numericalAvailability), ageAvailability?.let(::enumValue) ?: ReferenceAgeAvailability.CARRY_FORWARD, unavailabilityReason?.let(::enumValue))
}
private data class ProfileJson(val id: String, val species: String, val sex: String, val basis: String, val weightCategory: String?, val breedId: String?, val sourceId: String, val citation: String, val license: String, val constraints: List<String>, val referenceKind: String, val minimumBinN: Int, val points: List<PointJson>, val ageAvailability: String?, val centerStatistic: String?, val boundsStatistic: String?) {
    fun toModel(): ReferenceProfile {
        val kind = enumValue<ReferenceKind>(referenceKind)
        return ReferenceProfile(id, enumValue(species), enumValue(sex), enumValue(basis), weightCategory, breedId, sourceId, citation, license, constraints, kind, minimumBinN, points.map(PointJson::toModel), ageAvailability?.let(::enumValue) ?: ReferenceAgeAvailability.CARRY_FORWARD, centerStatistic?.let(::enumValue) ?: ReferenceCenterStatistic.MEDIAN, boundsStatistic?.let(::enumValue) ?: if (kind == ReferenceKind.FITTED_BCCG_PERCENTILES) ReferenceBoundsStatistic.P9_P91 else ReferenceBoundsStatistic.QUARTILES)
    }
}
private data class PointJson(val ageDays: Int, val lowerKg: Double, val medianKg: Double, val upperKg: Double, val sourceId: String?, val empirical: Boolean?) {
    fun toModel() = ReferencePoint(ageDays, lowerKg, medianKg, upperKg, sourceId, empirical ?: false)
}
private inline fun <reified T : Enum<T>> enumValue(value: String): T = enumValueOf(value.uppercase(Locale.ROOT))
