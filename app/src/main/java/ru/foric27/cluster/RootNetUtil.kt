package ru.foric27.cluster

import android.os.SystemClock
import timber.log.Timber
import java.util.Locale

/**
 * Низкоуровневая root-настройка сети для cluster-пайплайна.
 *
 * Утилита работает только с явно заданным интерфейсом из runtime-конфига,
 * применяет статический IP и кеширует дорогие root-проверки, чтобы сервис не
 * спамил su без необходимости.
 */
internal object RootNetUtil {

    private const val TAG = "RootNetUtil"
    private const val ROOT_REQUIRED_MESSAGE = "Нет privileged backend с root-правами"
    private val IFACE_NAME_REGEX = Regex("^[a-zA-Z0-9._:-]+$")

    private val ifaceCacheTtlMs: Long
        get() = RuntimeConfig.Root.IFACE_CACHE_TTL_MS

    private val routeCacheTtlMs: Long
        get() = RuntimeConfig.Root.ROUTE_CACHE_TTL_MS

    @Volatile private var cachedIfaceName: String? = null
    @Volatile private var cachedIfaceExists: Boolean? = null
    @Volatile private var cachedIfaceCheckAtMs: Long = 0L

    /**
     * Флаг: выбранный root-интерфейс хотя бы раз присутствовал на устройстве.
     * Используется для отличия "пропал" от "никогда не существовал".
     */
    @Volatile var wasSelectedIfaceEverPresent: Boolean = false
        private set

    @Volatile private var cachedRouteIface: String? = null
    @Volatile private var cachedRouteDstIp: String? = null
    @Volatile private var cachedRouteExpectedSrcIp: String? = null
    @Volatile private var cachedRouteOk: Boolean? = null
    @Volatile private var cachedRouteCheckAtMs: Long = 0L
    @Volatile private var networkRootShell: NetworkRootShell? = null

    data class ApplyResult(
        val ok: Boolean,
        val iface: String,
        val details: String,
        val rootRequired: Boolean = false,
    )

    data class ProbeState(
        val iface: String,
        val exists: Boolean,
        val linkUp: Boolean,
        val rootRequired: Boolean,
        val details: String,
    )

    data class RouteCheckResult(
        val iface: String,
        val dstIp: String,
        val expectedSrcIp: String?,
        val rootRequired: Boolean,
        val routeCommandOk: Boolean,
        val devOk: Boolean,
        val srcOk: Boolean,
        val ok: Boolean,
        val output: String,
    ) {
        fun summary(): String {
            return if (ok) {
                "маршрут через $iface подтверждён, dst=$dstIp" +
                    (expectedSrcIp?.let { ", src=$it" } ?: "")
            } else {
                "маршрут не совпал, dst=$dstIp, ожидаемый интерфейс=$iface" +
                    (expectedSrcIp?.let { ", ожидаемый src=$it" } ?: "")
            }
        }
    }

    @Synchronized
    fun clearCaches() {
        cachedIfaceName = null
        cachedIfaceExists = null
        cachedIfaceCheckAtMs = 0L
        cachedRouteIface = null
        cachedRouteDstIp = null
        cachedRouteExpectedSrcIp = null
        cachedRouteOk = null
        cachedRouteCheckAtMs = 0L
    }

    fun attachNetworkRootShell(shell: NetworkRootShell) {
        networkRootShell = shell
    }

    @Synchronized
    fun applyStaticIfaceNetwork(localCidr: String, gateway: String?): ApplyResult {
        return try {
            val probeState = probeIfaceState(force = true)
            if (probeState.rootRequired) {
                Timber.tag(TAG).w("Root недоступен — настройка ${probeState.iface} пропущена")
                return ApplyResult(
                    ok = false,
                    iface = probeState.iface,
                    details = probeState.details,
                    rootRequired = true,
                )
            }

            if (!probeState.exists) {
                val probe = runNetworkScript(
                    listOf(
                        "ip -o link show",
                        "ip -o -4 addr show",
                        "ip route show",
                    ),
                )
                val details = buildString {
                    append("iface=").append(probeState.iface).append('\n')
                    append(buildIfaceExistsLabel(probeState.iface)).append("=false\n")
                    append(probe.renderOutput())
                }
                Timber.tag(TAG).i("${probeState.iface} отсутствует — статическая настройка сети пропущена")
                return ApplyResult(false, probeState.iface, details)
            }

            val cidr = parseIpv4Cidr(localCidr)
                ?: return ApplyResult(false, probeState.iface, "Некорректный localCidr: $localCidr")
            val iface = sanitizeIfaceName(probeState.iface)
                ?: return ApplyResult(false, probeState.iface, "Некорректный iface: ${probeState.iface}")
            val gatewayIp = gateway?.trim()?.takeIf { it.isNotEmpty() }
            if (gatewayIp != null && !isValidIpv4(gatewayIp)) {
                return ApplyResult(false, probeState.iface, "Некорректный gateway: $gatewayIp")
            }

            val interfaceResult = runNetworkScript(buildInterfaceSetupBatch(iface, cidr))
            if (!interfaceResult.ok) {
                return ApplyResult(
                    ok = false,
                    iface = iface,
                    details = buildString {
                        append("iface=").append(iface).append('\n')
                        append(buildIfaceExistsLabel(iface)).append("=true\n")
                        append("local=").append(cidr.ip).append('/').append(cidr.prefix).append('\n')
                        append(interfaceResult.renderOutput())
                    },
                    rootRequired = interfaceResult.rootRequired,
                )
            }

            var routingResult = NetworkScriptResult.success("")
            var iptablesResult = NetworkScriptResult.success("")
            if (gatewayIp != null) {
                val iptablesAvailable = canUseIptables()
                routingResult = runNetworkScript(buildRoutingBatch(iface, cidr, gatewayIp, includeFwmarkRule = iptablesAvailable))
                if (!routingResult.ok) {
                    return ApplyResult(
                        ok = false,
                        iface = iface,
                        details = buildString {
                            append("iface=").append(iface).append('\n')
                            append(buildIfaceExistsLabel(iface)).append("=true\n")
                            append("local=").append(cidr.ip).append('/').append(cidr.prefix).append('\n')
                            append("gateway=").append(gatewayIp).append('\n')
                            append(interfaceResult.renderOutput())
                            append('\n')
                            append(routingResult.renderOutput())
                        },
                        rootRequired = routingResult.rootRequired,
                    )
                }
                if (iptablesAvailable) {
                    iptablesResult = runNetworkScript(buildIptablesBatch(gatewayIp))
                    if (!iptablesResult.ok) {
                        return ApplyResult(
                            ok = false,
                            iface = iface,
                            details = buildString {
                                append("iface=").append(iface).append('\n')
                                append(buildIfaceExistsLabel(iface)).append("=true\n")
                                append("local=").append(cidr.ip).append('/').append(cidr.prefix).append('\n')
                                append("gateway=").append(gatewayIp).append('\n')
                                append(interfaceResult.renderOutput())
                                append('\n')
                                append(routingResult.renderOutput())
                                append('\n')
                                append(iptablesResult.renderOutput())
                            },
                            rootRequired = iptablesResult.rootRequired,
                        )
                    }
                }
            }

            clearCaches()
            val routeCheck = gatewayIp?.let { checkRouteTo(it, cidr.ip, forceProbe = true) }
            val combinedOutput = buildString {
                append(interfaceResult.output)
                if (routingResult.output.isNotBlank()) {
                    if (isNotEmpty()) append('\n')
                    append(routingResult.output)
                }
                if (iptablesResult.output.isNotBlank()) {
                    if (isNotEmpty()) append('\n')
                    append(iptablesResult.output)
                }
            }
            val outLower = combinedOutput.lowercase(Locale.US)
            val ok = interfaceResult.ok && routingResult.ok && iptablesResult.ok &&
                combinedOutput.contains(" ${cidr.ip}/") &&
                (
                    gatewayIp == null ||
                        routeCheck?.ok == true ||
                        outLower.contains("$gatewayIp dev $iface") ||
                        outLower.contains("$gatewayIp scope link") ||
                        outLower.contains("$gatewayIp/32 dev $iface")
                    )
            val details = buildString {
                append("iface=").append(iface).append('\n')
                append(buildIfaceExistsLabel(iface)).append("=true\n")
                append("local=").append(cidr.ip).append('/').append(cidr.prefix).append('\n')
                if (gatewayIp != null) {
                    append("gateway=").append(gatewayIp).append('\n')
                }
                append("[STDOUT]\n").append(combinedOutput)
                append("\n[STDERR]\n")
                routeCheck?.let {
                    append("\n[ROUTE_CHECK]\n")
                    append(it.summary()).append('\n')
                    append(it.output)
                }
            }

            if (ok) {
                Timber.tag(TAG).i("Статический IP применён: ${cidr.ip}/${cidr.prefix} dev=$iface")
            } else {
                Timber.tag(TAG).w("Не удалось применить статический IP для $iface\n$details")
            }
            ApplyResult(ok, iface, details)
        } catch (t: Throwable) {
            val iface = resolveSelection(force = true).name ?: RuntimeConfig.Root.IFACE
            Timber.tag(TAG).e(t, "Ошибка настройки $iface через root")
            ApplyResult(false, iface, t.toString())
        }
    }

    fun getIfaceProbeState(force: Boolean = false): ProbeState = probeIfaceState(force = force)

    fun isIfacePresent(force: Boolean = false): Boolean = getIfaceProbeState(force = force).exists

    /**
     * Проверяет, что маршрут до приёмника проходит через нужный интерфейс и,
     * при необходимости, с ожидаемым source IP.
     */
    fun canRouteTo(dstIp: String, expectedSrcIp: String? = null, forceProbe: Boolean = false): Boolean {
        return checkRouteTo(dstIp, expectedSrcIp, forceProbe).ok
    }

    fun checkRouteTo(dstIp: String, expectedSrcIp: String? = null, forceProbe: Boolean = false): RouteCheckResult {
        val ip = dstIp.trim()
        if (!isNetworkRootAvailable()) {
            return RouteCheckResult(
                iface = RuntimeConfig.Root.IFACE,
                dstIp = ip,
                expectedSrcIp = expectedSrcIp?.trim(),
                rootRequired = true,
                routeCommandOk = false,
                devOk = false,
                srcOk = false,
                ok = false,
                output = ROOT_REQUIRED_MESSAGE,
            )
        }
        val selection = resolveSelection(force = forceProbe)
        val ifaceName = selection.name ?: RuntimeConfig.Root.IFACE
        if (!isValidIpv4(ip)) {
            return RouteCheckResult(
                iface = ifaceName,
                dstIp = ip,
                expectedSrcIp = expectedSrcIp,
                rootRequired = false,
                routeCommandOk = false,
                devOk = false,
                srcOk = false,
                ok = false,
                output = "некорректный_ip_назначения",
            )
        }

        val normalizedSrcIp = expectedSrcIp?.trim()?.takeIf { it.isNotEmpty() }?.lowercase(Locale.US)
        val probeState = probeIfaceState(force = forceProbe)
        val now = SystemClock.elapsedRealtime()
        if (
            !forceProbe &&
            cachedRouteOk != null &&
            cachedRouteIface == probeState.iface &&
            cachedRouteDstIp == ip &&
            cachedRouteExpectedSrcIp == normalizedSrcIp &&
            (now - cachedRouteCheckAtMs) < routeCacheTtlMs
        ) {
            return RouteCheckResult(
                iface = probeState.iface,
                dstIp = ip,
                expectedSrcIp = normalizedSrcIp,
                rootRequired = false,
                routeCommandOk = cachedRouteOk == true,
                devOk = cachedRouteOk == true,
                srcOk = cachedRouteOk == true,
                ok = cachedRouteOk == true,
                output = "кеш",
            )
        }

        if (probeState.rootRequired || !probeState.exists) {
            updateRouteCache(probeState.iface, ip, normalizedSrcIp, ok = false, checkedAtMs = now)
            return RouteCheckResult(
                iface = probeState.iface,
                dstIp = ip,
                expectedSrcIp = normalizedSrcIp,
                rootRequired = probeState.rootRequired,
                routeCommandOk = false,
                devOk = false,
                srcOk = false,
                ok = false,
                output = probeState.details,
            )
        }

        val result = runNetworkScript(listOf("ip route get $ip"))
        if (!result.ok) {
            updateRouteCache(probeState.iface, ip, normalizedSrcIp, ok = false, checkedAtMs = now)
            return RouteCheckResult(
                iface = probeState.iface,
                dstIp = ip,
                expectedSrcIp = normalizedSrcIp,
                rootRequired = result.rootRequired,
                routeCommandOk = false,
                devOk = false,
                srcOk = false,
                ok = false,
                output = result.combinedText(),
            )
        }

        val output = result.output.lowercase(Locale.US)
        val devRegex = Regex("""\bdev\s+${Regex.escape(probeState.iface.lowercase(Locale.US))}\b""")
        val devOk = devRegex.containsMatchIn(output)
        val srcRegex = normalizedSrcIp?.let { Regex("""\bsrc\s+${Regex.escape(it)}\b""") }
        val srcOk = srcRegex == null || srcRegex.containsMatchIn(output)
        val ok = devOk && srcOk
        updateRouteCache(probeState.iface, ip, normalizedSrcIp, ok = ok, checkedAtMs = now)
        return RouteCheckResult(
            iface = probeState.iface,
            dstIp = ip,
            expectedSrcIp = normalizedSrcIp,
            rootRequired = false,
            routeCommandOk = true,
            devOk = devOk,
            srcOk = srcOk,
            ok = ok,
            output = result.output.trim(),
        )
    }

    fun getSelectedIfaceName(force: Boolean = false): String? = resolveSelection(force = force).name

    fun logSelectedIface(tag: String, prefix: String, force: Boolean = false) {
        NetworkInterfaceSelector.logSelection(tag, prefix, resolveSelection(force = force))
    }

    private fun probeIfaceState(force: Boolean): ProbeState {
        val selection = resolveSelection(force = force)
        val iface = selection.name
        if (iface.isNullOrBlank()) {
            return ProbeState(
                iface = RuntimeConfig.Root.IFACE,
                exists = false,
                linkUp = false,
                rootRequired = false,
                details = buildString {
                    append("iface=").append(RuntimeConfig.Root.IFACE).append('\n')
                    append("selection=").append(selection.summary())
                },
            )
        }

        val now = SystemClock.elapsedRealtime()
        if (!force && cachedIfaceName == iface && cachedIfaceExists != null && (now - cachedIfaceCheckAtMs) < ifaceCacheTtlMs) {
            val exists = cachedIfaceExists == true
            return ProbeState(
                iface = iface,
                exists = exists,
                linkUp = exists,
                rootRequired = false,
                details = buildString {
                    append("iface=").append(iface).append('\n')
                    append(buildIfaceExistsLabel(iface)).append('=').append(exists).append('\n')
                    append(buildIfaceLinkLabel(iface)).append('=').append(exists).append('\n')
                    append("selection=").append(selection.summary())
                },
            )
        }

        val sanitizedIface = sanitizeIfaceName(iface)
        if (sanitizedIface == null) {
            return ProbeState(
                iface = iface,
                exists = false,
                linkUp = false,
                rootRequired = false,
                details = buildString {
                    append("iface=").append(iface).append('\n')
                    append("selection=").append(selection.summary()).append('\n')
                    append("invalid_iface_name=true")
                },
            )
        }

        val result = runNetworkScript(
            listOf(
                "ip link show dev $sanitizedIface",
            ),
        )
        if (result.rootRequired) {
            return ProbeState(
                iface = iface,
                exists = false,
                linkUp = false,
                rootRequired = true,
                details = buildString {
                    append("iface=").append(iface).append('\n')
                    append("selection=").append(selection.summary()).append('\n')
                    append("root_required=true\n")
                    append(result.renderOutput())
                },
            )
        }

        val exists = result.output.lowercase(Locale.US).contains("$sanitizedIface:")
        val linkUp = exists && isIfaceLinkUp(result.output)
        cachedIfaceName = iface
        cachedIfaceExists = exists
        if (exists) wasSelectedIfaceEverPresent = true
        cachedIfaceCheckAtMs = now
        return ProbeState(
            iface = iface,
            exists = exists,
            linkUp = linkUp,
            rootRequired = false,
            details = buildString {
                append("iface=").append(iface).append('\n')
                append(buildIfaceExistsLabel(iface)).append('=').append(exists).append('\n')
                append(buildIfaceLinkLabel(iface)).append('=').append(linkUp).append('\n')
                append("selection=").append(selection.summary()).append('\n')
                append(result.renderOutput())
            },
        )
    }

    private fun resolveSelection(force: Boolean): NetworkInterfaceSelector.Selection {
        return NetworkInterfaceSelector.select(RuntimeConfig.Root.IFACE).also { selection ->
            if (force || cachedIfaceName != selection.name) {
                NetworkInterfaceSelector.logSelection(TAG, "Выбран сетевой интерфейс", selection)
            }
        }
    }

    private fun buildIfaceExistsLabel(iface: String): String = "${iface}_exists"

    private fun buildIfaceLinkLabel(iface: String): String = "${iface}_link_up"

    internal fun isIfaceLinkUp(output: String): Boolean {
        val normalized = output.lowercase(Locale.US)
        if (normalized.contains("lower_up")) return true
        if (normalized.contains("running")) return true
        return output.lineSequence()
            .map { it.trim() }
            .any { it == "1" }
    }

    internal fun buildInterfaceSetupBatch(iface: String, cidr: Ipv4Cidr): List<String> {
        return listOf(
            "ip addr del ${cidr.ip}/${cidr.prefix} dev $iface",
            "ip link set $iface up",
            "ip addr replace ${cidr.ip}/${cidr.prefix} dev $iface",
            "ip -o -4 addr show dev $iface",
        )
    }

    internal fun buildRoutingBatch(
        iface: String,
        cidr: Ipv4Cidr,
        gatewayIp: String,
        includeFwmarkRule: Boolean,
    ): List<String> {
        return buildList {
            add("ip route del $gatewayIp")
            add("ip route del $gatewayIp/32")
            add("ip rule del to $gatewayIp/32 lookup main priority 11000")
            add("ip rule del from ${cidr.ip}/32 lookup main priority 11001")
            add("ip rule del to $gatewayIp/32 lookup main priority 51")
            add("ip rule del from ${cidr.ip}/32 lookup main priority 52")
            if (includeFwmarkRule) {
                add("ip rule del fwmark 0x1 lookup main priority 50")
            }
            add("ip route replace ${cidr.network}/${cidr.prefix} dev $iface scope link src ${cidr.ip} table main")
            add("ip route replace $gatewayIp/32 dev $iface scope link src ${cidr.ip} table main")
            if (includeFwmarkRule) {
                add("ip rule add fwmark 0x1 lookup main priority 50")
            }
            add("ip rule add to $gatewayIp/32 lookup main priority 51")
            add("ip rule add from ${cidr.ip}/32 lookup main priority 52")
            add("ip route flush cache")
            add("ip route get $gatewayIp")
            add("ip rule show")
            add("ip route show dev $iface")
        }
    }

    internal fun buildIptablesBatch(gatewayIp: String): List<String> {
        return listOf(
            "iptables -t mangle -D OUTPUT -d $gatewayIp -j MARK --set-mark 0x1",
            "iptables -t mangle -A OUTPUT -d $gatewayIp -j MARK --set-mark 0x1",
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

    private fun updateRouteCache(
        iface: String,
        dstIp: String,
        expectedSrcIp: String?,
        ok: Boolean,
        checkedAtMs: Long,
    ) {
        cachedRouteIface = iface
        cachedRouteDstIp = dstIp
        cachedRouteExpectedSrcIp = expectedSrcIp
        cachedRouteOk = ok
        cachedRouteCheckAtMs = checkedAtMs
    }

    private fun canUseIptables(): Boolean {
        val result = runNetworkScript(listOf("iptables -t mangle -S OUTPUT"))
        return result.ok
    }

    private fun isNetworkRootAvailable(): Boolean = networkRootShell?.isAvailable() == true

    private fun runNetworkScript(commands: List<String>): NetworkScriptResult {
        val shell = networkRootShell ?: return NetworkScriptResult.failure(
            error = "NetworkRootShell не подключён",
            rootRequired = true,
        )
        if (!shell.isAvailable()) {
            return NetworkScriptResult.failure(error = ROOT_REQUIRED_MESSAGE, rootRequired = true)
        }
        return shell.execScript(commands).fold(
            onSuccess = { NetworkScriptResult.success(it) },
            onFailure = { NetworkScriptResult.failure(it.message ?: it.toString()) },
        )
    }

    private fun sanitizeIfaceName(iface: String?): String? {
        val normalized = iface?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return normalized.takeIf { IFACE_NAME_REGEX.matches(it) }
    }

    internal data class Ipv4Cidr(
        val ip: String,
        val prefix: Int,
        val network: String,
    )

    private data class NetworkScriptResult(
        val ok: Boolean,
        val output: String,
        val error: String,
        val rootRequired: Boolean = false,
    ) {
        fun combinedText(): String {
            return buildString {
                if (output.isNotBlank()) {
                    append(output)
                }
                if (error.isNotBlank()) {
                    if (isNotEmpty()) append('\n')
                    append(error)
                }
            }
        }

        fun renderOutput(): String {
            return buildString {
                append("[STDOUT]\n").append(output)
                append("\n[STDERR]\n").append(error)
            }
        }

        companion object {
            fun success(output: String): NetworkScriptResult =
                NetworkScriptResult(ok = true, output = output, error = "")

            fun failure(error: String, rootRequired: Boolean = false): NetworkScriptResult =
                NetworkScriptResult(ok = false, output = "", error = error, rootRequired = rootRequired)
        }
    }
}
