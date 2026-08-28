package com.example.huaweimisync.domain

enum class ProfileUpdateDecision {
    SAVE_KEEP_EXISTING,
    ASK_HISTORY_RECALCULATION,
}

fun Account.hasCalculationInputChanges(update: AccountUpdate): Boolean =
    profile.heightCm != update.profile.heightCm ||
        profile.birthDate != update.profile.birthDate ||
        profile.sex != update.profile.sex

class DecideProfileUpdate(
    private val accounts: AccountRepository,
) {
    suspend operator fun invoke(update: AccountUpdate): ProfileUpdateDecision {
        val current = requireNotNull(accounts.getAccount(update.id)) {
            "Account ${update.id} no longer exists"
        }
        if (!current.hasCalculationInputChanges(update)) {
            return ProfileUpdateDecision.SAVE_KEEP_EXISTING
        }
        return if (accounts.hasProfileRecalculationCandidates(update.id)) {
            ProfileUpdateDecision.ASK_HISTORY_RECALCULATION
        } else {
            ProfileUpdateDecision.SAVE_KEEP_EXISTING
        }
    }
}
