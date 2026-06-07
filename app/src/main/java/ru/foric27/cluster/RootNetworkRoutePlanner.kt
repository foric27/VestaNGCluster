package ru.foric27.cluster

internal data class RootNetworkRoutePlan(
    val iface: String,
    val cidr: Ipv4Cidr,
    val gatewayIp: String,
    val routingTable: String,
    val commands: List<String>,
)

/**
 * Чистая модель policy-route команд без запуска shell.
 *
 * Минимальный набор команд — только то, что реально меняет routing state.
 * Удалены:
 * - диагностические команды (ip route get/show, ip rule show)
 * - избыточные cleanup (ip route del без /32, legacy priority 11000/11001)
 * - ip route flush cache (не требуется на ядрах 3.6+, не везде поддерживается)
 */
internal object RootNetworkRoutePlanner {

    fun plan(
        iface: String,
        localCidr: String,
        gatewayIp: String,
        routingTable: String = DEFAULT_ROUTING_TABLE,
        includeFwmarkRule: Boolean,
    ): Result<RootNetworkRoutePlan> {
        val cleanIface = iface.trim()
        if (cleanIface.isEmpty()) return failure("iface is missing")
        if (!IFACE_NAME_REGEX.matches(cleanIface)) return failure("iface is invalid: $iface")

        val cidr = RootNetworkAddressing.parseIpv4Cidr(localCidr)
            ?: return failure("localCidr is invalid: $localCidr")

        val cleanGateway = gatewayIp.trim()
        if (!RootNetworkAddressing.isValidIpv4(cleanGateway)) {
            return failure("gateway is invalid: $gatewayIp")
        }
        if (!isHostInCidr(cleanGateway, cidr)) {
            return failure("gateway is outside subnet: $cleanGateway !in ${cidr.network}/${cidr.prefix}")
        }
        if (isReservedSubnetAddress(cleanGateway, cidr)) {
            return failure("gateway is reserved subnet address: $cleanGateway")
        }

        val table = routingTable.trim()
        if (!isValidRoutingTable(table)) return failure("routing table is invalid: $routingTable")

        return Result.success(
            RootNetworkRoutePlan(
                iface = cleanIface,
                cidr = cidr,
                gatewayIp = cleanGateway,
                routingTable = table,
                commands = buildCommands(
                    iface = cleanIface,
                    cidr = cidr,
                    gatewayIp = cleanGateway,
                    routingTable = table,
                    includeFwmarkRule = includeFwmarkRule,
                ),
            ),
        )
    }

    private fun buildCommands(
        iface: String,
        cidr: Ipv4Cidr,
        gatewayIp: String,
        routingTable: String,
        includeFwmarkRule: Boolean,
    ): List<String> {
        return buildList {
            // Idempotent cleanup перед apply
            add("ip route del $gatewayIp/$IPV4_BITS")
            add("ip rule del to $gatewayIp/$IPV4_BITS lookup $routingTable priority $GATEWAY_TO_PRIORITY")
            add("ip rule del from ${cidr.ip}/$IPV4_BITS lookup $routingTable priority $GATEWAY_FROM_PRIORITY")
            if (includeFwmarkRule) {
                add("ip rule del fwmark $FWMARK_VALUE lookup $routingTable priority $FWMARK_PRIORITY")
            }
            // Основные команды: network route + host route
            add("ip route replace ${cidr.network}/${cidr.prefix} dev $iface scope link src ${cidr.ip} table $routingTable")
            add("ip route replace $gatewayIp/$IPV4_BITS dev $iface scope link src ${cidr.ip} table $routingTable")
            // Policy rules: fwmark (optional), gateway, local
            if (includeFwmarkRule) {
                add("ip rule add fwmark $FWMARK_VALUE lookup $routingTable priority $FWMARK_PRIORITY")
            }
            add("ip rule add to $gatewayIp/$IPV4_BITS lookup $routingTable priority $GATEWAY_TO_PRIORITY")
            add("ip rule add from ${cidr.ip}/$IPV4_BITS lookup $routingTable priority $GATEWAY_FROM_PRIORITY")
            // NOTE: ip route flush cache намеренно опущен — не требуется на ядрах 3.6+,
            // отсутствует на некоторых Android-устройствах. Route kernel cache устаревает
            // автоматически при изменении routing table.
        }
    }

    private fun isHostInCidr(ip: String, cidr: Ipv4Cidr): Boolean {
        val mask = maskForPrefix(cidr.prefix)
        val maskedIp = toIpv4Int(ip) and mask
        val maskedCidrIp = toIpv4Int(cidr.ip) and mask
        return maskedIp == maskedCidrIp
    }

    private fun isReservedSubnetAddress(ip: String, cidr: Ipv4Cidr): Boolean {
        if (cidr.prefix >= HOSTLESS_PREFIX_START) return false
        val mask = maskForPrefix(cidr.prefix)
        val address = toIpv4Int(ip)
        val network = toIpv4Int(cidr.ip) and mask
        val broadcast = network or mask.inv()
        return address == network || address == broadcast
    }

    private fun isValidRoutingTable(value: String): Boolean {
        if (value == DEFAULT_ROUTING_TABLE) return true
        return value.toIntOrNull() in ROUTING_TABLE_RANGE
    }

    private fun maskForPrefix(prefix: Int): Int {
        return if (prefix == 0) 0 else (-1 shl (IPV4_BITS - prefix))
    }

    private fun toIpv4Int(value: String): Int {
        val octets = value.split('.').map { it.toInt() }
        return (octets[0] shl 24) or
            (octets[1] shl 16) or
            (octets[2] shl 8) or
            octets[3]
    }

    private fun <T> failure(message: String): Result<T> {
        return Result.failure(IllegalArgumentException(message))
    }

    private const val DEFAULT_ROUTING_TABLE = "main"
    private const val FWMARK_VALUE = "0x1"
    private const val FWMARK_PRIORITY = 50
    private const val GATEWAY_TO_PRIORITY = 51
    private const val GATEWAY_FROM_PRIORITY = 52
    private const val IPV4_BITS = 32
    private const val HOSTLESS_PREFIX_START = 31
    private val IFACE_NAME_REGEX = Regex("^[a-zA-Z0-9._:-]+$")
    private val ROUTING_TABLE_RANGE = 1..252
}
