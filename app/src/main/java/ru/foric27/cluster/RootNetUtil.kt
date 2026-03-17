package ru.foric27.cluster

import android.os.SystemClock
import android.util.Log
import java.util.Locale

/**
 * Root-настройка сети для cluster-проекта.
 *
 * Логика жёстко ориентирована только на интерфейс из RuntimeConfig.Root.IFACE.
 */
object RootNetUtil {

    private const val TAG = "RootNetUtil"
    private val fixedIface: String
        get() = RuntimeConfig.Root.IFACE

    private val ifaceCacheTtlMs: Long
        get() = RuntimeConfig.Root.IFACE_CACHE_TTL_MS

    private val routeCacheTtlMs: Long
        get() = RuntimeConfig.Root.ROUTE_CACHE_TTL_MS

    @Volatile private var cachedIfaceExists: Boolean? = null
    @Volatile private var cachedIfaceCheckAtMs: Long = 0L
    @Volatile private var cachedRouteDstIp: String? = null
    @Volatile private var cachedRouteExpectedSrcIp: String? = null
    @Volatile private var cachedRouteOk: Boolean? = null
    @Volatile private var cachedRouteCheckAtMs: Long = 0L

    data class ApplyResult(
        val ok: Boolean,
        val iface: String,
        val details: String,
        val rootRequired: Boolean = false,
    )

    data class ProbeState(
        val exists: Boolean,
        val rootRequired: Boolean,
        val details: String,
    )

    @Synchronized
    fun clearCaches() {
        cachedIfaceExists = null
        cachedIfaceCheckAtMs = 0L
        cachedRouteDstIp = null
        cachedRouteExpectedSrcIp = null
        cachedRouteOk = null
        cachedRouteCheckAtMs = 0L
    }

    @Synchronized
    fun applyStaticIfaceNetwork(localCidr: String, gateway: String?): ApplyResult {
        return try {
            val probeState = probeIfaceState(force = true)
            if (probeState.rootRequired) {
                Log.w(TAG, "Root недоступен — настройка $fixedIface пропущена")
                return ApplyResult(
                    ok = false,
                    iface = fixedIface,
                    details = probeState.details,
                    rootRequired = true,
                )
            }

            if (!probeState.exists) {
                val probe = RootShell.su(
                    listOf(
                        "ip -o link show",
                        "ip -o -4 addr show",
                        "ip route show",
                    ),
                    logOnFailure = false,
                )
                val details = buildString {
                    append("iface=").append(fixedIface).append('\n')
                    append(RuntimeConfig.Root.IFACE_EXISTS_LABEL).append("=false\n")
                    append("[STDOUT]\n").append(probe.out)
                    append("\n[STDERR]\n").append(probe.err)
                }
                Log.i(TAG, "$fixedIface отсутствует — статическая настройка сети пропущена")
                return ApplyResult(false, fixedIface, details)
            }

            val cidr = parseIpv4Cidr(localCidr)
                ?: return ApplyResult(false, fixedIface, "Некорректный localCidr: $localCidr")
            val gatewayIp = gateway?.trim()?.takeIf { it.isNotEmpty() }
            if (gatewayIp != null && !isValidIpv4(gatewayIp)) {
                return ApplyResult(false, fixedIface, "Некорректный gateway: $gatewayIp")
            }

            val commands = buildList {
                add("ip link set $fixedIface up")
                add("ip addr replace ${cidr.ip}/${cidr.prefix} dev $fixedIface")
                add("ip route replace ${cidr.network}/${cidr.prefix} dev $fixedIface scope link src ${cidr.ip}")
                gatewayIp?.let {
                    add("ip route replace $it/32 dev $fixedIface scope link src ${cidr.ip}")
                    add("ip route get $it")
                }
                add("ip -o -4 addr show dev $fixedIface")
                add("ip route show dev $fixedIface")
            }

            val result = RootShell.su(commands)
            if (result.isRootDeniedOrMissing()) {
                val details = buildString {
                    append("iface=").append(fixedIface).append('\n')
                    append("root_required=true\n")
                    append("[STDOUT]\n").append(result.out)
                    append("\n[STDERR]\n").append(result.err)
                }
                Log.w(TAG, "Root недоступен — применение статического IP для $fixedIface пропущено")
                return ApplyResult(false, fixedIface, details, rootRequired = true)
            }

            val outLower = result.out.lowercase(Locale.US)
            val ok = result.ok() &&
                result.out.contains(" ${cidr.ip}/") &&
                (
                    gatewayIp == null ||
                        outLower.contains("$gatewayIp dev $fixedIface") ||
                        outLower.contains("$gatewayIp scope link") ||
                        outLower.contains("$gatewayIp/32 dev $fixedIface")
                    )
            val details = buildString {
                append("iface=").append(fixedIface).append('\n')
                append(RuntimeConfig.Root.IFACE_EXISTS_LABEL).append("=true\n")
                append("local=").append(cidr.ip).append('/').append(cidr.prefix).append('\n')
                if (gatewayIp != null) {
                    append("gateway=").append(gatewayIp).append('\n')
                }
                append("[STDOUT]\n").append(result.out)
                append("\n[STDERR]\n").append(result.err)
            }

            if (ok) {
                Log.i(TAG, "Статический IP применён: ${cidr.ip}/${cidr.prefix} dev=$fixedIface")
            } else {
                Log.w(TAG, "Не удалось применить статический IP\n$details")
            }
            ApplyResult(ok, fixedIface, details)
        } catch (t: Throwable) {
            Log.e(TAG, "Ошибка настройки $fixedIface через root", t)
            ApplyResult(false, fixedIface, t.toString())
        }
    }

    fun getIfaceProbeState(force: Boolean = false): ProbeState = probeIfaceState(force = force)

    fun isIfacePresent(force: Boolean = false): Boolean = getIfaceProbeState(force = force).exists

    fun canRouteTo(dstIp: String, expectedSrcIp: String? = null, forceProbe: Boolean = false): Boolean {
        val ip = dstIp.trim()
        if (!isValidIpv4(ip)) return false

        val normalizedSrcIp = expectedSrcIp?.trim()?.takeIf { it.isNotEmpty() }?.lowercase(Locale.US)
        val now = SystemClock.elapsedRealtime()
        if (!forceProbe && cachedRouteOk != null && cachedRouteDstIp == ip && cachedRouteExpectedSrcIp == normalizedSrcIp && (now - cachedRouteCheckAtMs) < routeCacheTtlMs) {
            return cachedRouteOk == true
        }

        val probeState = probeIfaceState(force = forceProbe)
        if (probeState.rootRequired || !probeState.exists) {
            updateRouteCache(ip, normalizedSrcIp, ok = false, checkedAtMs = now)
            return false
        }

        val result = RootShell.su(listOf("ip route get $ip"), logOnFailure = false)
        if (!result.ok()) {
            updateRouteCache(ip, normalizedSrcIp, ok = false, checkedAtMs = now)
            return false
        }

        val output = result.out.lowercase(Locale.US)
        val devRegex = Regex("""\bdev\s+${Regex.escape(fixedIface)}\b""")
        val devOk = devRegex.containsMatchIn(output)
        val srcRegex = normalizedSrcIp?.let { Regex("""\bsrc\s+${Regex.escape(it)}\b""") }
        val ok = devOk && (srcRegex == null || srcRegex.containsMatchIn(output))
        updateRouteCache(ip, normalizedSrcIp, ok = ok, checkedAtMs = now)
        return ok
    }

    private fun probeIfaceState(force: Boolean): ProbeState {
        val now = SystemClock.elapsedRealtime()
        val cached = cachedIfaceExists
        if (!force && cached != null && (now - cachedIfaceCheckAtMs) < ifaceCacheTtlMs) {
            return ProbeState(
                exists = cached,
                rootRequired = false,
                details = "iface=$fixedIface\n${RuntimeConfig.Root.IFACE_EXISTS_LABEL}=$cached",
            )
        }

        val result = RootShell.su(listOf("ip link show dev $fixedIface"), logOnFailure = false)
        if (result.isRootDeniedOrMissing()) {
            return ProbeState(
                exists = false,
                rootRequired = true,
                details = buildString {
                    append("iface=").append(fixedIface).append('\n')
                    append("root_required=true\n")
                    append("[STDOUT]\n").append(result.out)
                    append("\n[STDERR]\n").append(result.err)
                },
            )
        }

        val ok = result.ok() && result.out.lowercase(Locale.US).contains("$fixedIface:")
        cachedIfaceExists = ok
        cachedIfaceCheckAtMs = now
        return ProbeState(
            exists = ok,
            rootRequired = false,
            details = buildString {
                append("iface=").append(fixedIface).append('\n')
                append(RuntimeConfig.Root.IFACE_EXISTS_LABEL).append("=").append(ok).append('\n')
                append("[STDOUT]\n").append(result.out)
                append("\n[STDERR]\n").append(result.err)
            },
        )
    }

    private fun parseIpv4Cidr(value: String): Ipv4Cidr? {
        val raw = value.trim()
        if (raw.isEmpty()) return null
        val ip = raw.substringBefore('/').trim()
        if (!isValidIpv4(ip)) return null
        val prefix = raw.substringAfter('/', "24").trim().toIntOrNull() ?: return null
        if (prefix !in 0..32) return null
        return Ipv4Cidr(ip = ip, prefix = prefix, network = calculateNetworkAddress(ip, prefix))
    }

    private fun isValidIpv4(value: String): Boolean {
        val parts = value.split('.')
        if (parts.size != 4) return false
        return parts.all { part ->
            val number = part.toIntOrNull() ?: return@all false
            number in 0..255 && (part == "0" || !part.startsWith('0') || part.length == 1)
        }
    }

    private fun calculateNetworkAddress(ip: String, prefix: Int): String {
        val bytes = ip.split('.').map { it.toInt() }
        val address =
            (bytes[0] shl 24) or
                (bytes[1] shl 16) or
                (bytes[2] shl 8) or
                bytes[3]
        val mask = if (prefix == 0) 0 else (-1 shl (32 - prefix))
        val network = address and mask
        return listOf(
            (network ushr 24) and 0xFF,
            (network ushr 16) and 0xFF,
            (network ushr 8) and 0xFF,
            network and 0xFF,
        ).joinToString(".")
    }

    private fun updateRouteCache(dstIp: String, expectedSrcIp: String?, ok: Boolean, checkedAtMs: Long) {
        cachedRouteDstIp = dstIp
        cachedRouteExpectedSrcIp = expectedSrcIp
        cachedRouteOk = ok
        cachedRouteCheckAtMs = checkedAtMs
    }

    private data class Ipv4Cidr(
        val ip: String,
        val prefix: Int,
        val network: String,
    )
}
