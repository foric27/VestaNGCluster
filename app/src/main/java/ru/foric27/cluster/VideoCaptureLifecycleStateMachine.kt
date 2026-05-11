package ru.foric27.cluster

/**
 * Явная модель жизненного цикла пайплайна захвата:
 * VirtualDisplay -> MediaCodec input Surface -> UDP.
 *
 * Модель намеренно остаётся чистой логикой без Android-зависимостей. Она
 * документирует допустимые переходы, а [VideoEncoder] использует её как
 * lightweight-ограждение вокруг существующего implicit flow, не меняя порядок
 * realtime-операций.
 *
 * Ресурсные инварианты:
 * - [VideoCaptureLifecycleState.IDLE]: MediaCodec и Surface освобождены;
 *   persistent VirtualDisplay может жить в процессе, но без привязанной Surface.
 * - [VideoCaptureLifecycleState.PREPARING]: создаются codec thread, MediaCodec,
 *   encoder input Surface и VirtualDisplay.
 * - [VideoCaptureLifecycleState.RUNNING]: VirtualDisplay привязан к Surface,
 *   MediaCodec запущен и получает кадры напрямую с VirtualDisplay.
 * - [VideoCaptureLifecycleState.STOPPING]: новые кадры запрещены; shutdown order
 *   фиксирован как detach/release VirtualDisplay -> stop MediaCodec ->
 *   завершить codec thread -> release input Surface -> release MediaCodec.
 * - [VideoCaptureLifecycleState.ERROR]: предыдущий запуск/рендер завершился
 *   ошибкой; повторный start разрешён recovery-контуром.
 * - [VideoCaptureLifecycleState.RELEASED]: терминальное состояние владельца;
 *   повторный start запрещён.
 */
internal enum class VideoCaptureLifecycleState {
    IDLE,
    PREPARING,
    RUNNING,
    STOPPING,
    RELEASED,
    ERROR,
}

internal enum class VideoCaptureLifecycleEvent {
    START_REQUESTED,
    START_COMPLETED,
    START_FAILED,
    STOP_REQUESTED,
    STOP_COMPLETED,
    RELEASE_COMPLETED,
    ERROR_DETECTED,
}

internal object VideoCaptureLifecycleStateMachine {

    fun transition(
        current: VideoCaptureLifecycleState,
        event: VideoCaptureLifecycleEvent,
    ): VideoCaptureLifecycleState? = when (event) {
        VideoCaptureLifecycleEvent.START_REQUESTED -> when (current) {
            VideoCaptureLifecycleState.IDLE,
            VideoCaptureLifecycleState.ERROR,
            -> VideoCaptureLifecycleState.PREPARING
            VideoCaptureLifecycleState.PREPARING,
            VideoCaptureLifecycleState.RUNNING,
            VideoCaptureLifecycleState.STOPPING,
            VideoCaptureLifecycleState.RELEASED,
            -> null
        }

        VideoCaptureLifecycleEvent.START_COMPLETED -> when (current) {
            VideoCaptureLifecycleState.PREPARING -> VideoCaptureLifecycleState.RUNNING
            else -> null
        }

        VideoCaptureLifecycleEvent.START_FAILED -> when (current) {
            VideoCaptureLifecycleState.PREPARING -> VideoCaptureLifecycleState.ERROR
            else -> null
        }

        VideoCaptureLifecycleEvent.STOP_REQUESTED -> when (current) {
            VideoCaptureLifecycleState.PREPARING,
            VideoCaptureLifecycleState.RUNNING,
            VideoCaptureLifecycleState.ERROR,
            -> VideoCaptureLifecycleState.STOPPING
            VideoCaptureLifecycleState.IDLE,
            VideoCaptureLifecycleState.STOPPING,
            VideoCaptureLifecycleState.RELEASED,
            -> null
        }

        VideoCaptureLifecycleEvent.STOP_COMPLETED -> when (current) {
            VideoCaptureLifecycleState.STOPPING -> VideoCaptureLifecycleState.IDLE
            else -> null
        }

        VideoCaptureLifecycleEvent.RELEASE_COMPLETED -> when (current) {
            VideoCaptureLifecycleState.IDLE,
            VideoCaptureLifecycleState.STOPPING,
            VideoCaptureLifecycleState.ERROR,
            -> VideoCaptureLifecycleState.RELEASED
            VideoCaptureLifecycleState.PREPARING,
            VideoCaptureLifecycleState.RUNNING,
            VideoCaptureLifecycleState.RELEASED,
            -> null
        }

        VideoCaptureLifecycleEvent.ERROR_DETECTED -> when (current) {
            VideoCaptureLifecycleState.PREPARING,
            VideoCaptureLifecycleState.RUNNING,
            VideoCaptureLifecycleState.STOPPING,
            -> VideoCaptureLifecycleState.ERROR
            VideoCaptureLifecycleState.IDLE,
            VideoCaptureLifecycleState.ERROR,
            VideoCaptureLifecycleState.RELEASED,
            -> null
        }
    }

    fun canStart(current: VideoCaptureLifecycleState): Boolean =
        transition(current, VideoCaptureLifecycleEvent.START_REQUESTED) != null
}
