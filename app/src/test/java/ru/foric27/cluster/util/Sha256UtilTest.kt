package ru.foric27.cluster.util

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File

class Sha256UtilTest {

    @Test
    fun `sha256Hex of empty InputStream returns known hash`() {
        val input = ByteArrayInputStream(ByteArray(0))
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            Sha256Util.sha256Hex(input),
        )
    }

    @Test
    fun `sha256Hex of hello returns known hash`() {
        val input = ByteArrayInputStream("hello".toByteArray())
        assertEquals(
            "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
            Sha256Util.sha256Hex(input),
        )
    }

    @Test
    fun `sha256Hex of File matches sha256Hex of InputStream`() {
        val file = File.createTempFile("sha256test", ".tmp")
        try {
            file.writeBytes("test content".toByteArray())
            val fileHash = Sha256Util.sha256Hex(file)
            val streamHash = Sha256Util.sha256Hex(file.inputStream())
            assertEquals(streamHash, fileHash)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `sha256Hex of empty File returns known hash`() {
        val file = File.createTempFile("sha256test", ".tmp")
        try {
            assertEquals(
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                Sha256Util.sha256Hex(file),
            )
        } finally {
            file.delete()
        }
    }
}
