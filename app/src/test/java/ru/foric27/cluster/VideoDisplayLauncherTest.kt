package ru.foric27.cluster

import org.junit.Assert.assertEquals
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

        assertEquals(5, rootStarter.commands.size)
        assertOverlayCloseCommands(rootStarter.commands)
        assertBlackScreenCommand(rootStarter.commands[2], 9)
        assertEquals("am force-stop ${RuntimeConfig.TargetApp.PACKAGE_NAME}", rootStarter.commands[3])
        assertTrue(rootStarter.commands[4].contains("am start"))
        assertTrue(rootStarter.commands[4].contains("--display 9"))
        assertTrue(rootStarter.commands[4].contains("${BuildConfig.APPLICATION_ID}/.MediaCoverActivity"))
    }

    @Test
    fun `own component launch emits only force stop and direct start on success`() {
        val rootStarter = RecordingRootActivityStarter(success = true)
        val launcher = VideoDisplayLauncher(
            preferredLaunchComponent = "${BuildConfig.APPLICATION_ID}/.MediaCoverActivity",
            rootActivityStarter = rootStarter,
        )

        launcher.launchOnDisplay(11)

        assertEquals(5, rootStarter.commands.size)
        assertOverlayCloseCommands(rootStarter.commands)
        assertBlackScreenCommand(rootStarter.commands[2], 11)
        assertTrue(rootStarter.commands[3].startsWith("am force-stop "))
        assertEquals(
            "am start --display 11 -n ${BuildConfig.APPLICATION_ID}/.MediaCoverActivity -f 0x14000000",
            rootStarter.commands[4],
        )
    }

    @Test
    fun `own component failure does not launch any fallback`() {
        val rootStarter = RecordingRootActivityStarter(success = false)
        val launcher = VideoDisplayLauncher(
            preferredLaunchComponent = "${BuildConfig.APPLICATION_ID}/.MediaCoverActivity",
            rootActivityStarter = rootStarter,
        )

        launcher.launchOnDisplay(13)

        assertEquals(5, rootStarter.commands.size)
        assertOverlayCloseCommands(rootStarter.commands)
        assertBlackScreenCommand(rootStarter.commands[2], 13)
        assertTrue(rootStarter.commands[3].startsWith("am force-stop "))
        assertEquals(
            "am start --display 13 -n ${BuildConfig.APPLICATION_ID}/.MediaCoverActivity -f 0x14000000",
            rootStarter.commands[4],
        )
    }

    @Test
    fun `external launch failure does not launch any fallback`() {
        val rootStarter = RecordingRootActivityStarter(success = false)
        val launcher = VideoDisplayLauncher(
            preferredLaunchComponent = "ru.yandex.yandexnavi/.CustomClusterActivity",
            rootActivityStarter = rootStarter,
        )

        launcher.launchOnDisplay(17)

        assertTrue(rootStarter.commands.isNotEmpty())
        assertTrue(rootStarter.commands.last().contains("ru.yandex.yandexnavi/.CustomClusterActivity"))
    }

    @Test
    fun `launch always emits black screen before target launch on own component`() {
        val rootStarter = RecordingRootActivityStarter(success = true)
        val launcher = VideoDisplayLauncher(
            preferredLaunchComponent = "${BuildConfig.APPLICATION_ID}/.MediaCoverActivity",
            rootActivityStarter = rootStarter,
        )

        launcher.launchOnDisplay(21)

        val blackScreenIndex = rootStarter.commands.indexOfFirst { it.contains(ClusterBlackScreenActivity::class.java.simpleName) }
        assertTrue("Ожидался запуск ClusterBlackScreenActivity, получили: ${rootStarter.commands}", blackScreenIndex >= 0)
        assertTrue(rootStarter.commands[blackScreenIndex].contains("--display 21"))
    }

    private fun assertOverlayCloseCommands(commands: List<String>) {
        assertEquals(YandexLaunchTarget.buildBroadcastCommand(MediaCoverActivity.ACTION_FINISH_MEDIA_COVER), commands[0])
        assertEquals(
            YandexLaunchTarget.buildBroadcastCommand(ClusterBlackScreenActivity.ACTION_FINISH_CLUSTER_BLACK_SCREEN),
            commands[1],
        )
    }

    private fun assertBlackScreenCommand(command: String, displayId: Int) {
        assertEquals(
            "am start --display $displayId -n ${BuildConfig.APPLICATION_ID}/.${ClusterBlackScreenActivity::class.java.simpleName} -f 0x14000000",
            command,
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
