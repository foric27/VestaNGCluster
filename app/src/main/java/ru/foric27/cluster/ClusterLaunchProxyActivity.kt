package ru.foric27.cluster

import android.app.Activity
import android.app.ActivityOptions
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import android.os.Bundle
import timber.log.Timber

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
            Timber.tag(TAG).e("Proxy: не удалось собрать target intent для cluster-activity")
            AppWarningCenter.publish(getString(R.string.msg_output_app_launch_failed_fmt, targetComponent))
            finish()
            return
        }

        try {
            val displayId = resolveCurrentDisplayId()
            val options = if (displayId >= 0) {
                ActivityOptions.makeBasic()
                    .setLaunchDisplayId(displayId)
                    .toBundle()
            } else {
                null
            }
            startActivity(targetIntent, options)
            Timber.tag(TAG).i("Proxy: cluster-activity запущена, visibleArea=${YandexLaunchTarget.CLUSTER_VISIBLE_AREA_SHORT}, blackBottomMask=encoder, display=$displayId",
            )
        } catch (e: ActivityNotFoundException) {
            val msg = getString(R.string.msg_target_app_not_found_fmt, targetComponent)
            Timber.tag(TAG).e(e, "Proxy: целевое приложение не найдено: $targetComponent")
            AppWarningCenter.publish(msg)
        } catch (t: Throwable) {
            Timber.tag(TAG).e(t, "Proxy: не удалось запустить cluster-activity")
            AppWarningCenter.publish(getString(R.string.msg_output_app_launch_failed_fmt, targetComponent))
        } finally {
            finish()
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }


    private fun resolveCurrentDisplayId(): Int {
        val persistentDisplayId = VdspState.getDisplayId()
        if (persistentDisplayId >= 0) {
            return persistentDisplayId
        }

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
