package ru.foric27.cluster

import android.content.Intent
import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.Rule
import java.io.File

class UpdateFileLocatorTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `persist get and clear tree uri round trip works`() {
        val prefs = FakeSharedPreferences()
        val permissionAccess = FakePersistedTreePermissionAccess()
        val treeUri = "content://com.android.externalstorage.documents/tree/primary%3A"

        val persisted = UpdateFileLocator.persistGrantedTreeUri(
            prefs = prefs,
            treeUriString = treeUri,
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        ) { flags ->
            permissionAccess.take(flags)
        }

        assertTrue(persisted)
        assertEquals(treeUri, UpdateFileLocator.getPersistedTreeUriString(prefs))
        assertEquals(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            permissionAccess.takenFlags,
        )

        val cleared = UpdateFileLocator.clearPersistedTreeUri(prefs, treeUri) {
            permissionAccess.release(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }

        assertTrue(cleared)
        assertNull(UpdateFileLocator.getPersistedTreeUriString(prefs))
        assertEquals(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            permissionAccess.releasedFlags,
        )
    }

    @Test
    fun `get persisted tree uri returns null when nothing saved`() {
        assertNull(UpdateFileLocator.getPersistedTreeUriString(FakeSharedPreferences()))
    }

    @Test
    fun `gracefully handles missing persisted uri`() {
        val parsed = UpdateFileLocator.getPersistedTreeUri(FakeSharedPreferences()) { raw ->
            error("parser should not run for missing uri: $raw")
        }

        assertNull(parsed)
    }

    @Test
    fun `parse root descriptor identifies internal storage root`() {
        val descriptor = parseRootDescriptor("primary:")

        assertEquals(UpdateFileLocator.SourceKind.INTERNAL, descriptor.sourceKind)
        assertEquals("primary", descriptor.volumeId)
        assertEquals("", descriptor.relativePath)
        assertTrue(descriptor.isStorageRoot)
    }

    @Test
    fun `parse root descriptor identifies usb subdirectory as non root`() {
        val descriptor = parseRootDescriptor("1234-5678:updates")

        assertEquals(UpdateFileLocator.SourceKind.USB, descriptor.sourceKind)
        assertEquals("1234-5678", descriptor.volumeId)
        assertEquals("updates", descriptor.relativePath)
        assertFalse(descriptor.isStorageRoot)
    }

    @Test
    fun `build directory label uses internal and usb paths`() {
        val internal = parseRootDescriptor("primary:")
        val usb = parseRootDescriptor("ABCD-0001:")

        assertEquals("/storage/emulated/0", buildDirectoryLabel(internal.raw))
        assertEquals("/storage/ABCD-0001", buildDirectoryLabel(usb.raw))
    }

    @Test
    fun `file root search finds update pair in storage root`() {
        val storageRoot = temporaryFolder.newFolder("storage-root")
        val zip = File(storageRoot, ProductConfig.UpdateFtp.UPDATE_ZIP_NAME).apply { writeText("zip") }
        val sig = File(storageRoot, ProductConfig.UpdateFtp.UPDATE_SIG_NAME).apply { writeText("sig") }

        val candidates = UpdateFileLocator().findCandidatesInFileRoots(
            listOf(
                UpdateFileLocator.FileScanRoot(
                    sourceKind = UpdateFileLocator.SourceKind.INTERNAL,
                    directoryLabel = storageRoot.absolutePath,
                    directory = storageRoot,
                ),
            ),
        )

        assertEquals(1, candidates.size)
        val candidate = candidates.single()
        assertEquals(UpdateFileLocator.SourceKind.INTERNAL, candidate.sourceKind)
        assertEquals(storageRoot.absolutePath, candidate.directoryLabel)
        assertEquals(zip.absolutePath, candidate.zipFile.debugPath)
        assertEquals(sig.absolutePath, candidate.sigFile.debugPath)
    }

    @Test
    fun `file root search is not recursive`() {
        val storageRoot = temporaryFolder.newFolder("storage-root")
        val nested = File(storageRoot, "updates").apply { mkdirs() }
        File(nested, ProductConfig.UpdateFtp.UPDATE_ZIP_NAME).writeText("zip")
        File(nested, ProductConfig.UpdateFtp.UPDATE_SIG_NAME).writeText("sig")

        val candidates = UpdateFileLocator().findCandidatesInFileRoots(
            listOf(
                UpdateFileLocator.FileScanRoot(
                    sourceKind = UpdateFileLocator.SourceKind.INTERNAL,
                    directoryLabel = storageRoot.absolutePath,
                    directory = storageRoot,
                ),
            ),
        )

        assertTrue(candidates.isEmpty())
    }

    private fun parseRootDescriptor(treeDocumentId: String): ReflectedRootDescriptor {
        val companionClass = Class.forName("ru.foric27.cluster.UpdateFileLocator\$Companion")
        val companionInstance = UpdateFileLocator::class.java.getDeclaredField("Companion").apply {
            isAccessible = true
        }.get(null)
        val method = companionClass.getDeclaredMethod("parseRootDescriptor", String::class.java).apply {
            isAccessible = true
        }
        val raw = requireNotNull(method.invoke(companionInstance, treeDocumentId))
        val rawClass = raw.javaClass
        return ReflectedRootDescriptor(
            raw = raw,
            sourceKind = rawClass.getDeclaredField("sourceKind").apply { isAccessible = true }.get(raw) as UpdateFileLocator.SourceKind,
            volumeId = rawClass.getDeclaredField("volumeId").apply { isAccessible = true }.get(raw) as String,
            relativePath = rawClass.getDeclaredField("relativePath").apply { isAccessible = true }.get(raw) as String,
            isStorageRoot = rawClass.getDeclaredField("isStorageRoot").apply { isAccessible = true }.getBoolean(raw),
        )
    }

    private fun buildDirectoryLabel(rootDescriptor: Any): String {
        val companionClass = Class.forName("ru.foric27.cluster.UpdateFileLocator\$Companion")
        val companionInstance = UpdateFileLocator::class.java.getDeclaredField("Companion").apply {
            isAccessible = true
        }.get(null)
        val method = companionClass.getDeclaredMethod("buildDirectoryLabel", rootDescriptor.javaClass).apply {
            isAccessible = true
        }
        return method.invoke(companionInstance, rootDescriptor) as String
    }

    private data class ReflectedRootDescriptor(
        val raw: Any,
        val sourceKind: UpdateFileLocator.SourceKind,
        val volumeId: String,
        val relativePath: String,
        val isStorageRoot: Boolean,
    )

    private class FakePersistedTreePermissionAccess {
        var takenFlags: Int? = null
        var releasedFlags: Int? = null

        fun take(flags: Int) {
            takenFlags = flags
        }

        fun release(flags: Int) {
            releasedFlags = flags
        }
    }

    private class FakeSharedPreferences : SharedPreferences {
        private val values = LinkedHashMap<String, Any?>()

        override fun getAll(): MutableMap<String, *> = LinkedHashMap(values)

        override fun getString(key: String?, defValue: String?): String? = values[key] as String? ?: defValue

        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? {
            @Suppress("UNCHECKED_CAST")
            return (values[key] as MutableSet<String>?) ?: defValues
        }

        override fun getInt(key: String?, defValue: Int): Int = values[key] as Int? ?: defValue

        override fun getLong(key: String?, defValue: Long): Long = values[key] as Long? ?: defValue

        override fun getFloat(key: String?, defValue: Float): Float = values[key] as Float? ?: defValue

        override fun getBoolean(key: String?, defValue: Boolean): Boolean = values[key] as Boolean? ?: defValue

        override fun contains(key: String?): Boolean = values.containsKey(key)

        override fun edit(): SharedPreferences.Editor = Editor()

        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

        private inner class Editor : SharedPreferences.Editor {
            private val updates = LinkedHashMap<String, Any?>()
            private var clearRequested = false

            override fun putString(key: String?, value: String?): SharedPreferences.Editor = apply { updates[key.orEmpty()] = value }

            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = apply { updates[key.orEmpty()] = values?.toMutableSet() }

            override fun putInt(key: String?, value: Int): SharedPreferences.Editor = apply { updates[key.orEmpty()] = value }

            override fun putLong(key: String?, value: Long): SharedPreferences.Editor = apply { updates[key.orEmpty()] = value }

            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = apply { updates[key.orEmpty()] = value }

            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = apply { updates[key.orEmpty()] = value }

            override fun remove(key: String?): SharedPreferences.Editor = apply { updates[key.orEmpty()] = null }

            override fun clear(): SharedPreferences.Editor = apply { clearRequested = true }

            override fun commit(): Boolean {
                if (clearRequested) {
                    values.clear()
                }
                updates.forEach { (key, value) ->
                    if (value == null) {
                        values.remove(key)
                    } else {
                        values[key] = value
                    }
                }
                return true
            }

            override fun apply() {
                commit()
            }
        }
    }
}
