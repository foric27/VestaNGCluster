package ru.foric27.cluster

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoDisplayLauncherTest {

    @Test
    fun `launch uses root am start command with preferred component`() {
        val rootStarter = FakeRootActivityStarter(success = true)
        val launcher = VideoDisplayLauncher(
            preferredLaunchComponent = "ru.yandex.yandexnavi/.CustomClusterActivity",
            rootActivityStarter = rootStarter,
        )

        launcher.launchOnDisplay(7)

        assertTrue(rootStarter.called)
        val command = requireNotNull(rootStarter.lastCommand)
        assertTrue(command.contains("am start"))
        assertTrue(command.contains("--display 7"))
        assertTrue(command.contains("ru.yandex.yandexnavi/.CustomClusterActivity"))
        assertTrue(command.contains(RuntimeConfig.TargetApp.ACTION_MAIN))
        assertTrue(command.contains(RuntimeConfig.TargetApp.CATEGORY_CLUSTER_NAVIGATION))
    }

    @Test
    fun `launch falls back through commands on root failure`() {
        val rootStarter = FakeRootActivityStarter(success = false)
        val launcher = VideoDisplayLauncher(
            preferredLaunchComponent = "ru.yandex.yandexnavi/.CustomClusterActivity",
            rootActivityStarter = rootStarter,
        )

        launcher.launchOnDisplay(3)

        assertTrue(rootStarter.called)
        val command = requireNotNull(rootStarter.lastCommand)
        assertTrue(command.contains("am start"))
        assertTrue(command.contains("--display 3"))
    }

    @Test
    fun `launch uses default component when preferred is null`() {
        val rootStarter = FakeRootActivityStarter(success = true)
        val launcher = VideoDisplayLauncher(
            preferredLaunchComponent = null,
            rootActivityStarter = rootStarter,
        )

        launcher.launchOnDisplay(42)

        assertTrue(rootStarter.called)
        val command = requireNotNull(rootStarter.lastCommand)
        assertTrue(command.contains(YandexLaunchTarget.COMPONENT_AUTO_CLUSTER))
    }

    @Test
    fun `launch shell command contains proxy component`() {
        val rootStarter = FakeRootActivityStarter(success = true)
        val launcher = VideoDisplayLauncher(
            preferredLaunchComponent = null,
            rootActivityStarter = rootStarter,
        )

        launcher.launchOnDisplay(5)

        val command = requireNotNull(rootStarter.lastCommand)
        assertTrue(command.contains("ru.foric27.cluster/${ClusterLaunchProxyActivity::class.java.name}"))
    }

    private class FakeRootActivityStarter(private val success: Boolean) : VideoDisplayLauncher.RootActivityStarter {
        var called = false
        var lastCommand: String? = null

        override fun start(command: String): VideoDisplayLauncher.RootLaunchAttempt {
            called = true
            lastCommand = command
            return VideoDisplayLauncher.RootLaunchAttempt(
                success = success,
                errorMessage = if (success) null else "fake error",
            )
        }
    }
}
