package ru.foric27.cluster.video
import ru.foric27.cluster.util.*

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import timber.log.Timber
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

    /**
     * Создаёт или переиспользует VirtualDisplay с заданными параметрами.
     *
     * Если существующий display валиден и имеет те же размеры/DPI — обновляет
     * только входную Surface. При невалидности или изменении параметров — создаёт новый.
     *
     * @param context контекст приложения
     * @param width ширина display
     * @param height высота display
     * @param dpi плотность пикселей
     * @param surface входная Surface для отображения
     * @return созданный или переиспользованный VirtualDisplay
     * @throws IllegalStateException если создание display не удалось
     */
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
            // Проверяем валидность display перед reuse — система могла уничтожить его
            val display = existing.display
            if (display != null && display.isValid) {
                try {
                    existing.setSurface(surface)
                    return existing
                } catch (t: Throwable) {
                    Timber.tag(TAG).w(t, "Не удалось переиспользовать VirtualDisplay, пересоздаю")
                    releaseLocked(clearState = false)
                }
            } else {
                Timber.tag(TAG).w("Existing VirtualDisplay невалиден (display=$display), пересоздаю")
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
        Timber.tag(TAG).i("Создан новый VirtualDisplay ${width}x${height}@${dpi}")
        return created
    }

    /**
     * Отсоединяет Surface от текущего VirtualDisplay без его освобождения.
     *
     * Используется при остановке кодера, чтобы display оставался жив,
     * но не принимал кадры от освобождённого encoder'а.
     */
    @Synchronized
    fun detachSurface() {
        try {
            virtualDisplay?.setSurface(null)
        } catch (t: Throwable) {
            Timber.tag(TAG).w(t, "Не удалось отсоединить Surface от VirtualDisplay")
        }
    }

    /**
     * Полностью освобождает VirtualDisplay и сбрасывает состояние.
     *
     * Вызывается при полной остановке сервиса или при невозможности
     * переиспользовать существующий display.
     */
    @Synchronized
    fun releaseAll() {
        releaseLocked(clearState = true)
    }

    @Synchronized
    private fun releaseLocked(clearState: Boolean) {
        try {
            virtualDisplay?.setSurface(null)
        } catch (t: Throwable) {
            Timber.tag(TAG).w(t, "Не удалось очистить Surface у VirtualDisplay")
        }
        try {
            virtualDisplay?.release()
        } catch (t: Throwable) {
            Timber.tag(TAG).w(t, "Не удалось освободить VirtualDisplay")
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
