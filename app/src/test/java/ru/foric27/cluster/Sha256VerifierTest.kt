package ru.foric27.cluster

import android.content.Context
import android.content.ContextWrapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale

class Sha256VerifierTest {

    private val context: Context = ContextWrapper(null)

    @Test
    fun `verify accepts uppercase signature with line breaks`() {
        val zipBytes = "zip-content".toByteArray()
        val expected = sha256(zipBytes)
        val sigText = expected.uppercase(Locale.US).chunked(16).joinToString("\r\n")

        val result = Sha256Verifier().verify(
            context = context,
            zipFile = MemorySourceFile("ICUpdate.zip", zipBytes),
            sigFile = MemorySourceFile("ICUpdate.zip.sig", sigText.toByteArray()),
        )

        assertTrue(result.valid)
        assertEquals(expected, result.expectedSha256)
        assertEquals(expected, result.actualSha256)
    }

    @Test
    fun `verify rejects malformed signature without throwing`() {
        val result = Sha256Verifier().verify(
            context = context,
            zipFile = MemorySourceFile("ICUpdate.zip", "zip".toByteArray()),
            sigFile = MemorySourceFile("ICUpdate.zip.sig", "not-a-sha".toByteArray()),
        )

        assertFalse(result.valid)
        assertEquals("", result.expectedSha256)
        assertEquals("", result.actualSha256)
    }

    @Test
    fun `verify rejects mismatched checksum`() {
        val result = Sha256Verifier().verify(
            context = context,
            zipFile = MemorySourceFile("ICUpdate.zip", "zip".toByteArray()),
            sigFile = MemorySourceFile("ICUpdate.zip.sig", "0".repeat(64).toByteArray()),
        )

        assertFalse(result.valid)
        assertEquals("0".repeat(64), result.expectedSha256)
    }

    private fun sha256(bytes: ByteArray): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString(separator = "") { byte -> "%02x".format(Locale.US, byte.toInt() and 0xFF) }
    }

    private data class MemorySourceFile(
        override val name: String,
        private val bytes: ByteArray,
    ) : UpdateFileLocator.UpdateSourceFile {
        override val debugPath: String = "memory://$name"
        override val lastModified: Long = 0L
        override val size: Long = bytes.size.toLong()

        override fun openInputStream(context: Context): InputStream = ByteArrayInputStream(bytes)
    }
}
