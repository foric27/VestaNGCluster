package ru.foric27.cluster

internal data class Ipv4Cidr(
    val ip: String,
    val prefix: Int,
    val network: String,
)

internal object RootNetworkAddressing {

    fun parseIpv4Cidr(value: String): Ipv4Cidr? {
        val raw = value.trim()
        if (raw.isEmpty()) return null
        val ip = raw.substringBefore('/').trim()
        if (!isValidIpv4(ip)) return null
        val prefix = raw.substringAfter('/', "24").trim().toIntOrNull() ?: return null
        if (prefix !in 0..IPV4_BITS) return null
        return Ipv4Cidr(ip = ip, prefix = prefix, network = calculateNetworkAddress(ip, prefix))
    }

    fun isValidIpv4(value: String): Boolean {
        val parts = value.split('.')
        if (parts.size != IPV4_OCTETS) return false
        return parts.all { part ->
            val number = part.toIntOrNull() ?: return@all false
            number in IPV4_OCTET_RANGE
        }
    }

    private fun calculateNetworkAddress(ip: String, prefix: Int): String {
        val bytes = ip.split('.').map { it.toInt() }
        val address =
            (bytes[0] shl 24) or
                (bytes[1] shl 16) or
                (bytes[2] shl 8) or
                bytes[3]
        val mask = if (prefix == 0) 0 else (-1 shl (IPV4_BITS - prefix))
        val network = address and mask
        return listOf(
            (network ushr 24) and 0xFF,
            (network ushr 16) and 0xFF,
            (network ushr 8) and 0xFF,
            network and 0xFF,
        ).joinToString(".")
    }

    private const val IPV4_BITS = 32
    private const val IPV4_OCTETS = 4
    private val IPV4_OCTET_RANGE = 0..255
}
