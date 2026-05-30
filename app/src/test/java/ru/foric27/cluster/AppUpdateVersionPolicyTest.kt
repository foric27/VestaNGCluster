package ru.foric27.cluster

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateVersionPolicyTest {

    @Test
    fun `stable update requires higher version code`() {
        assertTrue(
            AppUpdateVersionPolicy.isUpdateNewer(
                channel = AppSettings.UpdateChannel.STABLE,
                candidateVersionCode = 4,
                candidateBuildSha = "bbbbbbb",
                currentVersionCode = 3,
                currentBuildSha = "aaaaaaa",
            ),
        )
        assertFalse(
            AppUpdateVersionPolicy.isUpdateNewer(
                channel = AppSettings.UpdateChannel.STABLE,
                candidateVersionCode = 3,
                candidateBuildSha = "bbbbbbb",
                currentVersionCode = 3,
                currentBuildSha = "aaaaaaa",
            ),
        )
    }

    @Test
    fun `rolling update is newer when version code matches but sha differs`() {
        assertTrue(
            AppUpdateVersionPolicy.isUpdateNewer(
                channel = AppSettings.UpdateChannel.ROLLING,
                candidateVersionCode = 3,
                candidateBuildSha = "bbbbbbb",
                currentVersionCode = 3,
                currentBuildSha = "aaaaaaa",
            ),
        )
    }

    @Test
    fun `rolling update is not newer when version code and sha match`() {
        assertFalse(
            AppUpdateVersionPolicy.isUpdateNewer(
                channel = AppSettings.UpdateChannel.ROLLING,
                candidateVersionCode = 3,
                candidateBuildSha = "AAAAAAA",
                currentVersionCode = 3,
                currentBuildSha = "aaaaaaa",
            ),
        )
    }

    @Test
    fun `rolling update is newer when current sha is unknown`() {
        assertTrue(
            AppUpdateVersionPolicy.isUpdateNewer(
                channel = AppSettings.UpdateChannel.ROLLING,
                candidateVersionCode = 3,
                candidateBuildSha = "bbbbbbb",
                currentVersionCode = 3,
                currentBuildSha = "unknown",
            ),
        )
    }

    @Test
    fun `lower version code is never newer`() {
        assertFalse(
            AppUpdateVersionPolicy.isUpdateNewer(
                channel = AppSettings.UpdateChannel.ROLLING,
                candidateVersionCode = 2,
                candidateBuildSha = "bbbbbbb",
                currentVersionCode = 3,
                currentBuildSha = "aaaaaaa",
            ),
        )
    }
}
