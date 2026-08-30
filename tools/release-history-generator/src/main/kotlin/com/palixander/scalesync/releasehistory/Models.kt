package com.palixander.scalesync.releasehistory

enum class ReleaseFlavor(val id: String) {
    PERSONAL("personal"),
    HUAWEI_ENTERPRISE("huaweiEnterprise"),
    ;

    companion object {
        fun fromId(id: String): ReleaseFlavor = entries.singleOrNull { it.id == id }
            ?: throw GenerationException("Unknown release flavor '$id'")
    }
}

enum class ReleaseHistoryMode(val id: String) {
    BUILD("build"),
    RELEASE("release"),
    ;

    companion object {
        fun fromId(id: String): ReleaseHistoryMode = entries.singleOrNull { it.id == id }
            ?: throw GenerationException(
                "Unknown release history mode '$id'; expected one of: ${entries.joinToString { it.id }}",
            )
    }
}

data class ReleaseNoteFragment(
    val path: String,
    val issue: Int,
    val userVisible: Boolean,
    val text: String?,
    val reason: String?,
    val flavors: Set<ReleaseFlavor>,
    val suppressReleasedChange: Boolean,
) {
    fun appliesTo(flavor: ReleaseFlavor): Boolean = flavors.isEmpty() || flavor in flavors
}

data class ReleaseChange(
    val issue: Int,
    val text: String,
)

data class GeneratedRelease(
    val version: String,
    val commitSha: String,
    val changes: List<ReleaseChange>,
)

enum class HistoricalBoundaryStatus {
    CONFIRMED,
    UNKNOWN,
}

data class HistoricalBoundaryEvidence(
    val status: HistoricalBoundaryStatus,
    val boundaryCommit: String?,
    val candidateCommit: String?,
    val source: String,
)

data class BootstrapRelease(
    val release: GeneratedRelease,
    val evidence: HistoricalBoundaryEvidence,
)

data class GeneratedHistory(
    val releases: List<GeneratedRelease>,
    val latestChanges: List<ReleaseChange> = emptyList(),
)

data class ReleaseRange(
    val headSha: String,
    val previousTag: ApkTag?,
    val displayName: String,
    val issues: List<Int>,
    val fragments: List<ReleaseNoteFragment>,
)

data class ReleasePreflight(
    val headSha: String,
    val previousTag: ApkTag?,
    val range: ReleaseRange?,
    val currentVersion: String,
    val history: GeneratedHistory,
)

data class ApkTag(
    val name: String,
    val version: String,
    val commitSha: String,
)

class GenerationException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
