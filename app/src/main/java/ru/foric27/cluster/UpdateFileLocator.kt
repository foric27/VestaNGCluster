package ru.foric27.cluster

import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.util.Locale

/**
 * Поиск кандидатов ICUpdate.zip / ICUpdate.zip.sig.
 *
 * Product-логика:
 * - внутренняя память проверяется только в корне /storage/emulated/0;
 * - USB-накопители проверяются только в корне каждого тома;
 * - рекурсивный обход каталогов не используется.
 */
internal class UpdateFileLocator {

    enum class SearchPolicy {
        INTERNAL_ONLY,
        USB_FIRST,
    }

    enum class SourceKind(
        val priority: Int,
        val displayName: String,
    ) {
        USB(priority = 0, displayName = "USB"),
        INTERNAL(priority = 1, displayName = "Внутренняя память"),
    }

    interface UpdateSourceFile {
        val name: String
        val debugPath: String
        val lastModified: Long
        val size: Long
        fun openInputStream(context: Context): InputStream
    }

    data class LocatedUpdatePair(
        val sourceKind: SourceKind,
        val sourceLabel: String,
        val directoryLabel: String,
        val zipFile: UpdateSourceFile,
        val sigFile: UpdateSourceFile,
        val lastModified: Long,
    )

    fun findCandidates(
        context: Context,
        searchPolicy: SearchPolicy,
    ): List<LocatedUpdatePair> {
        val roots = buildRoots(context, searchPolicy)
        val candidates = ArrayList<LocatedUpdatePair>()
        roots.forEach { root ->
            val candidate = inspectRoot(root)
            if (candidate != null) {
                candidates += candidate
            }
        }
        return candidates.sortedWith(
            compareBy<LocatedUpdatePair> { it.sourceKind.priority }
                .thenByDescending { it.lastModified },
        )
    }

    private fun buildRoots(
        context: Context,
        searchPolicy: SearchPolicy,
    ): List<ScanRoot> {
        val result = ArrayList<ScanRoot>()
        val seen = LinkedHashSet<String>()

        fun addRoot(sourceKind: SourceKind, label: String, rawRoot: File?) {
            val normalizedRoot = normalizeStorageRoot(rawRoot) ?: return
            val key = normalizedRoot.absolutePath
            if (!seen.add(key)) return
            result += ScanRoot(sourceKind = sourceKind, label = label, fileRoot = normalizedRoot)
        }

        if (searchPolicy == SearchPolicy.USB_FIRST) {
            collectUsbRoots(context).forEach { usbRoot ->
                addRoot(SourceKind.USB, "Корень USB: ${usbRoot.absolutePath}", usbRoot)
            }
        }

        addRoot(
            SourceKind.INTERNAL,
            "Корень внутренней памяти: ${INTERNAL_STORAGE_ROOT.absolutePath}",
            INTERNAL_STORAGE_ROOT,
        )

        return result.sortedBy { it.sourceKind.priority }
    }

    private fun collectUsbRoots(context: Context): List<File> {
        val result = ArrayList<File>()
        val seen = LinkedHashSet<String>()

        fun addUsbRoot(rawRoot: File?) {
            val normalizedRoot = normalizeStorageRoot(rawRoot) ?: return
            if (!isLikelyUsbRoot(normalizedRoot)) return
            val key = normalizedRoot.absolutePath
            if (seen.add(key)) {
                result += normalizedRoot
            }
        }

        context.getExternalFilesDirs(null).forEach { dir ->
            val currentDir = dir ?: return@forEach
            val removable = runCatching { Environment.isExternalStorageRemovable(currentDir) }.getOrDefault(false)
            if (removable) {
                addUsbRoot(currentDir)
            }
        }

        File("/storage").listFiles()
            ?.asSequence()
            ?.filter { it.isDirectory }
            ?.forEach(::addUsbRoot)

        return result.sortedBy { it.absolutePath.lowercase(Locale.US) }
    }

    private fun inspectRoot(root: ScanRoot): LocatedUpdatePair? {
        val directory = root.fileRoot
        val rootProbe = probePath(directory, expectDirectory = true)
        if (!rootProbe.exists || !rootProbe.isDirectory) {
            Log.i(
                TAG,
                "Корень недоступен: ${root.label}; exists=${rootProbe.exists}, isDirectory=${rootProbe.isDirectory}, canRead=${rootProbe.canRead}",
            )
            return null
        }

        val zip = File(directory, RuntimeConfig.UpdateFtp.UPDATE_ZIP_NAME)
        val sig = File(directory, RuntimeConfig.UpdateFtp.UPDATE_SIG_NAME)
        val zipProbe = probePath(zip, expectDirectory = false)
        val sigProbe = probePath(sig, expectDirectory = false)

        Log.i(
            TAG,
            "Проверяю корень: ${directory.absolutePath}, " +
                "zip=${zip.absolutePath}[exists=${zipProbe.exists},isFile=${zipProbe.isFile},canRead=${zipProbe.canRead}], " +
                "sig=${sig.absolutePath}[exists=${sigProbe.exists},isFile=${sigProbe.isFile},canRead=${sigProbe.canRead}]",
        )

        if (!zipProbe.exists || !zipProbe.isFile) return null
        if (!sigProbe.exists || !sigProbe.isFile) return null

        Log.i(
            TAG,
            "Найдена пара обновления в корне: zip=${zip.absolutePath}, sig=${sig.absolutePath}, источник=${root.label}",
        )
        return LocatedUpdatePair(
            sourceKind = root.sourceKind,
            sourceLabel = root.label,
            directoryLabel = directory.absolutePath,
            zipFile = FileSourceFile(zip, zipProbe),
            sigFile = FileSourceFile(sig, sigProbe),
            lastModified = maxOf(zipProbe.lastModified, sigProbe.lastModified),
        )
    }

    private fun normalizeStorageRoot(rawRoot: File?): File? {
        val file = rawRoot ?: return null
        val absolutePath = file.absolutePath
        if (absolutePath.isBlank()) return null

        val androidIndex = absolutePath.indexOf("/Android/", ignoreCase = true)
        val candidate = if (androidIndex > 0) {
            File(absolutePath.substring(0, androidIndex))
        } else {
            file
        }
        val probe = probePath(candidate, expectDirectory = true)
        return candidate.takeIf { probe.exists && probe.isDirectory }
    }

    private fun isLikelyUsbRoot(root: File): Boolean {
        val lowerPath = root.absolutePath.lowercase(Locale.US)
        if (!lowerPath.startsWith("/storage/")) return false
        if (lowerPath == "/storage") return false
        if (lowerPath == INTERNAL_STORAGE_ROOT.absolutePath.lowercase(Locale.US)) return false
        if (lowerPath.startsWith("/storage/emulated")) return false
        if (lowerPath.startsWith("/storage/self")) return false
        if (lowerPath.startsWith("/storage/enc_emulated")) return false
        val name = root.name.lowercase(Locale.US)
        if (name in INTERNAL_STORAGE_NAMES) return false
        return true
    }

    private data class ScanRoot(
        val sourceKind: SourceKind,
        val label: String,
        val fileRoot: File,
    )

    private data class FileSourceFile(
        private val file: File,
        private val probe: PathProbe,
    ) : UpdateSourceFile {
        override val name: String = file.name
        override val debugPath: String = file.absolutePath
        override val lastModified: Long = probe.lastModified
        override val size: Long = probe.size

        override fun openInputStream(context: Context): InputStream {
            return if (file.canRead()) {
                FileInputStream(file)
            } else {
                RootShell.openInputStream(file)
            }
        }
    }

    private companion object {
        private const val TAG = "UpdateFileLocator"
        private val INTERNAL_STORAGE_ROOT = File("/storage/emulated/0")

        private val INTERNAL_STORAGE_NAMES = setOf(
            "emulated",
            "self",
            "enc_emulated",
        )

        private data class PathProbe(
            val exists: Boolean,
            val isFile: Boolean,
            val isDirectory: Boolean,
            val canRead: Boolean,
            val lastModified: Long,
            val size: Long,
        )

        private fun probePath(file: File, expectDirectory: Boolean): PathProbe {
            val exists = file.exists()
            val isDirectory = file.isDirectory
            val isFile = file.isFile
            val canRead = file.canRead()
            val lastModified = file.lastModified()
            val size = file.length()
            if (exists && ((expectDirectory && isDirectory) || (!expectDirectory && isFile))) {
                return PathProbe(exists, isFile, isDirectory, canRead, lastModified, size)
            }
            if (!UsbStoragePathMatcher.isUsbStoragePath(file.absolutePath)) {
                return PathProbe(exists, isFile, isDirectory, canRead, lastModified, size)
            }
            return probePathViaRoot(file, expectDirectory)
                ?: PathProbe(exists, isFile, isDirectory, canRead, lastModified, size)
        }

        private fun probePathViaRoot(file: File, expectDirectory: Boolean): PathProbe? {
            val typeFlag = if (expectDirectory) "-d" else "-f"
            val command = "if [ $typeFlag ${shellQuote(file.absolutePath)} ]; then stat -c '%Y:%s' ${shellQuote(file.absolutePath)}; fi"
            val result = RootShell.exec(listOf(command), logOnFailure = false)
            val output = result.out.trim()
            if (!result.ok() || output.isBlank()) return null
            val parts = output.split(':', limit = 2)
            return PathProbe(
                exists = true,
                isFile = !expectDirectory,
                isDirectory = expectDirectory,
                canRead = false,
                lastModified = (parts.getOrNull(0)?.toLongOrNull() ?: 0L) * 1000L,
                size = parts.getOrNull(1)?.toLongOrNull() ?: 0L,
            )
        }

        private fun shellQuote(value: String): String {
            return "'" + value.replace("'", "'\\''") + "'"
        }

    }
}
