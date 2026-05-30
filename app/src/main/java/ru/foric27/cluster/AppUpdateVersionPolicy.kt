package ru.foric27.cluster

import java.util.Locale

internal object AppUpdateVersionPolicy {

    fun isUpdateNewer(
        channel: AppSettings.UpdateChannel,
        candidateVersionCode: Long,
        candidateBuildSha: String?,
        currentVersionCode: Long,
        currentBuildSha: String?,
    ): Boolean {
        if (candidateVersionCode > currentVersionCode) return true
        if (candidateVersionCode < currentVersionCode) return false
        if (channel != AppSettings.UpdateChannel.ROLLING) return false
        val normalizedCandidateSha = normalizeBuildSha(candidateBuildSha)
        val normalizedCurrentSha = normalizeBuildSha(currentBuildSha)
        return when {
            normalizedCandidateSha == null -> false
            normalizedCurrentSha == null -> true
            normalizedCandidateSha != normalizedCurrentSha -> true
            else -> false
        }
    }

    fun normalizeBuildSha(value: String?): String? {
        val normalized = value?.trim()?.lowercase(Locale.US)
        return normalized?.takeIf { it.isNotEmpty() && it != "unknown" }
    }
}
