package com.palixander.scalesync.core.reference

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.palixander.scalesync.core.breed.BreedCatalog
import com.palixander.scalesync.core.breed.BreedSpecies
import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale

enum class ReferenceSpecies { CAT, DOG }
enum class ReferenceSex { FEMALE, MALE }
enum class ReferenceBasis { BREED, WEIGHT_CATEGORY }
enum class NumericalAvailability { AVAILABLE, NOT_REPRODUCIBLE_FROM_PUBLISHED_ARTIFACTS }

data class ReferencePoint(val ageDays: Int, val lowerKg: Double, val medianKg: Double, val upperKg: Double)
data class ReferenceSource(
    val id: String,
    val citation: String,
    val publicationDoi: String,
    val dataDoi: String,
    val dataUrl: String,
    val upstreamArtifactSha256: String,
    val license: String,
    val licenseUrl: String,
    val correctionDoi: String?,
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
    val sourceId: String,
    val numericalAvailability: NumericalAvailability,
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
    val referenceKind: String,
    val minimumBinN: Int,
    val points: List<ReferencePoint>,
)
data class WeightReferenceManifest(
    val schemaVersion: Int,
    val snapshotVersion: String,
    val snapshotDate: String,
    val numericalDataSha256: String,
    val sources: List<ReferenceSource>,
    val scopes: List<ReferenceScope>,
)

class WeightReferenceSnapshot private constructor(
    val manifest: WeightReferenceManifest,
    val profiles: List<ReferenceProfile>,
) {
    /** Returns an exact or linearly interpolated point within one profile only. Never extrapolates. */
    fun interpolate(profileId: String, ageDays: Int): ReferencePoint? {
        val profile = profiles.singleOrNull { it.id == profileId } ?: return null
        val points = profile.points
        if (ageDays < points.first().ageDays || ageDays > points.last().ageDays) return null
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
        const val SUPPORTED_SCHEMA_VERSION = 1
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
            require(source.id.isNotBlank() && source.citation.isNotBlank() && source.publicationDoi.isNotBlank() && source.dataDoi.isNotBlank()) { "Incomplete source ${source.id}" }
            require(source.dataUrl.startsWith("https://") && source.license.isNotBlank() && source.licenseUrl.startsWith("https://")) { "Invalid provenance for ${source.id}" }
            require(source.upstreamArtifactSha256.matches(sha256Pattern)) { "Invalid upstream checksum for ${source.id}" }
        }
        require(manifest.scopes.map(ReferenceScope::id).distinct().size == manifest.scopes.size) { "Scope IDs must be unique" }
        manifest.scopes.forEach { scope -> validateScope(scope, sources, breedCatalog) }
        require(profiles.map(ReferenceProfile::id).distinct().size == profiles.size) { "Profile IDs must be unique" }
        val scopes = manifest.scopes.associateBy(ReferenceScope::id)
        profiles.forEach { profile -> validateProfile(profile, sources, scopes, breedCatalog) }
    }

    private fun validateScope(scope: ReferenceScope, sources: Map<String, ReferenceSource>, breeds: BreedCatalog) {
        require(scope.sourceId in sources) { "Unknown source in scope ${scope.id}" }
        require(scope.minimumAgeDays > 0 && scope.maximumAgeDays > scope.minimumAgeDays) { "Invalid age range in scope ${scope.id}" }
        require(scope.constraints.isNotEmpty() && scope.constraints.none(String::isBlank)) { "Scope ${scope.id} requires constraints" }
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
        }
    }

    private fun validateProfile(profile: ReferenceProfile, sources: Map<String, ReferenceSource>, scopes: Map<String, ReferenceScope>, breeds: BreedCatalog) {
        require(profile.sourceId in sources) { "Unknown source in profile ${profile.id}" }
        require(profile.citation == sources.getValue(profile.sourceId).citation && profile.license == sources.getValue(profile.sourceId).license) { "Profile ${profile.id} provenance differs from its source" }
        require(profile.constraints.isNotEmpty()) { "Profile ${profile.id} requires constraints" }
        require(profile.referenceKind == "empirical_observation_quartiles" && profile.minimumBinN > 0) { "Profile ${profile.id} must declare its empirical derivation" }
        val scope = scopes[profile.id] ?: error("Profile ${profile.id} has no declared scope")
        require(scope.numericalAvailability == NumericalAvailability.AVAILABLE) { "Profile ${profile.id} scope is not numerically available" }
        require(profile.species == scope.species && profile.sex == scope.sex && profile.basis == scope.basis && profile.weightCategory == scope.weightCategory && profile.breedId == scope.breedId && profile.sourceId == scope.sourceId && profile.constraints == scope.constraints) { "Profile ${profile.id} differs from its declared scope" }
        validateScope(scope, sources, breeds)
        require(profile.points.isNotEmpty()) { "Profile ${profile.id} contains no points" }
        var previousAge = 0
        profile.points.forEach { point ->
            require(point.ageDays > previousAge) { "Profile ${profile.id} ages must be strictly increasing" }
            require(point.ageDays in scope.minimumAgeDays..scope.maximumAgeDays) { "Profile ${profile.id} point is outside its declared age range" }
            require(listOf(point.lowerKg, point.medianKg, point.upperKg).all { it.isFinite() && it > 0.0 }) { "Profile ${profile.id} weights must be finite and positive" }
            require(point.lowerKg <= point.medianKg && point.medianKg <= point.upperKg) { "Profile ${profile.id} has unordered bounds" }
            previousAge = point.ageDays
        }
    }
}

private data class SnapshotJson(val manifest: ManifestJson, val profiles: List<ProfileJson>)
private data class ManifestJson(val schemaVersion: Int, val snapshotVersion: String, val snapshotDate: String, val numericalDataSha256: String, val sources: List<SourceJson>, val scopes: List<ScopeJson>) {
    fun toModel() = WeightReferenceManifest(schemaVersion, snapshotVersion, snapshotDate, numericalDataSha256, sources.map(SourceJson::toModel), scopes.map(ScopeJson::toModel))
}
private data class SourceJson(val id: String, val citation: String, val publicationDoi: String, val dataDoi: String, val dataUrl: String, val upstreamArtifactSha256: String, val license: String, val licenseUrl: String, val correctionDoi: String?) {
    fun toModel() = ReferenceSource(id, citation, publicationDoi, dataDoi, dataUrl, upstreamArtifactSha256, license, licenseUrl, correctionDoi)
}
private data class ScopeJson(val id: String, val species: String, val sex: String, val basis: String, val weightCategory: String?, val breedId: String?, val minimumAdultWeightKg: Double?, val maximumAdultWeightKg: Double?, val minimumAgeDays: Int, val maximumAgeDays: Int, val constraints: List<String>, val sourceId: String, val numericalAvailability: String) {
    fun toModel() = ReferenceScope(id, enumValue(species), enumValue(sex), enumValue(basis), weightCategory, breedId, minimumAdultWeightKg, maximumAdultWeightKg, minimumAgeDays, maximumAgeDays, constraints, sourceId, enumValue(numericalAvailability))
}
private data class ProfileJson(val id: String, val species: String, val sex: String, val basis: String, val weightCategory: String?, val breedId: String?, val sourceId: String, val citation: String, val license: String, val constraints: List<String>, val referenceKind: String, val minimumBinN: Int, val points: List<PointJson>) {
    fun toModel() = ReferenceProfile(id, enumValue(species), enumValue(sex), enumValue(basis), weightCategory, breedId, sourceId, citation, license, constraints, referenceKind, minimumBinN, points.map(PointJson::toModel))
}
private data class PointJson(val ageDays: Int, val lowerKg: Double, val medianKg: Double, val upperKg: Double) { fun toModel() = ReferencePoint(ageDays, lowerKg, medianKg, upperKg) }
private inline fun <reified T : Enum<T>> enumValue(value: String): T = enumValueOf(value.uppercase(Locale.ROOT))
