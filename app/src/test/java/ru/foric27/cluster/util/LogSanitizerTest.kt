package ru.foric27.cluster.util

import org.junit.Assert.assertEquals
import org.junit.Test

class LogSanitizerTest {

    // ---- Существующие тесты (не трогать) ----

    @Test
    fun `sanitize redacts common secret values`() {
        val value = "password=fetisov token:abc secret = xyz authorization=Bearer123 keep=visible"

        assertEquals(
            "password=<redacted> token:<redacted> secret = <redacted> authorization=<redacted> keep=visible",
            LogSanitizer.sanitize(value),
        )
    }

    @Test
    fun `sanitize leaves regular diagnostic text unchanged`() {
        val value = "route ok iface=eth0 target=192.168.40.2"

        assertEquals(value, LogSanitizer.sanitize(value))
    }

    // ---- Multiline ----

    @Test
    fun `sanitize redacts secret on first line and leaves continuation`() {
        // \S+ останавливается на \n — секрет на первой строке маскируется,
        // продолжение на второй строке остаётся видимым (известное ограничение).
        val value = "line1 password=secret123\nline2 secret_continuation"
        assertEquals(
            "line1 password=<redacted>\nline2 secret_continuation",
            LogSanitizer.sanitize(value),
        )
    }

    // ---- JSON ----

    @Test
    fun `sanitize does not redact JSON-formatted secrets`() {
        // Кавычки перед : не дают \s*[=:] сматчиться — известное ограничение.
        val value = """{"password":"s3cr3t", "token":"abc123"}"""
        assertEquals(value, LogSanitizer.sanitize(value))
    }

    @Test
    fun `sanitize does not redact JSON with internal spaces`() {
        val value = """{"password": "secret123", "token": "xyz789"}"""
        assertEquals(value, LogSanitizer.sanitize(value))
    }

    // ---- URL с query-параметрами ----

    @Test
    fun `sanitize handles URL query parameters with token`() {
        // \S+ захватывает всё до пробела: token=abc123&secret=xyz целиком.
        val value = "https://api.example.com/auth?token=abc123&secret=xyz"
        assertEquals(
            "https://api.example.com/auth?token=<redacted>",
            LogSanitizer.sanitize(value),
        )
    }

    @Test
    fun `sanitize handles URL query parameters with secret before token`() {
        val value = "https://api.example.com/auth?secret=xyz&token=abc123"
        // token pattern срабатывает первым, заменяя token=abc123
        val result = LogSanitizer.sanitize(value)
        // secret потом доедает оставшееся
        assertEquals(
            "https://api.example.com/auth?secret=<redacted>",
            result,
        )
    }

    // ---- Bearer / JWT ----

    @Test
    fun `sanitize only redacts Bearer prefix before space`() {
        // \S+ матчит только "Bearer" (до пробела), JWT-payload остаётся.
        val value = "Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0In0.dGVzdA"
        assertEquals(
            "Authorization: <redacted> eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0In0.dGVzdA",
            LogSanitizer.sanitize(value),
        )
    }

    @Test
    fun `sanitize redacts Bearer token when no space after keyword`() {
        // Если Bearer склеен с токеном (без пробела), \S+ захватывает всё.
        val value = "Authorization: Bearer_eyJhbGciOiJIUzI1NiJ9"
        assertEquals(
            "Authorization: <redacted>",
            LogSanitizer.sanitize(value),
        )
    }

    // ---- Unicode ----

    @Test
    fun `sanitize handles unicode secret values`() {
        // \S матчит не-ASCII непробельные символы.
        val value = "password=привет token=тест secret=секрет"
        assertEquals(
            "password=<redacted> token=<redacted> secret=<redacted>",
            LogSanitizer.sanitize(value),
        )
    }

    // ---- pass (отдельный ключ) ----

    @Test
    fun `sanitize redacts pass key standalone`() {
        assertEquals("pass=<redacted>", LogSanitizer.sanitize("pass=mysecret"))
        assertEquals("pass:<redacted>", LogSanitizer.sanitize("pass:mysecret"))
    }

    @Test
    fun `sanitize does not match pass as substring of unrelated words`() {
        // "pass" в составе слов без [=:] после него — без ложных срабатываний.
        val value = "passenger list passport data passing through"
        assertEquals(value, LogSanitizer.sanitize(value))
    }

    // ---- Несколько секретов в одной строке ----

    @Test
    fun `sanitize handles multiple secrets separated by spaces`() {
        val value = "password=123 token=abc secret=xyz pass:qwe"
        assertEquals(
            "password=<redacted> token=<redacted> secret=<redacted> pass:<redacted>",
            LogSanitizer.sanitize(value),
        )
    }

    @Test
    fun `sanitize handles adjacent secret pairs`() {
        val value = "password=a token=b secret=c"
        assertEquals(
            "password=<redacted> token=<redacted> secret=<redacted>",
            LogSanitizer.sanitize(value),
        )
    }

    // ---- Краевые случаи ----

    @Test
    fun `sanitize handles empty string`() {
        assertEquals("", LogSanitizer.sanitize(""))
    }

    @Test
    fun `sanitize handles string with keys but no separators`() {
        val value = "password token secret authorization pass"
        // Нет [=:] после ключевых слов — замен не происходит.
        assertEquals(value, LogSanitizer.sanitize(value))
    }

    @Test
    fun `sanitize handles secrets with special non-whitespace characters`() {
        val value = "password=a!b@c#d\$e% token=x-y_z"
        assertEquals(
            "password=<redacted> token=<redacted>",
            LogSanitizer.sanitize(value),
        )
    }

    @Test
    fun `sanitize handles value adjacent to next key`() {
        // \S+ захватывает всё до пробела: "abc:token:xyz"
        val value = "password=abc:token:xyz"
        assertEquals(
            "password=<redacted>",
            LogSanitizer.sanitize(value),
        )
    }

    // ---- case-insensitive ----

    @Test
    fun `sanitize redacts uppercase variants`() {
        assertEquals(
            "PASSWORD=<redacted> TOKEN:<redacted>",
            LogSanitizer.sanitize("PASSWORD=secret TOKEN:abc"),
        )
    }

    @Test
    fun `sanitize redacts mixed case variants`() {
        assertEquals(
            "Password=<redacted> ToKeN:<redacted>",
            LogSanitizer.sanitize("Password=secret ToKeN:abc"),
        )
    }

    @Test
    fun `sanitize redacts ftp password`() {
        assertEquals("ftp_password=<redacted>", LogSanitizer.sanitize("ftp_password=topsecret"))
        assertEquals("ftp-password:<redacted>", LogSanitizer.sanitize("ftp-password:topsecret"))
    }

    @Test
    fun `sanitize redacts api key and automotive identifiers`() {
        assertEquals("api_key=<redacted>", LogSanitizer.sanitize("api_key=abcdef"))
        assertEquals("imei=<redacted>", LogSanitizer.sanitize("imei=123456789012345"))
        assertEquals("serial_number=<redacted>", LogSanitizer.sanitize("serial_number=ABC123"))
        assertEquals("vin=<redacted>", LogSanitizer.sanitize("vin=1HGBH41JXMN109186"))
    }

    @Test
    fun `sanitize redacts MAC but keeps cluster IP`() {
        assertEquals(
            "iface=<mac-redacted> target=192.168.40.2",
            LogSanitizer.sanitize("iface=AA:BB:CC:DD:EE:FF target=192.168.40.2"),
        )
        assertEquals(
            "route ok iface=eth0 target=192.168.40.2",
            LogSanitizer.sanitize("route ok iface=eth0 target=192.168.40.2"),
        )
    }
}
