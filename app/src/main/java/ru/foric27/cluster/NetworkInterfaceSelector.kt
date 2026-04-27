package ru.foric27.cluster

import timber.log.Timber
import java.net.NetworkInterface
import java.util.TreeSet

/**
 * Проверяет только явно заданный сетевой интерфейс из runtime-настроек.
 *
 * Автоподбор интерфейса намеренно не используется: проект работает только
 * с тем именем, которое задано в конфиге разработчика.
 */
object NetworkInterfaceSelector {

    private const val TAG = "NetIfaceSelector"

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
        if (preferred.isEmpty()) {
            return Selection(
                name = null,
                source = "missing_config",
                available = available,
            )
        }

        return if (available.any { it.equals(preferred, ignoreCase = true) }) {
            Selection(
                name = available.first { it.equals(preferred, ignoreCase = true) },
                source = "configured",
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
