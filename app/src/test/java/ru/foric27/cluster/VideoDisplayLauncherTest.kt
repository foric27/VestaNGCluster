package ru.foric27.cluster

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class VideoDisplayLauncherTest {

    @Before
    fun resetState() {
        VideoDisplayLauncher.clearLaunchState()
    }

    private fun waitForCleanup() {
        // launchBlackScreen выполняется синхронно (foreground), а
        // closeOwnClusterOverlays — в фоновом Thread. Ждём 200мс чтобы
        // фоновые команды cleanup появились в RecordingRootActivityStarter.commands.
        Thread.sleep(200)
    }

    private fun waitUntil(timeoutMs: Long = 2000L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(20)
        }
    }

    @Test
    fun `launch uses root am start command with preferred component`() {
        val rootStarter = RecordingRootActivityStarter(success = true)
        val launcher = VideoDisplayLauncher(
            preferredLaunchComponent = "ru.yandex.yandexnavi/.CustomClusterActivity",
            rootActivityStarter = rootStarter,
        )

        launcher.launchOnDisplay(7)
        waitForCleanup()

        // Ищем основной am start, а не cleanup (cleanup идёт в фоне).
        val mainCommand = requireNotNull(
            rootStarter.commands.firstOrNull {
                it.contains("am start") && it.contains("ru.yandex.yandexnavi/.CustomClusterActivity")
            },
        ) { "Не нашли основной am start для навигатора в командах: ${rootStarter.commands}" }
        assertTrue(mainCommand.contains("am start"))
        assertTrue(mainCommand.contains("--display 7"))
        assertTrue(mainCommand.contains(RuntimeConfig.TargetApp.ACTION_MAIN))
        assertTrue(mainCommand.contains(RuntimeConfig.TargetApp.CATEGORY_CLUSTER_NAVIGATION))
    }

    @Test
    fun `launch falls back through commands on root failure`() {
        val rootStarter = RecordingRootActivityStarter(success = false)
        val launcher = VideoDisplayLauncher(
            preferredLaunchComponent = "ru.yandex.yandexnavi/.CustomClusterActivity",
            rootActivityStarter = rootStarter,
        )

        launcher.launchOnDisplay(3)
        waitForCleanup()

        // С success=false основной am start (proxy) должен быть выполнен.
        // Cleanup-команды идут в фоне, и am start для чёрного экрана
        // присутствует в общем списке. Проверяем что proxy am start
        // для навигатора действительно был запущен.
        val proxyAmStart = rootStarter.commands.firstOrNull {
            it.contains("am start") && it.contains("ru.yandex.yandexnavi/.CustomClusterActivity")
        }
        assertTrue(
            "Ожидался proxy am start для навигатора с display=3 в командах: ${rootStarter.commands}",
            proxyAmStart != null,
        )
        assertTrue(proxyAmStart!!.contains("--display 3"))
    }

    @Test
    fun `launch uses default component when preferred is null`() {
        val rootStarter = RecordingRootActivityStarter(success = true)
        val launcher = VideoDisplayLauncher(
            preferredLaunchComponent = null,
            rootActivityStarter = rootStarter,
        )

        launcher.launchOnDisplay(42)
        // Polling: foreground main launch + blackScreen синхронные,
        // cleanup идёт в фоне. Ждём появления основной am start команды с таймаутом.
        waitUntil { rootStarter.commands.any { it.contains(YandexLaunchTarget.COMPONENT_AUTO_CLUSTER) } }

        assertTrue(
            "Ожидался am start с COMPONENT_AUTO_CLUSTER, получили: ${rootStarter.commands}",
            rootStarter.commands.any { it.contains(YandexLaunchTarget.COMPONENT_AUTO_CLUSTER) },
        )
    }

    @Test
    fun `launch shell command contains proxy component`() {
        val rootStarter = RecordingRootActivityStarter(success = true)
        val launcher = VideoDisplayLauncher(
            preferredLaunchComponent = null,
            rootActivityStarter = rootStarter,
        )

        launcher.launchOnDisplay(5)
        waitForCleanup()

        assertTrue(
            "Ожидался am start с proxy component, получили: ${rootStarter.commands}",
            rootStarter.commands.any {
                it.contains("ru.foric27.cluster/${ClusterLaunchProxyActivity::class.java.name}")
            },
        )
    }

    @Test
    fun `own component launch force stops external target package before start`() {
        val rootStarter = RecordingRootActivityStarter(success = true)
        val launcher = VideoDisplayLauncher(
            preferredLaunchComponent = "${BuildConfig.APPLICATION_ID}/.MediaCoverActivity",
            rootActivityStarter = rootStarter,
        )

        launcher.launchOnDisplay(9)
        waitForCleanup()

        // blackScreen — синхронный (foreground), close×2 — async (background).
        // Главное: force-stop и am start выполнены, плюс close×2 + blackScreen.
        val forceStopIdx = rootStarter.commands.indexOfFirst { it.startsWith("am force-stop ") }
        val mainStartIdx = rootStarter.commands.indexOfFirst {
            it.contains("am start") && it.contains(".MediaCoverActivity")
        }
        assertTrue("force-stop должен быть в командах: ${rootStarter.commands}", forceStopIdx >= 0)
        assertTrue("am start для MediaCoverActivity должен быть: ${rootStarter.commands}", mainStartIdx >= 0)
        assertTrue(
            "force-stop должен быть ДО am start: ${rootStarter.commands}",
            forceStopIdx < mainStartIdx,
        )
        assertTrue(rootStarter.commands[mainStartIdx].contains("--display 9"))
        assertOverlayCloseCommandsPresent(rootStarter.commands)
        assertBlackScreenCommandPresent(rootStarter.commands, 9)
    }

    @Test
    fun `own component launch emits only force stop and direct start on success`() {
        val rootStarter = RecordingRootActivityStarter(success = true)
        val launcher = VideoDisplayLauncher(
            preferredLaunchComponent = "${BuildConfig.APPLICATION_ID}/.MediaCoverActivity",
            rootActivityStarter = rootStarter,
        )

        launcher.launchOnDisplay(11)
        waitForCleanup()

        val mainStartIdx = rootStarter.commands.indexOfFirst {
            it.contains("am start") && it.contains(".MediaCoverActivity")
        }
        assertTrue(mainStartIdx >= 0)
        assertEquals(
            "am start --display 11 -n ${BuildConfig.APPLICATION_ID}/.MediaCoverActivity -f 0x14000000",
            rootStarter.commands[mainStartIdx],
        )
        val forceStopIdx = rootStarter.commands.indexOfFirst { it.startsWith("am force-stop ") }
        assertTrue(forceStopIdx in 0 until mainStartIdx)
        assertOverlayCloseCommandsPresent(rootStarter.commands)
        assertBlackScreenCommandPresent(rootStarter.commands, 11)
    }

    @Test
    fun `own component failure does not launch any fallback`() {
        val rootStarter = RecordingRootActivityStarter(success = false)
        val launcher = VideoDisplayLauncher(
            preferredLaunchComponent = "${BuildConfig.APPLICATION_ID}/.MediaCoverActivity",
            rootActivityStarter = rootStarter,
        )

        launcher.launchOnDisplay(13)
        waitForCleanup()

        val mainStartIdx = rootStarter.commands.indexOfFirst {
            it.contains("am start") && it.contains(".MediaCoverActivity")
        }
        assertTrue(mainStartIdx >= 0)
        assertEquals(
            "am start --display 13 -n ${BuildConfig.APPLICATION_ID}/.MediaCoverActivity -f 0x14000000",
            rootStarter.commands[mainStartIdx],
        )
        val forceStopIdx = rootStarter.commands.indexOfFirst { it.startsWith("am force-stop ") }
        assertTrue(forceStopIdx in 0 until mainStartIdx)
        assertOverlayCloseCommandsPresent(rootStarter.commands)
        assertBlackScreenCommandPresent(rootStarter.commands, 13)
    }

    @Test
    fun `external launch failure does not launch any fallback`() {
        val rootStarter = RecordingRootActivityStarter(success = false)
        val launcher = VideoDisplayLauncher(
            preferredLaunchComponent = "ru.yandex.yandexnavi/.CustomClusterActivity",
            rootActivityStarter = rootStarter,
        )

        launcher.launchOnDisplay(17)
        waitForCleanup()

        assertTrue(rootStarter.commands.isNotEmpty())
        // Главный launch идёт в main thread; cleanup — в фоне. Проверяем что
        // В СПИСКЕ есть команда запуска навигатора, а не "last" (который теперь
        // относится к cleanup).
        assertTrue(
            "Ожидался am start для yandexnavi в командах: ${rootStarter.commands}",
            rootStarter.commands.any { it.contains("ru.yandex.yandexnavi/.CustomClusterActivity") },
        )
    }

    @Test
    fun `launch emits black screen launch on own component`() {
        val rootStarter = RecordingRootActivityStarter(success = true)
        val launcher = VideoDisplayLauncher(
            preferredLaunchComponent = "${BuildConfig.APPLICATION_ID}/.MediaCoverActivity",
            rootActivityStarter = rootStarter,
        )

        launcher.launchOnDisplay(21)
        waitForCleanup()

        val blackScreenCommand = rootStarter.commands.firstOrNull {
            it.contains(ClusterBlackScreenActivity::class.java.simpleName)
        }
        assertTrue("Ожидался запуск ClusterBlackScreenActivity, получили: ${rootStarter.commands}", blackScreenCommand != null)
        assertTrue(blackScreenCommand!!.contains("--display 21"))
    }

    @Test
    fun `normal relaunch within 800ms is throttled`() {
        // Используем поддельные часы: при первом вызове T=1000, при втором T=1200
        // (разница 200мс < 800мс throttle) → второй вызов должен быть отброшен.
        var nowMs = 1_000L
        val clock = { nowMs }
        val rootStarter = RecordingRootActivityStarter(success = true)
        val launcher = VideoDisplayLauncher(
            preferredLaunchComponent = "ru.yandex.yandexnavi/.CustomClusterActivity",
            rootActivityStarter = rootStarter,
            clock = clock,
        )

        launcher.launchOnDisplay(31)
        waitForCleanup()
        val commandsAfterFirst = rootStarter.commands.size

        // Перемещаем часы вперёд на 200мс (в пределах throttle window 800мс)
        nowMs = 1_200L
        launcher.launchOnDisplay(32)
        // Cleanup идёт в фоне, но throttle отбрасывает весь вызов
        // (включая main launch + blackScreen) → commands.size не должен измениться.
        waitForCleanup()
        assertEquals(
            "В пределах 800мс throttle должен отбросить второй вызов, получили: ${rootStarter.commands}",
            commandsAfterFirst,
            rootStarter.commands.size,
        )
    }

    @Test
    fun `normal relaunch after 800ms is not throttled`() {
        var nowMs = 1_000L
        val clock = { nowMs }
        val rootStarter = RecordingRootActivityStarter(success = true)
        val launcher = VideoDisplayLauncher(
            preferredLaunchComponent = "ru.yandex.yandexnavi/.CustomClusterActivity",
            rootActivityStarter = rootStarter,
            clock = clock,
        )

        launcher.launchOnDisplay(31)
        waitForCleanup()
        val commandsAfterFirst = rootStarter.commands.size

        // Перемещаем часы на 1000мс вперёд (> 800мс throttle) → второй вызов должен пройти
        nowMs = 2_000L
        launcher.launchOnDisplay(32)
        waitForCleanup()
        assertTrue(
            "После >800мс throttle второй вызов должен был добавить команду, получили: ${rootStarter.commands}",
            rootStarter.commands.size > commandsAfterFirst,
        )
    }

    private fun assertOverlayCloseCommandsPresent(commands: List<String>) {
        val expectedCloseMediaCover = YandexLaunchTarget.buildBroadcastCommand(MediaCoverActivity.ACTION_FINISH_MEDIA_COVER)
        val expectedCloseBlackScreen = YandexLaunchTarget.buildBroadcastCommand(ClusterBlackScreenActivity.ACTION_FINISH_CLUSTER_BLACK_SCREEN)
        assertTrue("Ожидался broadcast FINISH_MEDIA_COVER: $commands", commands.contains(expectedCloseMediaCover))
        assertTrue("Ожидался broadcast FINISH_CLUSTER_BLACK_SCREEN: $commands", commands.contains(expectedCloseBlackScreen))
    }

    private fun assertBlackScreenCommandPresent(commands: List<String>, displayId: Int) {
        val expected = "am start --display $displayId -n ${BuildConfig.APPLICATION_ID}/.${ClusterBlackScreenActivity::class.java.simpleName} -f 0x14000000"
        assertTrue(
            "Ожидался black screen launch на display=$displayId, получили: $commands",
            commands.contains(expected),
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
        // CopyOnWriteArrayList: thread-safe, и foreground main launch, и
        // background LauncherCleanup могут писать одновременно без CME.
        val commands: MutableList<String> = java.util.concurrent.CopyOnWriteArrayList()

        override fun start(command: String): VideoDisplayLauncher.RootLaunchAttempt {
            commands += command
            return VideoDisplayLauncher.RootLaunchAttempt(
                success = success,
                errorMessage = if (success) null else "fake error",
            )
        }
    }
}
