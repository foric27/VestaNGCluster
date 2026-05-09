package ru.foric27.cluster

/** Удаляет из диагностических логов значения, похожие на чувствительные данные. */
internal object LogSanitizer {

    private val sensitivePatterns = listOf(
        Regex("(?i)(password\\s*[=:]\\s*)\\S+"),
        Regex("(?i)(pass\\s*[=:]\\s*)\\S+"),
        Regex("(?i)(token\\s*[=:]\\s*)\\S+"),
        Regex("(?i)(secret\\s*[=:]\\s*)\\S+"),
        Regex("(?i)(authorization\\s*[=:]\\s*)\\S+"),
    )

    fun sanitize(value: String): String {
        return sensitivePatterns.fold(value) { current, pattern ->
            pattern.replace(current) { match -> "${match.groupValues[1]}<redacted>" }
        }
    }
}
