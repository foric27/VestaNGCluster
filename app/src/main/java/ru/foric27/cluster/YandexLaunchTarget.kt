package ru.foric27.cluster

import android.content.ComponentName
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle

/**
 * Единственная целевая activity для штатного кластерного вывода Яндекс.Навигатора.
 *
 * В декомпилированном ClusterView visibleArea применяется через bundle
 * `android.car.cluster.ClusterActivityState` -> `android.car:InstrumentClusterService.unobscured.rect`.
 * Здесь этот же bundle формируется и передаётся в целевую activity.
 */
internal object YandexLaunchTarget {

    val ACTION_MAIN: String
        get() = RuntimeConfig.TargetApp.ACTION_MAIN

    val CATEGORY_CLUSTER_NAVIGATION: String
        get() = RuntimeConfig.TargetApp.CATEGORY_CLUSTER_NAVIGATION

    val PACKAGE_AUTO: String
        get() = RuntimeConfig.TargetApp.PACKAGE_NAME

    val COMPONENT_AUTO_CLUSTER: String
        get() = RuntimeConfig.TargetApp.CLUSTER_COMPONENT

    private const val CLUSTER_ACTIVITY_STATE_KEY: String = "android.car.cluster.ClusterActivityState"
    private const val CLUSTER_UNOBSCURED_RECT: String = "android.car:InstrumentClusterService.unobscured.rect"

    internal const val EXTRA_TARGET_COMPONENT: String = "ru.foric27.cluster.extra.TARGET_COMPONENT"
    internal const val EXTRA_TARGET_ACTION: String = "ru.foric27.cluster.extra.TARGET_ACTION"
    internal const val EXTRA_TARGET_CATEGORY: String = "ru.foric27.cluster.extra.TARGET_CATEGORY"

    val CLUSTER_VISIBLE_AREA: Rect
        get() = RuntimeConfig.VisibleArea.rect()

    val CLUSTER_VISIBLE_AREA_SHORT: String
        get() = RuntimeConfig.VisibleArea.SHORT

    data class LaunchCommand(
        val component: String,
        val action: String,
        val category: String,
        val note: String,
    )

    fun buildPreferredCommands(launchComponent: String?): List<LaunchCommand> {
        val resolvedComponent = launchComponent?.trim().takeUnless { it.isNullOrEmpty() }
            ?: COMPONENT_AUTO_CLUSTER

        return listOf(
            LaunchCommand(
                component = resolvedComponent,
                action = ACTION_MAIN,
                category = CATEGORY_CLUSTER_NAVIGATION,
                note = if (resolvedComponent == COMPONENT_AUTO_CLUSTER) {
                    "Штатная cluster-activity Яндекс.Навигатора Auto"
                } else {
                    "Явно заданный cluster-компонент"
                },
            ),
        )
    }

    fun buildProxyAmStartCommand(displayId: Int, command: LaunchCommand): String {
        val proxyComponent = "${BuildConfig.APPLICATION_ID}/${ClusterLaunchProxyActivity::class.java.name}"
        return listOf(
            "am start",
            "--display $displayId",
            "-n $proxyComponent",
            "--es $EXTRA_TARGET_COMPONENT ${shellQuote(command.component)}",
            "--es $EXTRA_TARGET_ACTION ${shellQuote(command.action)}",
            "--es $EXTRA_TARGET_CATEGORY ${shellQuote(command.category)}",
        ).joinToString(separator = " ")
    }

    /**
     * Прямая root-команда `am start --display` для запуска любого компонента
     * без proxy-activity. Используется для собственных activity (MediaCoverActivity),
     * т.к. proxy-activity не имеет прав на запуск на secondary display.
     */
    fun buildDirectAmStartCommand(displayId: Int, component: String): String {
        return listOf(
            "am start",
            "--display $displayId",
            "-n $component",
            "-f 0x14000000",
        ).joinToString(separator = " ")
    }

    /**
     * Команда `am force-stop` для закрытия стороннего приложения (навигатора)
     * перед запуском собственной activity на том же display.
     * Предотвращает наложение UI навигатора поверх MED-контента.
     */
    fun buildForceStopCommand(packageName: String): String {
        return "am force-stop $packageName"
    }

    /**
     * Извлекает имя пакета из полного имени компонента (package/ClassName).
     */
    fun extractPackageName(component: String): String? {
        return component.substringBefore('/').takeIf { it.isNotEmpty() }
    }

    fun buildProxyIntent(command: LaunchCommand): Intent {
        return Intent().apply {
            setComponent(ComponentName(BuildConfig.APPLICATION_ID, ClusterLaunchProxyActivity::class.java.name))
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
            putExtra(EXTRA_TARGET_COMPONENT, command.component)
            putExtra(EXTRA_TARGET_ACTION, command.action)
            putExtra(EXTRA_TARGET_CATEGORY, command.category)
        }
    }

    fun describeTargetComponentFromProxyIntent(proxyIntent: Intent?): String {
        val rawComponent = proxyIntent?.getStringExtra(EXTRA_TARGET_COMPONENT)?.trim().orEmpty()
        return rawComponent.ifEmpty { COMPONENT_AUTO_CLUSTER }
    }

    fun buildTargetIntentFromProxyIntent(proxyIntent: Intent?): Intent? {
        val rawComponent = proxyIntent?.getStringExtra(EXTRA_TARGET_COMPONENT)?.trim().orEmpty()
        if (rawComponent.isEmpty()) {
            return null
        }

        val componentName = parseComponent(rawComponent) ?: return null
        val action = proxyIntent?.getStringExtra(EXTRA_TARGET_ACTION)?.trim().orEmpty()
        val category = proxyIntent?.getStringExtra(EXTRA_TARGET_CATEGORY)?.trim().orEmpty()

        return Intent().apply {
            if (action.isNotEmpty()) setAction(action)
            if (category.isNotEmpty()) addCategory(category)
            setComponent(componentName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
            putClusterVisibleAreaExtras(this)
        }
    }

    private fun putClusterVisibleAreaExtras(intent: Intent) {
        val clusterState = Bundle().apply {
            putParcelable(CLUSTER_UNOBSCURED_RECT, Rect(CLUSTER_VISIBLE_AREA))
        }
        intent.putExtra(CLUSTER_ACTIVITY_STATE_KEY, clusterState)
    }

    private fun parseComponent(raw: String): ComponentName? {
        val pkg = raw.substringBefore('/').trim()
        val cls = raw.substringAfter('/', "").trim()
        if (pkg.isEmpty() || cls.isEmpty()) return null
        val normalizedClassName = when {
            cls.startsWith('.') -> pkg + cls
            '.' in cls -> cls
            else -> "$pkg.$cls"
        }
        return ComponentName(pkg, normalizedClassName)
    }

    private fun shellQuote(value: String): String {
        return "'" + value.replace("'", "'\\''") + "'"
    }

}
