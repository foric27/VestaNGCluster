package ru.foric27.cluster

import timber.log.Timber
import java.net.NetworkInterface
import java.util.TreeSet

/**
 * Выбирает USB/Ethernet-интерфейс для root-сети.
 *
 * Значение `auto` включает поиск известных USB/RNDIS/CDC/NCM/ECM интерфейсов.
 * Если вручную заданный интерфейс пропал, selector всё равно пробует найти
 * подходящий USB-интерфейс, чтобы не зависеть от имени `eth0` на разных устройствах.
 */
object NetworkInterfaceSelector {

    private const val TAG = "NetIfaceSelector"
    private const val AUTO = "auto"
    private val KNOWN_USB_EXACT_NAMES = listOf("usb0", "usb1", "rndis0", "rndis1", "eth0", "eth1")
    private val KNOWN_USB_PREFIXES = listOf("usb", "rndis", "enx", "enp", "eth", "cdc", "ncm", "ecm")

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
        return selectFromAvailable(available, preferredName)
    }

    internal fun selectFromAvailable(
        availableNames: Collection<String>,
        preferredName: String = RuntimeConfig.Root.IFACE,
    ): Selection {
        val available = TreeSet<String>(String.CASE_INSENSITIVE_ORDER).apply {
            availableNames
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .forEach(::add)
        }.toList()

        val preferred = preferredName.trim()
        if (preferred.isEmpty() || preferred.equals(AUTO, ignoreCase = true)) {
            val auto = findKnownUsbInterface(available)
            return Selection(
                name = auto,
                source = if (auto == null) "auto_missing" else "auto_usb",
                available = available,
            )
        }

        if (available.any { it.equals(preferred, ignoreCase = true) }) {
            return Selection(
                name = available.first { it.equals(preferred, ignoreCase = true) },
                source = "configured",
                available = available,
            )
        }

        val auto = findKnownUsbInterface(available)
        return if (auto != null) {
            Selection(
                name = auto,
                source = "configured_missing_auto_usb",
                available = available,
            )
        } else {
            Selection(
                name = null,
                source = "configured_missing",
                available = available,
            )
        }
    }

    private fun findKnownUsbInterface(available: List<String>): String? {
        KNOWN_USB_EXACT_NAMES.firstNotNullOfOrNull { candidate ->
            available.firstOrNull { it.equals(candidate, ignoreCase = true) }
        }?.let { return it }

        return available.firstOrNull { name ->
            val normalized = name.lowercase()
            KNOWN_USB_PREFIXES.any { prefix -> normalized.startsWith(prefix) }
        }
    }

    fun logSelection(tag: String, prefix: String, selection: Selection) {
        Timber.tag(tag).i("$prefix: ${selection.summary()}")
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
            Timber.tag(TAG).w(it, "Не удалось получить список интерфейсов через NetworkInterface")
        }

        return names.toList()
    }
}
