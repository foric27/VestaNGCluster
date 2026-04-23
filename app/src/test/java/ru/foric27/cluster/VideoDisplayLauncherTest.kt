package ru.foric27.cluster

import android.content.Context
import android.content.ContextWrapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test

class VideoDisplayLauncherTest {

    private val context = ContextWrapper(null)

    @Test
    fun `launch constructs proxy intent with preferred component`() {
        val intentStarter = FakeIntentStarter()
        val launcher = VideoDisplayLauncher(
            context = context,
            preferredLaunchComponent = "ru.yandex.yandexnavi/.CustomClusterActivity",
            intentStarter = intentStarter,
        )
        val command = YandexLaunchTarget.buildPreferredCommands("ru.yandex.yandexnavi/.CustomClusterActivity").single()

        invokeLaunchViaIntentBestEffort(launcher, 7, command)

        val spec = requireNotNull(intentStarter.startedSpec)
        assertEquals(7, intentStarter.displayId)
        assertEquals(
            "ru.foric27.cluster/${ClusterLaunchProxyActivity::class.java.name}",
            spec.proxyComponent,
        )
        assertEquals("ru.yandex.yandexnavi/.CustomClusterActivity", spec.targetComponent)
        assertEquals(RuntimeConfig.TargetApp.ACTION_MAIN, spec.targetAction)
        assertEquals(RuntimeConfig.TargetApp.CATEGORY_CLUSTER_NAVIGATION, spec.targetCategory)
    }

    @Test
    fun `launch path does not use am start shell commands`() {
        val intentStarter = FakeIntentStarter()
        val launcher = VideoDisplayLauncher(
            context = context,
            preferredLaunchComponent = null,
            intentStarter = intentStarter,
        )
        val command = YandexLaunchTarget.buildPreferredCommands(null).single()

        invokeLaunchViaIntentBestEffort(launcher, 3, command)

        val spec = requireNotNull(intentStarter.startedSpec)

        assertFalse(spec.proxyComponent.contains("am start"))
        assertFalse(spec.targetComponent.contains("am start"))
        assertFalse(spec.targetAction.contains("am start"))
        assertFalse(spec.targetCategory.contains("am start"))
    }

    @Test
    fun `launch forwards requested display id to intent starter`() {
        val intentStarter = FakeIntentStarter()
        val launcher = VideoDisplayLauncher(
            context = context,
            preferredLaunchComponent = null,
            intentStarter = intentStarter,
        )
        val command = YandexLaunchTarget.buildPreferredCommands(null).single()

        invokeLaunchViaIntentBestEffort(launcher, 42, command)

        assertEquals(42, intentStarter.displayId)
        assertNotNull(intentStarter.startedSpec)
    }

    private class FakeIntentStarter : VideoDisplayLauncher.IntentStarter {
        var context: Context? = null
        var startedSpec: VideoDisplayLauncher.ProxyIntentSpec? = null
        var displayId: Int? = null

        override fun start(context: Context, spec: VideoDisplayLauncher.ProxyIntentSpec, displayId: Int) {
            this.context = context
            this.startedSpec = spec
            this.displayId = displayId
        }
    }

    private fun invokeLaunchViaIntentBestEffort(
        launcher: VideoDisplayLauncher,
        displayId: Int,
        command: YandexLaunchTarget.LaunchCommand,
    ) {
        val method = VideoDisplayLauncher::class.java.getDeclaredMethod(
            "launchViaIntentBestEffort",
            Int::class.javaPrimitiveType,
            YandexLaunchTarget.LaunchCommand::class.java,
        ).apply {
            isAccessible = true
        }
        method.invoke(launcher, displayId, command)
    }
}
