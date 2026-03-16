package ru.foric27.cluster

/**
 * Минимальный JSON-контейнер для совместимого формата пакетов синхронизации.
 *
 * Используется только для простых ключей/значений, но корректно экранирует строки,
 * чтобы пакет не ломался на кавычках, обратных слешах и переводах строк.
 */
class SimpleJsonContainer {

    private val values: MutableMap<String, Any?> = LinkedHashMap()

    fun addValue(key: String, value: Any?) {
        values[key] = value
    }

    override fun toString(): String {
        val sb = StringBuilder()
        sb.append('{')
        val iterator = values.entries.iterator()
        while (iterator.hasNext()) {
            val (key, value) = iterator.next()
            sb.append('"').append(escape(key)).append('"').append(':')
            appendJsonValue(sb, value)
            if (iterator.hasNext()) {
                sb.append(',')
            }
        }
        sb.append('}')
        return sb.toString()
    }

    private fun appendJsonValue(sb: StringBuilder, value: Any?) {
        when (value) {
            null -> sb.append("null")
            is Number, is Boolean -> sb.append(value.toString())
            else -> sb.append('"').append(escape(value.toString())).append('"')
        }
    }

    private fun escape(value: String): String {
        val out = StringBuilder(value.length + 8)
        for (ch in value) {
            when (ch) {
                '\\' -> out.append("\\\\")
                '"' -> out.append("\\\"")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> out.append(ch)
            }
        }
        return out.toString()
    }
}
