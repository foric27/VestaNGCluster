package ru.foric27.cluster.network
import ru.foric27.cluster.config.*

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
    private val KNOWN_USB_EXACT_NAMES = listOf(
        "usb0",
        "usb1",
        "usb2",
        "usb3",
        "rndis0",
        "rndis1",
        "rndis2",
        "rndis_data0",
        "rndis_data1",
        "ncm0",
        "ncm1",
        "ecm0",
        "ecm1",
        "cdc0",
        "cdc1",
        "eth0",
        "eth1",
    )
    private val KNOWN_USB_PREFIXES = listOf("usb", "rndis", "enx", "enp", "eth", "cdc", "ncm", "ecm")

    /**
     * Результат выбора сетевого интерфейса.
     *
     * @param name выбранное имя интерфейса или null
     * @param source источник выбора (configured, auto_usb, configured_missing и т.д.)
     * @param available список всех доступных интерфейсов
     */
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

    /**
     * Выбирает сетевой интерфейс на основе предпочтительного имени.
     *
     * При `auto` или пустом значении ищет известный USB-интерфейс.
     * Если предпочтительный интерфейс отсутствует — fallback на auto-поиск.
     *
     * @param preferredName предпочтительное имя интерфейса или "auto"
     * @return выбор с именем, источником и списком доступных
     */
    fun select(preferredName: String = RuntimeConfig.Root.IFACE): Selection {
        val available = discoverInterfaces()
        return selectFromAvailable(available, preferredName)
    }

    /**
     * Выбирает интерфейс из предоставленного списка доступных.
     *
     * @param availableNames список доступных интерфейсов
     * @param preferredName предпочтительное имя или "auto"
     * @return выбор с именем, источником и списком доступных
     */
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

    /**
     * Ищет известный USB-интерфейс в списке доступных.
     *
     * Сначала проверяет точные совпадения, затем — по префиксам.
     *
     * @param available список доступных интерфейсов
     * @return имя найденного USB-интерфейса или null
     */
    private fun findKnownUsbInterface(available: List<String>): String? {
        KNOWN_USB_EXACT_NAMES.firstNotNullOfOrNull { candidate ->
            available.firstOrNull { it.equals(candidate, ignoreCase = true) }
        }?.let { return it }

        return available.firstOrNull { name ->
            val normalized = name.lowercase()
            KNOWN_USB_PREFIXES.any { prefix -> normalized.startsWith(prefix) }
        }
    }

    /**
     * Логирует выбор интерфейса через Timber.
     *
     * @param tag тег для Timber
     * @param prefix текстовый префикс сообщения
     * @param selection результат выбора интерфейса
     */
    fun logSelection(tag: String, prefix: String, selection: Selection) {
        Timber.tag(tag).i("$prefix: ${selection.summary()}")
    }

    /**
     * Обнаруживает все сетевые интерфейсы системы через [NetworkInterface].
     *
     * @return отсортированный список имён интерфейсов
     */
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
