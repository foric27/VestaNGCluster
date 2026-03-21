package ru.foric27.cluster

internal class VideoFrameTimingController(
    fps: Int,
    private val keepalivePeriodMs: Long,
) {

    private val minRenderIntervalMs: Long = (1000L / fps.coerceAtLeast(1)).coerceAtLeast(1L)

    private var lastRenderAtMs: Long = 0L
    private var lastPresentationTimestampNs: Long = 0L

    fun delayUntilNextDynamicRender(nowMs: Long): Long {
        if (lastRenderAtMs <= 0L) return 0L
        return (minRenderIntervalMs - (nowMs - lastRenderAtMs)).coerceAtLeast(0L)
    }

    fun shouldEmitKeepalive(nowMs: Long): Boolean {
        return lastRenderAtMs > 0L && nowMs - lastRenderAtMs >= keepalivePeriodMs
    }

    fun idleDurationMs(nowMs: Long): Long {
        if (lastRenderAtMs <= 0L) return 0L
        return (nowMs - lastRenderAtMs).coerceAtLeast(0L)
    }

    fun markRendered(nowMs: Long) {
        lastRenderAtMs = nowMs
    }

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

    fun reset() {
        lastRenderAtMs = 0L
        lastPresentationTimestampNs = 0L
    }
}
