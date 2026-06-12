package ru.foric27.cluster.update
import ru.foric27.cluster.BuildConfig

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateManagerTest {

    @Test
    fun `currentBuildSha returns non-empty SHA`() {
        val sha = BuildConfig.GIT_SHA
        assertFalse("GIT_SHA should not be empty", sha.isEmpty())
        assertTrue("GIT_SHA should be 7 characters or 'unknown'", sha.length <= 7 || sha == "unknown")
    }

    @Test
    fun `API_BASE_URL points to GitHub API`() {
        val field = AppUpdateManager::class.java.getDeclaredField("API_BASE_URL")
        field.isAccessible = true
        val url = field.get(null) as String
        assertTrue("API_BASE_URL should start with https://api.github.com", url.startsWith("https://api.github.com"))
    }

    @Test
    fun `ROLLING_TAG is main-latest`() {
        val field = AppUpdateManager::class.java.getDeclaredField("ROLLING_TAG")
        field.isAccessible = true
        val tag = field.get(null) as String
        assertTrue("ROLLING_TAG should be main-latest", tag == "main-latest")
    }
}
