package ru.foric27.cluster.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class YandexLaunchTargetTest {

    @Test
    fun `proxy am start command targets cluster proxy on requested display`() {
        val command = YandexLaunchTarget.LaunchCommand(
            component = "ru.yandex.yandexnavi/com.yandex.navi.cluster.ClusterNavigationActivity",
            action = "android.intent.action.MAIN",
            category = "android.car.cluster.NAVIGATION",
            note = "test",
        )

        val rootCommand = YandexLaunchTarget.buildProxyAmStartCommand(displayId = 42, command = command)

        assertTrue(rootCommand.startsWith("am start --display 42 -n ru.foric27.cluster/ru.foric27.cluster.ui.ClusterLaunchProxyActivity"))
        assertTrue(rootCommand.contains("--es ru.foric27.cluster.extra.TARGET_COMPONENT 'ru.yandex.yandexnavi/com.yandex.navi.cluster.ClusterNavigationActivity'"))
        assertTrue(rootCommand.contains("--es ru.foric27.cluster.extra.TARGET_ACTION 'android.intent.action.MAIN'"))
        assertTrue(rootCommand.contains("--es ru.foric27.cluster.extra.TARGET_CATEGORY 'android.car.cluster.NAVIGATION'"))
    }

    @Test
    fun `proxy am start command quotes single quotes in extras`() {
        val command = YandexLaunchTarget.LaunchCommand(
            component = "pkg/.Activity'Name",
            action = "android.intent.action.MAIN",
            category = "android.car.cluster.NAVIGATION",
            note = "test",
        )

        val rootCommand = YandexLaunchTarget.buildProxyAmStartCommand(displayId = 7, command = command)

        assertEquals(
            "am start --display 7 -n ru.foric27.cluster/ru.foric27.cluster.ui.ClusterLaunchProxyActivity " +
                "--es ru.foric27.cluster.extra.TARGET_COMPONENT 'pkg/.Activity'\\''Name' " +
                "--es ru.foric27.cluster.extra.TARGET_ACTION 'android.intent.action.MAIN' " +
                "--es ru.foric27.cluster.extra.TARGET_CATEGORY 'android.car.cluster.NAVIGATION'",
            rootCommand,
        )
    }

    @Test
    fun `direct am start command targets requested display and component`() {
        val command = YandexLaunchTarget.buildDirectAmStartCommand(
            displayId = 12,
            component = "ru.foric27.cluster/.MediaCoverActivity",
        )

        assertEquals(
            "am start --display 12 -n ru.foric27.cluster/.MediaCoverActivity -f 0x14000000",
            command,
        )
    }

    @Test
    fun `force stop command uses package name only`() {
        assertEquals(
            "am force-stop ru.yandex.yandexnavi",
            YandexLaunchTarget.buildForceStopCommand("ru.yandex.yandexnavi"),
        )
    }

    @Test
    fun `broadcast command is package scoped for exported dynamic receivers`() {
        assertEquals(
            "am broadcast -a 'ru.foric27.cluster.action.FINISH_MEDIA_COVER' -p ru.foric27.cluster",
            YandexLaunchTarget.buildBroadcastCommand(MediaCoverActivity.ACTION_FINISH_MEDIA_COVER),
        )
    }

    @Test
    fun `extract package name returns segment before slash`() {
        assertEquals(
            "ru.yandex.yandexnavi",
            YandexLaunchTarget.extractPackageName("ru.yandex.yandexnavi/.Main"),
        )
        assertEquals(
            "ru.foric27.cluster",
            YandexLaunchTarget.extractPackageName("ru.foric27.cluster"),
        )
        assertEquals(null, YandexLaunchTarget.extractPackageName(""))
    }

    @Test
    fun `build preferred commands uses explicit launch component when present`() {
        val commands = YandexLaunchTarget.buildPreferredCommands("ru.test/.ClusterActivity")

        assertEquals(1, commands.size)
        assertEquals("ru.test/.ClusterActivity", commands.single().component)
        assertEquals("Явно заданный cluster-компонент", commands.single().note)
    }

    @Test
    fun `build preferred commands falls back to auto component when blank`() {
        val commands = YandexLaunchTarget.buildPreferredCommands("   ")

        assertEquals(1, commands.size)
        assertEquals(YandexLaunchTarget.COMPONENT_AUTO_CLUSTER, commands.single().component)
        assertEquals("Штатная cluster-activity Яндекс.Навигатора Auto", commands.single().note)
    }
}
