package ru.foric27.cluster

import android.util.Log
import java.io.File
import java.net.NetworkInterface
import java.util.Locale
import java.util.TreeSet

/**
 * Выбирает подходящий сетевой интерфейс для root-настройки и FTP.
 *
 * Сначала уважает явно заданное имя интерфейса, а если его нет в системе,
 * переходит к автоопределению по приоритетным шаблонам проекта.
 */
object NetworkInterfaceSelector {

    private const val TAG = "NetIfaceSelector"
    private const val AUTO_INTERFACE_VALUE = "auto"

    data class Selection(
        val name: String?,
        val source: String,
        val available: List<String>,
    ) {
        fun summary(): String {
            val names = if (available.isEmpty()) "нет" else available.joinToString(", ")
            return "source=$source, selected=${name ?: "none"}, available=[$names]"
        }
    }

    fun select(preferredName: String = RuntimeConfig.Root.IFACE): Selection {
        val available = discoverInterfaces()
        val preferred = preferredName
            .trim()
            .takeIf { it.isNotEmpty() && !it.equals(AUTO_INTERFACE_VALUE, ignoreCase = true) }
        if (preferred != null && available.contains(preferred)) {
            return Selection(
                name = preferred,
                source = "configured",
                available = available,
            )
        }

        val selected = pickByPriority(available)
        val source = when {
            selected == null -> "not_found"
            preferred.isNullOrEmpty() -> "auto"
            preferred == selected -> "configured"
            else -> "fallback_from_$preferred"
        }
        return Selection(
            name = selected,
            source = source,
            available = available,
        )
    }

    fun logSelection(tag: String, prefix: String, selection: Selection) {
        Log.i(tag, "$prefix: ${selection.summary()}")
    }

    private fun discoverInterfaces(): List<String> {
        val names = TreeSet<String>(String.CASE_INSENSITIVE_ORDER)

        runCatching {
            val enumeration = NetworkInterface.getNetworkInterfaces()
            while (enumeration != null && enumeration.hasMoreElements()) {
                val iface = enumeration.nextElement()
                val name = iface.name?.trim().orEmpty()
                if (name.isNotEmpty()) {
                    names += name
                }
            }
        }.onFailure {
            Log.w(TAG, "Не удалось получить список интерфейсов через NetworkInterface", it)
        }

        runCatching {
            val sysClassNet = File("/sys/class/net")
            val listed = sysClassNet.list()?.map { it.trim() }.orEmpty()
            listed.filter { it.isNotEmpty() }.forEach { names += it }
        }.onFailure {
            logDiscoveryFailure("/sys/class/net", it)
        }

        runCatching {
            File("/proc/net/dev")
                .useLines { lines ->
                    lines
                        .drop(2)
                        .mapNotNull { line ->
                            line.substringBefore(':')
                                .trim()
                                .takeIf { it.isNotEmpty() }
                        }
                        .forEach { names += it }
                }
        }.onFailure {
            logDiscoveryFailure("/proc/net/dev", it)
        }

        if (names.none(::isHighPriorityCandidate)) {
            runCatching {
                RootShell.su(
                    cmds = listOf("ip -o link show", "cat /proc/net/dev"),
                    logOnFailure = false,
                ).takeIf { it.ok() }?.let { result ->
                    collectInterfacesFromRootOutput(result.out).forEach { names += it }
                }
            }.onFailure {
                Log.w(TAG, "Не удалось получить список интерфейсов через root", it)
            }
        }

        return names.toList()
    }

    private fun pickByPriority(available: List<String>): String? {
        if (available.isEmpty()) return null

        val prefixPriority = listOf("eth", "usb", "rndis")
        for (prefix in prefixPriority) {
            available.firstOrNull { it.lowercase(Locale.US).startsWith(prefix) }?.let { return it }
        }

        available.firstOrNull { it.equals("ccmni-lan", ignoreCase = true) }?.let { return it }

        return null
    }

    private fun collectInterfacesFromRootOutput(output: String): Set<String> {
        val names = TreeSet<String>(String.CASE_INSENSITIVE_ORDER)
        output.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty()) return@forEach

            Regex("""^\d+:\s*([^:@]+)""").find(line)?.groupValues?.getOrNull(1)
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let { names += it.substringBefore('@') }

            line.substringBefore(':')
                .trim()
                .takeIf { it.isNotEmpty() && !it.contains(' ') && it != "Inter-|"}?.let { names += it }
        }
        return names
    }

    private fun isHighPriorityCandidate(name: String): Boolean {
        val normalized = name.lowercase(Locale.US)
        return normalized.startsWith("eth") ||
            normalized.startsWith("usb") ||
            normalized.startsWith("rndis") ||
            normalized == "ccmni-lan"
    }

    private fun logDiscoveryFailure(source: String, error: Throwable) {
        val message = error.message.orEmpty()
        if (message.contains("EACCES", ignoreCase = true) || message.contains("Permission denied", ignoreCase = true)) {
            Log.i(TAG, "Доступ к $source ограничен на этом устройстве; продолжаю поиск другими способами")
            return
        }
        Log.w(TAG, "Не удалось получить список интерфейсов через $source", error)
    }
}
