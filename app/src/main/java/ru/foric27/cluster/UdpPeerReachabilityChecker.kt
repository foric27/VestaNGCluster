package ru.foric27.cluster

import android.os.SystemClock
import java.util.Locale

internal class UdpPeerReachabilityChecker(
    private val ensurePingToolAvailable: () -> Boolean,
    private val executePing: (command: String, timeoutMs: Long) -> RootShell.Result,
    private val pingTimeoutMsProvider: () -> Long,
    private val pingCacheTtlMsProvider: () -> Long,
) {

    @Volatile private var lastPeerPingHost: String? = null
    @Volatile private var lastPeerPingAtMs: Long = 0L
    @Volatile private var lastPeerPingAttempted: Boolean = false
    @Volatile private var lastPeerPingOk: Boolean = false

    fun evaluate(targetHost: String, force: Boolean): PeerCheckResult {
        val hostValue = targetHost.trim()
        if (hostValue.isEmpty()) {
            return PeerCheckResult(attempted = false, ok = false)
        }
        if (!ensurePingToolAvailable()) {
            return PeerCheckResult(attempted = false, ok = false)
        }

        val nowMs = SystemClock.elapsedRealtime()
        if (!force && lastPeerPingHost == hostValue && (nowMs - lastPeerPingAtMs) < pingCacheTtlMsProvider()) {
            return PeerCheckResult(attempted = lastPeerPingAttempted, ok = lastPeerPingOk)
        }

        val result = executePing(buildPingCommand(hostValue), pingTimeoutMsProvider())
        val attempted = !result.isRootDeniedOrMissing() && !isPingCommandUnavailable(result)
        val peerCheck = PeerCheckResult(
            attempted = attempted,
            ok = attempted && result.ok(),
        )
        cache(hostValue, nowMs, peerCheck)
        return peerCheck
    }

    fun resetCache() {
        lastPeerPingHost = null
        lastPeerPingAtMs = 0L
        lastPeerPingAttempted = false
        lastPeerPingOk = false
    }

    private fun buildPingCommand(hostValue: String): String {
        return "if command -v ping >/dev/null 2>&1; then ping -c 1 -W 1 $hostValue; elif [ -x /system/bin/ping ]; then /system/bin/ping -c 1 -W 1 $hostValue; else exit 127; fi"
    }

    private fun isPingCommandUnavailable(result: RootShell.Result): Boolean {
        if (result.code == 127) return true
        val text = result.combinedText().lowercase(Locale.US)
        return text.contains("ping: not found") ||
            text.contains("/system/bin/ping: not found") ||
            (text.contains("not found") && text.contains("ping"))
    }

    private fun cache(hostValue: String, nowMs: Long, peerCheck: PeerCheckResult) {
        lastPeerPingHost = hostValue
        lastPeerPingAtMs = nowMs
        lastPeerPingAttempted = peerCheck.attempted
        lastPeerPingOk = peerCheck.ok
    }
}
