package ru.foric27.cluster

import android.app.Activity
import android.app.ActivityOptions
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log

/**
 * Промежуточная activity для запуска cluster-activity Яндекс.Навигатора на нужном display
 * с передачей bundle `android.car.cluster.ClusterActivityState`, содержащего unobscured rect.
 */
class ClusterLaunchProxyActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        launchTargetAndFinish()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        launchTargetAndFinish()
    }

    private fun launchTargetAndFinish() {
        val targetIntent = YandexLaunchTarget.buildTargetIntentFromProxyIntent(intent)
        val targetComponent = YandexLaunchTarget.describeTargetComponentFromProxyIntent(intent)
        if (targetIntent == null) {
            Log.e(TAG, "Proxy: не удалось собрать target intent для cluster-activity")
            AppWarningCenter.publish(getString(R.string.msg_output_app_launch_failed_fmt, targetComponent))
            finish()
            return
        }

        try {
            val displayId = resolveCurrentDisplayId()
            val options = if (Build.VERSION.SDK_INT >= 26 && displayId >= 0) {
                ActivityOptions.makeBasic()
                    .setLaunchDisplayId(displayId)
                    .toBundle()
            } else {
                null
            }
            startActivity(targetIntent, options)
            Log.i(
                TAG,
                "Proxy: cluster-activity запущена, visibleArea=${YandexLaunchTarget.CLUSTER_VISIBLE_AREA_SHORT}, blackBottomMask=encoder, display=$displayId",
            )
        } catch (e: ActivityNotFoundException) {
            val msg = getString(R.string.msg_yandex_navigator_not_found_fmt, targetComponent)
            Log.e(TAG, "Proxy: Яндекс Навигатор не найден: $targetComponent", e)
            AppWarningCenter.publish(msg)
        } catch (t: Throwable) {
            Log.e(TAG, "Proxy: не удалось запустить cluster-activity", t)
            AppWarningCenter.publish(getString(R.string.msg_output_app_launch_failed_fmt, targetComponent))
        } finally {
            finish()
            overridePendingTransition(0, 0)
        }
    }


    private fun resolveCurrentDisplayId(): Int {
        return try {
            when {
                Build.VERSION.SDK_INT >= 30 -> display?.displayId ?: -1
                else -> {
                    @Suppress("DEPRECATION")
                    windowManager.defaultDisplay?.displayId ?: -1
                }
            }
        } catch (_: Throwable) {
            -1
        }
    }

    private companion object {
        private const val TAG = "ClusterLaunchProxy"
    }
}
