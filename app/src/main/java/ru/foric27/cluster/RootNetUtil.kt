package ru.foric27.cluster

import android.os.SystemClock
import timber.log.Timber
import java.util.Locale

/**
 * Низкоуровневая root-настройка сети для cluster-пайплайна.
 *
 * Утилита выбирает явно заданный или автоматически найденный USB-интерфейс,
 * применяет статический IP и кеширует дорогие root-проверки, чтобы сервис не
 * спамил su без необходимости.
 */
internal object RootNetUtil {

    private const val TAG = "RootNetUtil"
    private const val ROOT_REQUIRED_MESSAGE = "Нет privileged backend с root-правами"
    private val IFACE_NAME_REGEX = Regex("^[a-zA-Z0-9._:-]+$")

    private class Constants private constructor() {
        companion object {
            const val FWMARK_VALUE = "0x1"
            const val FWMARK_PRIORITY = 50
            const val GATEWAY_TO_PRIORITY = 51
            const val GATEWAY_FROM_PRIORITY = 52
            const val RULE_DELETE_PRIORITY_BASE = 11000
            const val IPV4_BITS = 32
            const val MAX_SCRIPT_ATTEMPTS = 2
            const val EXIT_CODE_TIMEOUT = -2
        }
    }

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
    @Volatile private var networkRootShell: NetworkRootCommandExecutor? = null
    @Volatile private var routePlanner: RootRoutePlanner = DefaultRootRoutePlanner

    data class ApplyResult(
        val ok: Boolean,
        val iface: String,
        val details: String,
        val rootRequired: Boolean = false,
        val failureReason: FailureReason? = null,
    )

    enum class FailureReason {
        ROOT_UNAVAILABLE,
        IFACE_MISSING,
        ROUTE_TIMEOUT,
        ROUTE_NOT_APPLIED,
        NETWORK_UNREACHABLE,
    }

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
            return when {
                ok -> {
                    "маршрут через $iface подтверждён, dst=$dstIp" +
                        (expectedSrcIp?.let { ", src=$it" } ?: "")
                }
                else -> {
                    "маршрут не совпал, dst=$dstIp, ожидаемый интерфейс=$iface" +
                        (expectedSrcIp?.let { ", ожидаемый src=$it" } ?: "")
                }
            }
        }

        fun failureReason(): FailureReason? {
            if (ok) return null
            return when {
                rootRequired -> FailureReason.ROOT_UNAVAILABLE
                !routeCommandOk -> FailureReason.NETWORK_UNREACHABLE
                !devOk || !srcOk -> FailureReason.ROUTE_NOT_APPLIED
                else -> FailureReason.NETWORK_UNREACHABLE
            }
        }
    }

    private data class ApplyStaticContext(
        val cidr: Ipv4Cidr,
        val iface: String,
        val gatewayIp: String?,
    )

    private data class ApplyRoutingState(
        val routingResult: NetworkScriptResult,
        val shouldApplyIptables: Boolean,
    )

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

    fun attachNetworkRootShell(shell: NetworkRootCommandExecutor) {
        networkRootShell = shell
    }

    fun attachRoutePlanner(planner: RootRoutePlanner) {
        routePlanner = planner
    }

    @Synchronized
    fun applyStaticIfaceNetwork(localCidr: String, gateway: String?): ApplyResult {
        return try {
            val context = probeAndValidate(localCidr, gateway)
            if (context is ApplyResult) {
                return context
            }
            context as ApplyStaticContext

            val interfaceResult = setupInterface(context)
            if (!interfaceResult.ok) {
                return ApplyResult(
                    ok = false,
                    iface = context.iface,
                    details = buildString {
                        append("iface=").append(context.iface).append('\n')
                        append(buildIfaceExistsLabel(context.iface)).append("=true\n")
                        append("local=").append(context.cidr.ip).append('/').append(context.cidr.prefix).append('\n')
                        append(interfaceResult.renderOutput())
                     },
                     rootRequired = interfaceResult.rootRequired,
                    failureReason = interfaceResult.failureReason ?: FailureReason.ROUTE_NOT_APPLIED,
                 )
             }

            val routingState = applyRouting(context, interfaceResult)
            if (routingState is ApplyResult) {
                return routingState
            }
            routingState as ApplyRoutingState

            val iptablesResult = applyIptables(
                context = context,
                interfaceResult = interfaceResult,
                routingResult = routingState.routingResult,
                shouldApplyIptables = routingState.shouldApplyIptables,
            )
            if (iptablesResult is ApplyResult) {
                return iptablesResult
            }
            iptablesResult as NetworkScriptResult

            val result = verifyApplied(
                context = context,
                interfaceResult = interfaceResult,
                routingResult = routingState.routingResult,
                iptablesResult = iptablesResult,
            )

            if (result.ok) {
                Timber.tag(TAG).i("Статический IP применён: ${context.cidr.ip}/${context.cidr.prefix} dev=${context.iface}")
            } else {
                Timber.tag(TAG).w("Не удалось применить статический IP для ${context.iface}\n${result.details}")
            }
            result
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
        checkRouteCache(
            probeState = probeState,
            dstIp = ip,
            expectedSrcIp = normalizedSrcIp,
            forceProbe = forceProbe,
            now = now,
        )?.let { return it }

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

        val routeProbeResult = executeRouteProbe(ip)
        if (!routeProbeResult.ok) {
            updateRouteCache(probeState.iface, ip, normalizedSrcIp, ok = false, checkedAtMs = now)
            return RouteCheckResult(
                iface = probeState.iface,
                dstIp = ip,
                expectedSrcIp = normalizedSrcIp,
                rootRequired = routeProbeResult.rootRequired,
                routeCommandOk = false,
                devOk = false,
                srcOk = false,
                ok = false,
                output = routeProbeResult.combinedText(),
            )
        }

        val parsedResult = parseRouteOutput(probeState.iface, normalizedSrcIp, routeProbeResult.output)
        updateRouteCache(probeState.iface, ip, normalizedSrcIp, ok = parsedResult.ok, checkedAtMs = now)
        return RouteCheckResult(
            iface = probeState.iface,
            dstIp = ip,
            expectedSrcIp = normalizedSrcIp,
            rootRequired = false,
            routeCommandOk = true,
            devOk = parsedResult.devOk,
            srcOk = parsedResult.srcOk,
            ok = parsedResult.ok,
            output = routeProbeResult.output.trim(),
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
            val exists = cachedIfaceExists ?: false
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
        if (normalized.contains("lower_up") || normalized.contains("running")) return true
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
        return routePlanner.plan(
            iface = iface,
            localCidr = "${cidr.ip}/${cidr.prefix}",
            gatewayIp = gatewayIp,
            routingTable = "main",
            includeFwmarkRule = includeFwmarkRule,
        ).getOrThrow().commands
    }

    internal fun buildIptablesBatch(gatewayIp: String): List<String> {
        return listOf(
            "iptables -t mangle -D OUTPUT -d $gatewayIp -j MARK --set-mark ${Constants.FWMARK_VALUE}",
            "iptables -t mangle -A OUTPUT -d $gatewayIp -j MARK --set-mark ${Constants.FWMARK_VALUE}",
        )
    }

    private fun parseIpv4Cidr(value: String): Ipv4Cidr? {
        return RootNetworkAddressing.parseIpv4Cidr(value)
    }

    private fun isValidIpv4(value: String): Boolean {
        return RootNetworkAddressing.isValidIpv4(value)
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

    private fun isNetworkRootAvailable(): Boolean = networkRootShell?.isAvailable() ?: false

    private fun probeAndValidate(localCidr: String, gateway: String?): Any {
        val probeState = probeIfaceState(force = true)
        if (probeState.rootRequired) {
            Timber.tag(TAG).w("Root недоступен — настройка ${probeState.iface} пропущена")
            return ApplyResult(
                ok = false,
                iface = probeState.iface,
                details = probeState.details,
                 rootRequired = true,
                failureReason = FailureReason.ROOT_UNAVAILABLE,
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
            return ApplyResult(false, probeState.iface, details, failureReason = FailureReason.IFACE_MISSING)
         }

         val cidr = parseIpv4Cidr(localCidr)
            ?: return ApplyResult(false, probeState.iface, "Некорректный localCidr: $localCidr", failureReason = FailureReason.ROUTE_NOT_APPLIED)
         val iface = sanitizeIfaceName(probeState.iface)
            ?: return ApplyResult(false, probeState.iface, "Некорректный iface: ${probeState.iface}", failureReason = FailureReason.ROUTE_NOT_APPLIED)
         val gatewayIp = gateway?.trim()?.takeIf { it.isNotEmpty() }
         if (gatewayIp != null && !isValidIpv4(gatewayIp)) {
            return ApplyResult(false, probeState.iface, "Некорректный gateway: $gatewayIp", failureReason = FailureReason.ROUTE_NOT_APPLIED)
         }

        return ApplyStaticContext(
            cidr = cidr,
            iface = iface,
            gatewayIp = gatewayIp,
        )
    }

    private fun setupInterface(context: ApplyStaticContext): NetworkScriptResult {
        return runNetworkScript(buildInterfaceSetupBatch(context.iface, context.cidr))
    }

    private fun applyRouting(context: ApplyStaticContext, interfaceResult: NetworkScriptResult): Any {
        val gatewayIp = context.gatewayIp ?: return ApplyRoutingState(
            routingResult = NetworkScriptResult.success(""),
            shouldApplyIptables = false,
        )
        val iptablesAvailable = canUseIptables()
        val routingResult = runNetworkScript(
            buildRoutingBatch(context.iface, context.cidr, gatewayIp, includeFwmarkRule = iptablesAvailable),
        )
        if (!routingResult.ok) {
            return ApplyResult(
                ok = false,
                iface = context.iface,
                details = buildString {
                    append("iface=").append(context.iface).append('\n')
                    append(buildIfaceExistsLabel(context.iface)).append("=true\n")
                    append("local=").append(context.cidr.ip).append('/').append(context.cidr.prefix).append('\n')
                    append("gateway=").append(gatewayIp).append('\n')
                    append(interfaceResult.renderOutput())
                    append('\n')
                    append(routingResult.renderOutput())
                 },
                 rootRequired = routingResult.rootRequired,
                failureReason = routingResult.failureReason ?: FailureReason.ROUTE_NOT_APPLIED,
             )
         }
        return ApplyRoutingState(
            routingResult = routingResult,
            shouldApplyIptables = iptablesAvailable,
        )
    }

    private fun applyIptables(
        context: ApplyStaticContext,
        interfaceResult: NetworkScriptResult,
        routingResult: NetworkScriptResult,
        shouldApplyIptables: Boolean,
    ): Any {
        val gatewayIp = context.gatewayIp ?: return NetworkScriptResult.success("")
        if (!shouldApplyIptables) return NetworkScriptResult.success("")
        val iptablesResult = runNetworkScript(buildIptablesBatch(gatewayIp))
        if (!iptablesResult.ok) {
            return ApplyResult(
                ok = false,
                iface = context.iface,
                details = buildString {
                    append("iface=").append(context.iface).append('\n')
                    append(buildIfaceExistsLabel(context.iface)).append("=true\n")
                    append("local=").append(context.cidr.ip).append('/').append(context.cidr.prefix).append('\n')
                    append("gateway=").append(gatewayIp).append('\n')
                    append(interfaceResult.renderOutput())
                    append('\n')
                    append(routingResult.renderOutput())
                    append('\n')
                    append(iptablesResult.renderOutput())
                 },
                 rootRequired = iptablesResult.rootRequired,
                failureReason = iptablesResult.failureReason ?: FailureReason.ROUTE_NOT_APPLIED,
             )
         }
        return iptablesResult
    }

    private fun verifyApplied(
        context: ApplyStaticContext,
        interfaceResult: NetworkScriptResult,
        routingResult: NetworkScriptResult,
        iptablesResult: NetworkScriptResult,
    ): ApplyResult {
        clearCaches()
        val routeCheck = context.gatewayIp?.let { checkRouteTo(it, context.cidr.ip, forceProbe = true) }
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
        val outputLowercase = combinedOutput.lowercase(Locale.US)
        val ok = interfaceResult.ok && routingResult.ok && iptablesResult.ok &&
            combinedOutput.contains(" ${context.cidr.ip}/") &&
            (
                context.gatewayIp == null ||
                    routeCheck?.ok ?: false ||
                    outputLowercase.contains("${context.gatewayIp} dev ${context.iface}") ||
                    outputLowercase.contains("${context.gatewayIp} scope link") ||
                    outputLowercase.contains("${context.gatewayIp}/${Constants.IPV4_BITS} dev ${context.iface}")
                )
        val details = buildString {
            append("iface=").append(context.iface).append('\n')
            append(buildIfaceExistsLabel(context.iface)).append("=true\n")
            append("local=").append(context.cidr.ip).append('/').append(context.cidr.prefix).append('\n')
            context.gatewayIp?.let {
                append("gateway=").append(it).append('\n')
            }
            append(formatCommandOutput(stdout = combinedOutput, stderr = ""))
            routeCheck?.let {
                append("\n[ROUTE_CHECK]\n")
                append(it.summary()).append('\n')
                append(it.output)
            }
        }
        return ApplyResult(
            ok = ok,
            iface = context.iface,
            details = details,
            failureReason = if (ok) null else routeCheck?.failureReason() ?: FailureReason.ROUTE_NOT_APPLIED,
        )
    }

    private fun checkRouteCache(
        probeState: ProbeState,
        dstIp: String,
        expectedSrcIp: String?,
        forceProbe: Boolean,
        now: Long,
    ): RouteCheckResult? {
        if (
            forceProbe ||
            cachedRouteOk == null ||
            cachedRouteIface != probeState.iface ||
            cachedRouteDstIp != dstIp ||
            cachedRouteExpectedSrcIp != expectedSrcIp ||
            (now - cachedRouteCheckAtMs) >= routeCacheTtlMs
        ) {
            return null
        }
        val cachedOk = cachedRouteOk ?: false
        return RouteCheckResult(
            iface = probeState.iface,
            dstIp = dstIp,
            expectedSrcIp = expectedSrcIp,
            rootRequired = false,
            routeCommandOk = cachedOk,
            devOk = cachedOk,
            srcOk = cachedOk,
            ok = cachedOk,
            output = "кеш",
        )
    }

    private fun executeRouteProbe(dstIp: String): NetworkScriptResult {
        return runNetworkScript(listOf("ip route get $dstIp"))
    }

    private data class ParsedRouteOutput(
        val devOk: Boolean,
        val srcOk: Boolean,
        val ok: Boolean,
    )

    private fun parseRouteOutput(iface: String, expectedSrcIp: String?, output: String): ParsedRouteOutput {
        val outputLowercase = output.lowercase(Locale.US)
        val devRegex = Regex("""\bdev\s+${Regex.escape(iface.lowercase(Locale.US))}\b""")
        val devOk = devRegex.containsMatchIn(outputLowercase)
        val srcRegex = expectedSrcIp?.let { Regex("""\bsrc\s+${Regex.escape(it)}\b""") }
        val srcOk = srcRegex?.containsMatchIn(outputLowercase) ?: true
        return ParsedRouteOutput(
            devOk = devOk,
            srcOk = srcOk,
            ok = devOk && srcOk,
        )
    }

    private fun formatCommandOutput(stdout: String, stderr: String): String {
        return buildString {
            append("[STDOUT]\n").append(stdout)
            append("\n[STDERR]\n").append(stderr)
        }
    }

    private fun runNetworkScript(commands: List<String>): NetworkScriptResult {
        val shell = networkRootShell ?: return NetworkScriptResult.failure(
            error = "NetworkRootShell не подключён",
            rootRequired = true,
            failureReason = FailureReason.ROOT_UNAVAILABLE,
        )
        if (!shell.isAvailable()) {
            return NetworkScriptResult.failure(
                error = ROOT_REQUIRED_MESSAGE,
                rootRequired = true,
                failureReason = FailureReason.ROOT_UNAVAILABLE,
            )
        }
        val result = shell.execScript(commands).fold(
            onSuccess = { NetworkScriptResult.success(it) },
            onFailure = { NetworkScriptResult.failure(it.message ?: it.toString()) },
        )
        val commandPreview = commands.firstOrNull().orEmpty()
        if (result.ok) {
            Timber.tag(TAG).d(
                "root_script_ok cmd=${LogSanitizer.sanitize(commandPreview)} stdout=${LogSanitizer.sanitize(result.output.take(240))}",
            )
        } else {
            Timber.tag(TAG).w(
                "root_script_fail cmd=${LogSanitizer.sanitize(commandPreview)} rootRequired=${result.rootRequired} stderr=${LogSanitizer.sanitize(result.error.take(240))}",
            )
        }
        return result
    }

    private fun sanitizeIfaceName(iface: String?): String? {
        val normalized = iface?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return normalized.takeIf { IFACE_NAME_REGEX.matches(it) }
    }

    private data class NetworkScriptResult(
        val ok: Boolean,
        val output: String,
        val error: String,
        val rootRequired: Boolean = false,
        val failureReason: FailureReason? = null,
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
            return formatCommandOutput(stdout = output, stderr = error)
        }

        companion object {
            fun success(output: String): NetworkScriptResult =
                NetworkScriptResult(ok = true, output = output, error = "")

            fun failure(
                error: String,
                rootRequired: Boolean = false,
                failureReason: FailureReason? = null,
            ): NetworkScriptResult = NetworkScriptResult(
                ok = false,
                output = "",
                error = error,
                rootRequired = rootRequired,
                failureReason = failureReason,
            )
        }
    }
}
