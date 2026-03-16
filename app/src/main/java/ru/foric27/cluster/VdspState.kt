package ru.foric27.cluster

/**
 * Глобальное состояние VirtualDisplay для режима app-only.
 */
object VdspState {

    const val ACTION_VDSP_READY: String = "ru.foric27.cluster.ACTION_VDSP_READY"

    @Volatile private var displayId: Int = -1
    @Volatile private var width: Int = RuntimeConfig.Video.WIDTH
    @Volatile private var height: Int = RuntimeConfig.Video.HEIGHT

    fun set(displayId: Int, width: Int, height: Int) {
        this.displayId = displayId
        this.width = width
        this.height = height
    }

    fun clear() {
        displayId = -1
        width = RuntimeConfig.Video.WIDTH
        height = RuntimeConfig.Video.HEIGHT
    }

    fun getDisplayId(): Int = displayId
}
