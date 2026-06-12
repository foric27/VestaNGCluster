package ru.foric27.cluster.video

/**
 * Контроллер таймингов видеопотока.
 *
 * Гарантирует монотонность presentation timestamps и равномерные интервалы
 * между кадрами. При обнаружении overrun (реальное время ушло далеко вперёд
 * от расписания) выполняет resync к текущему времени, чтобы избежать
 * накопления задержки.
 */
internal class VideoFrameTimingController(
    fps: Int,
) {

    private var frameIntervalNs: Long = (1_000_000_000L / fps.coerceAtLeast(1)).coerceAtLeast(1L)

    private var lastPresentationTimestampNs: Long = 0L

    /**
     * Максимально допустимое отставание реального времени от расписания
     * перед принудительным resync. 2 интервала — достаточно, чтобы
     * пережить краткие задержки render, но не копить drift.
     */
    private val maxOverrunNs: Long
        get() = frameIntervalNs * 2

    /**
     * Санитизирует presentation timestamp, гарантируя монотонность.
     *
     * Если candidate меньше или равен последнему timestamp — возвращает
     * последний + 1 нс. Иначе — candidate.
     *
     * @param candidateTimestampNs предлагаемый timestamp в наносекундах
     * @return санитизированный монотонный timestamp
     */
    fun sanitizePresentationTimestamp(candidateTimestampNs: Long): Long {
        val normalizedCandidate = candidateTimestampNs.coerceAtLeast(1L)
        val sanitizedTimestamp = if (lastPresentationTimestampNs > 0L && normalizedCandidate <= lastPresentationTimestampNs) {
            lastPresentationTimestampNs + 1L
        } else {
            normalizedCandidate
        }
        lastPresentationTimestampNs = sanitizedTimestamp
        return sanitizedTimestamp
    }

    /**
     * Возвращает следующий запланированный presentation timestamp.
     *
     * При первом вызове — текущее время. При overrun (реальное время ушло
     * далеко вперёд от расписания) — выполняет resync к nowNs.
     *
     * @param nowNs текущее время в наносекундах
     * @return санитизированный timestamp для следующего кадра
     */
    fun nextScheduledPresentationTimestampNs(nowNs: Long): Long {
        val scheduledCandidate = if (lastPresentationTimestampNs <= 0L) {
            nowNs.coerceAtLeast(1L)
        } else {
            lastPresentationTimestampNs + frameIntervalNs
        }

        // Overrun policy: если nowNs ушёл далеко вперёд от scheduled,
        // resync к nowNs вместо накопления задержки.
        val overrun = nowNs - scheduledCandidate
        val finalCandidate = if (overrun > maxOverrunNs) {
            nowNs
        } else {
            scheduledCandidate
        }

        return sanitizePresentationTimestamp(finalCandidate)
    }

    /**
     * Явно фиксирует timestamp последнего запланированного кадра.
     *
     * Используется при ручном управлении таймингом вне стандартного цикла.
     *
     * @param timestampNs timestamp в наносекундах
     */
    fun markFrameScheduled(timestampNs: Long) {
        lastPresentationTimestampNs = timestampNs
    }

    /**
     * Обновляет целевой FPS и пересчитывает интервал между кадрами.
     *
     * @param fps новое значение кадров в секунду
     */
    fun setTargetFps(fps: Int) {
        frameIntervalNs = (1_000_000_000L / fps.coerceAtLeast(1)).coerceAtLeast(1L)
    }

    /**
     * Сбрасывает состояние тайминга: last timestamp, интервал.
     *
     * Вызывается при остановке/перезапуске кодера.
     */
    fun reset() {
        lastPresentationTimestampNs = 0L
    }
}