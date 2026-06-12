package ru.foric27.cluster.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppUpdateReleaseParsingTest {

    @Test
    fun `parseBuildShaFromApkName extracts short sha`() {
        assertEquals(
            "3b425ad",
            AppUpdateReleaseParsing.parseBuildShaFromApkName("VestaNGCluster-1.0.2-3b425ad.apk"),
        )
    }

    @Test
    fun `parseBuildShaFromApkName returns null for non matching name`() {
        assertNull(AppUpdateReleaseParsing.parseBuildShaFromApkName("app-release.apk"))
    }

    @Test
    fun `parseBuildShaFromReleaseNotes extracts commit sha`() {
        val body = """
            ## Vesta NG Cluster

            ### Build Info
            - **Commit:** [5321359](https://github.com/foric27/VestaNGCluster/commit/5321359)
        """.trimIndent()
        assertEquals("5321359", AppUpdateReleaseParsing.parseBuildShaFromReleaseNotes(body))
    }

    @Test
    fun `parseBuildShaFromReleaseNotes normalizes sha case`() {
        val body = "- **Commit:** [ABCDEF1](https://example.test/commit/ABCDEF1)"
        assertEquals("abcdef1", AppUpdateReleaseParsing.parseBuildShaFromReleaseNotes(body))
    }

    @Test
    fun `parseBuildShaFromReleaseNotes returns null without commit line`() {
        assertNull(AppUpdateReleaseParsing.parseBuildShaFromReleaseNotes("no commit here"))
    }
}
