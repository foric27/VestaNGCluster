package ru.foric27.cluster

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.util.Log
import android.view.Surface

/**
 * Держит один и тот же VirtualDisplay в рамках жизни процесса.
 *
 * На повторных стартах стрима display не пересоздаётся, а переиспользуется: меняется
 * только входная Surface энкодера. Это позволяет по возможности сохранять тот же displayId.
 */
object PersistentVirtualDisplay {

    @Volatile
    private var virtualDisplay: VirtualDisplay? = null

    @Volatile
    private var width: Int = 0

    @Volatile
    private var height: Int = 0

    @Volatile
    private var dpi: Int = 0

    @Synchronized
    fun acquire(
        context: Context,
        width: Int,
        height: Int,
        dpi: Int,
        surface: Surface,
    ): VirtualDisplay {
        val existing = virtualDisplay
        if (existing != null && this.width == width && this.height == height && this.dpi == dpi) {
            try {
                existing.setSurface(surface)
                return existing
            } catch (t: Throwable) {
                Log.w(TAG, "Не удалось переиспользовать VirtualDisplay, пересоздаю", t)
                releaseLocked(clearState = false)
            }
        }

        val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION

        val created = displayManager.createVirtualDisplay(
            NAME,
            width,
            height,
            dpi,
            surface,
            flags,
        ) ?: throw IllegalStateException("Не удалось создать VirtualDisplay")

        virtualDisplay = created
        this.width = width
        this.height = height
        this.dpi = dpi
        Log.i(TAG, "Создан новый VirtualDisplay ${width}x${height}@${dpi}")
        return created
    }

    @Synchronized
    fun detachSurface() {
        try {
            virtualDisplay?.setSurface(null)
        } catch (t: Throwable) {
            Log.w(TAG, "Не удалось отсоединить Surface от VirtualDisplay", t)
        }
    }

    @Synchronized
    fun releaseAll() {
        releaseLocked(clearState = true)
    }

    @Synchronized
    private fun releaseLocked(clearState: Boolean) {
        try {
            virtualDisplay?.setSurface(null)
        } catch (t: Throwable) {
            Log.w(TAG, "Не удалось очистить Surface у VirtualDisplay", t)
        }
        try {
            virtualDisplay?.release()
        } catch (t: Throwable) {
            Log.w(TAG, "Не удалось освободить VirtualDisplay", t)
        }
        virtualDisplay = null
        width = 0
        height = 0
        dpi = 0
        if (clearState) {
            VdspState.clear()
        }
    }

    private const val TAG = "PersistentVD"
    private const val NAME = "ClusterVD_APP"
}
