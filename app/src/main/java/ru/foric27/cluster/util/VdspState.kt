package ru.foric27.cluster.util
import ru.foric27.cluster.config.*

/**
 * Глобальное состояние VirtualDisplay для режима app-only.
 */
internal object VdspState {

    const val ACTION_VDSP_READY: String = "ru.foric27.cluster.ACTION_VDSP_READY"
    const val ACTION_VDSP_STATE_CHANGED: String = "ru.foric27.cluster.ACTION_VDSP_STATE_CHANGED"
    const val EXTRA_DISPLAY_ID: String = "displayId"
    const val EXTRA_WIDTH: String = "width"
    const val EXTRA_HEIGHT: String = "height"
    const val EXTRA_STATE: String = "state"

    enum class DisplayState(val wireValue: String) {
        UNKNOWN("unknown"),
        ADDED("added"),
        CHANGED("changed"),
        REMOVED("removed"),
    }

    @Volatile private var displayId: Int = -1
    @Volatile private var width: Int = RuntimeConfig.Video.WIDTH
    @Volatile private var height: Int = RuntimeConfig.Video.HEIGHT
    @Volatile private var state: DisplayState = DisplayState.UNKNOWN

    fun set(displayId: Int, width: Int, height: Int): DisplayState {
        val previousDisplayId = this.displayId
        val previousWidth = this.width
        val previousHeight = this.height
        this.displayId = displayId
        this.width = width
        this.height = height
        state = when {
            previousDisplayId < 0 -> DisplayState.ADDED
            previousDisplayId != displayId || previousWidth != width || previousHeight != height -> DisplayState.CHANGED
            else -> DisplayState.ADDED
        }
        return state
    }

    fun clear() {
        displayId = -1
        width = RuntimeConfig.Video.WIDTH
        height = RuntimeConfig.Video.HEIGHT
        state = DisplayState.REMOVED
    }

    fun getDisplayId(): Int = displayId
    fun getDisplayState(): DisplayState = state
}
