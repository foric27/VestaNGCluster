package ru.foric27.cluster.util

/**
 * Удаляет из диагностических логов значения, похожие на чувствительные данные.
 */
internal object LogSanitizer {

    private val sensitivePatterns = listOf(
        Regex("(?i)(password\\s*[=:]\\s*)\\S+"),
        Regex("(?i)(pass\\s*[=:]\\s*)\\S+"),
        Regex("(?i)(token\\s*[=:]\\s*)\\S+"),
        Regex("(?i)(secret\\s*[=:]\\s*)\\S+"),
        Regex("(?i)(authorization\\s*[=:]\\s*)\\S+"),
        Regex("(?i)(ftp[_-]?password\\s*[=:]\\s*)\\S+"),
        Regex("(?i)(api[_-]?key\\s*[=:]\\s*)\\S+"),
        Regex("(?i)(imei\\s*[=:]\\s*)\\S+"),
        Regex("(?i)(serial[_-]?number\\s*[=:]\\s*)\\S+"),
        Regex("(?i)(vin\\s*[=:]\\s*)\\S+"),
    )

    private val macPattern = Regex("(?i)(\\b[0-9a-f]{2}[:\\-]){5}[0-9a-f]{2}\\b")

    /**
     * Заменяет чувствительные данные в строке на `<redacted>`.
     *
     * @param value исходная строка
     * @return очищенная строка
     */
    fun sanitize(value: String): String {
        val partiallySanitized = sensitivePatterns.fold(value) { current, pattern ->
            pattern.replace(current) { match -> "${match.groupValues[1]}<redacted>" }
        }
        return macPattern.replace(partiallySanitized, "<mac-redacted>")
    }
}
