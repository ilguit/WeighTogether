package com.example.huaweimisync.releasehistory

enum class ReleaseFlavor(val id: String) {
    PERSONAL("personal"),
    HUAWEI_ENTERPRISE("huaweiEnterprise"),
    ;

    companion object {
        fun fromId(id: String): ReleaseFlavor = entries.singleOrNull { it.id == id }
            ?: throw GenerationException("Unknown release flavor '$id'")
    }
}

data class ReleaseNoteFragment(
    val path: String,
    val issue: Int,
    val userVisible: Boolean,
    val text: String?,
    val reason: String?,
    val flavors: Set<ReleaseFlavor>,
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

data class GeneratedHistory(
    val releases: List<GeneratedRelease>,
)

data class ApkTag(
    val name: String,
    val version: String,
    val commitSha: String,
)

class GenerationException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
