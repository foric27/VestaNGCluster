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

    @Test
    fun `own component launch force stops external target package before start`() {
        val rootStarter = RecordingRootActivityStarter(success = true)
        val launcher = VideoDisplayLauncher(
            preferredLaunchComponent = "${BuildConfig.APPLICATION_ID}/.MediaCoverActivity",
            rootActivityStarter = rootStarter,
        )

        launcher.launchOnDisplay(9)

        assertEquals(2, rootStarter.commands.size)
        assertEquals("am force-stop ${RuntimeConfig.TargetApp.PACKAGE_NAME}", rootStarter.commands[0])
        assertTrue(rootStarter.commands[1].contains("am start"))
        assertTrue(rootStarter.commands[1].contains("--display 9"))
        assertTrue(rootStarter.commands[1].contains("${BuildConfig.APPLICATION_ID}/.MediaCoverActivity"))
    }

    @Test
    fun `own component launch emits only force stop and direct start on success`() {
        val rootStarter = RecordingRootActivityStarter(success = true)
        val launcher = VideoDisplayLauncher(
            preferredLaunchComponent = "${BuildConfig.APPLICATION_ID}/.MediaCoverActivity",
            rootActivityStarter = rootStarter,
        )

        launcher.launchOnDisplay(11)

        assertEquals(2, rootStarter.commands.size)
        assertTrue(rootStarter.commands[0].startsWith("am force-stop "))
        assertEquals(
            "am start --display 11 -n ${BuildConfig.APPLICATION_ID}/.MediaCoverActivity -f 0x14000000",
            rootStarter.commands[1],
        )
    }

    @Test
    fun `own component failure falls back to placeholder after direct start`() {
        val rootStarter = RecordingRootActivityStarter(success = false)
        val launcher = VideoDisplayLauncher(
            preferredLaunchComponent = "${BuildConfig.APPLICATION_ID}/.MediaCoverActivity",
            rootActivityStarter = rootStarter,
        )

        launcher.launchOnDisplay(13)

        assertEquals(3, rootStarter.commands.size)
        assertTrue(rootStarter.commands[0].startsWith("am force-stop "))
        assertEquals(
            "am start --display 13 -n ${BuildConfig.APPLICATION_ID}/.MediaCoverActivity -f 0x14000000",
            rootStarter.commands[1],
        )
        assertEquals(
            "am start --display 13 -n ${BuildConfig.APPLICATION_ID}/.${StreamPlaceholderActivity::class.java.simpleName} -f 0x14000000",
            rootStarter.commands[2],
        )
    }

    @Test
    fun `external launch failure falls back to placeholder`() {
        val rootStarter = RecordingRootActivityStarter(success = false)
        val launcher = VideoDisplayLauncher(
            preferredLaunchComponent = "ru.yandex.yandexnavi/.CustomClusterActivity",
            rootActivityStarter = rootStarter,
        )

        launcher.launchOnDisplay(17)

        assertEquals(2, rootStarter.commands.size)
        assertTrue(rootStarter.commands[0].contains("ru.yandex.yandexnavi/.CustomClusterActivity"))
        assertEquals(
            "am start --display 17 -n ${BuildConfig.APPLICATION_ID}/.${StreamPlaceholderActivity::class.java.simpleName} -f 0x14000000",
            rootStarter.commands[1],
        )
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

    private class RecordingRootActivityStarter(private val success: Boolean) : VideoDisplayLauncher.RootActivityStarter {
        val commands = mutableListOf<String>()

        override fun start(command: String): VideoDisplayLauncher.RootLaunchAttempt {
            commands += command
            return VideoDisplayLauncher.RootLaunchAttempt(
                success = success,
                errorMessage = if (success) null else "fake error",
            )
        }
    }
}
