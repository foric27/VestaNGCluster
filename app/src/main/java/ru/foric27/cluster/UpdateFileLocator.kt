package ru.foric27.cluster

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.provider.DocumentsContract
import timber.log.Timber
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/**
 * Поиск кандидатов ICUpdate.zip / ICUpdate.zip.sig.
 *
 * Product-логика:
 * - внутренняя память проверяется только в корне /storage/emulated/0;
 * - USB-накопители проверяются только в корне выбранного тома;
 * - рекурсивный обход каталогов не используется.
 */
internal class UpdateFileLocator {

    enum class SearchPolicy {
        @Deprecated("Используйте USB_ONLY для runtime поиска обновлений")
        INTERNAL_ONLY,

        @Deprecated("Используйте USB_ONLY для runtime поиска обновлений")
        USB_FIRST,

        USB_ONLY,
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
        val ftpRootDir: File? = null,
    )

    internal data class FileScanRoot(
        val sourceKind: SourceKind,
        val directoryLabel: String,
        val directory: File,
    ) {
        val label: String = "${sourceKind.displayName}: $directoryLabel"
    }

    fun findCandidates(
        context: Context,
        searchPolicy: SearchPolicy,
    ): List<LocatedUpdatePair> {
        val candidates = ArrayList<LocatedUpdatePair>()
        candidates += findCandidatesInFileRoots(buildFileRoots(context, searchPolicy))
        buildSafRoots(context, searchPolicy).forEach { root ->
            inspectSafRoot(root)?.let { candidates += it }
        }
        return candidates.sortedWith(
            compareBy<LocatedUpdatePair> { it.sourceKind.priority }
                .thenByDescending { it.lastModified },
        )
    }

    internal fun findCandidatesInFileRoots(roots: List<FileScanRoot>): List<LocatedUpdatePair> {
        return roots.mapNotNull(::inspectFileRoot)
    }

    private fun buildSafRoots(
        context: Context,
        searchPolicy: SearchPolicy,
    ): List<ScanRoot> {
        val persistedTree = resolvePersistedTree(context) ?: return emptyList()
        when (searchPolicy) {
            SearchPolicy.INTERNAL_ONLY -> {
                if (persistedTree.sourceKind != SourceKind.INTERNAL) {
                    Timber.tag(TAG).i("Persisted SAF URI не указывает на внутреннюю память; INTERNAL_ONLY пропускает поиск: ${persistedTree.directoryLabel}")
                    return emptyList()
                }
            }
            SearchPolicy.USB_ONLY -> {
                if (persistedTree.sourceKind != SourceKind.USB) {
                    Timber.tag(TAG).i("Persisted SAF URI не указывает на USB; USB_ONLY пропускает поиск: ${persistedTree.directoryLabel}")
                    return emptyList()
                }
            }
            SearchPolicy.USB_FIRST -> { /* no filter */
            }
        }
        return listOf(persistedTree)
    }

    private fun buildFileRoots(
        context: Context,
        searchPolicy: SearchPolicy,
    ): List<FileScanRoot> {
        val internalRoot = FileScanRoot(
            sourceKind = SourceKind.INTERNAL,
            directoryLabel = INTERNAL_STORAGE_ROOT,
            directory = File(INTERNAL_STORAGE_ROOT),
        )
        val usbRoots = discoverUsbRoots(context).map { usbRoot ->
            FileScanRoot(
                sourceKind = SourceKind.USB,
                directoryLabel = usbRoot.absolutePath,
                directory = usbRoot,
            )
        }
        return when (searchPolicy) {
            SearchPolicy.INTERNAL_ONLY -> listOf(internalRoot)
            SearchPolicy.USB_FIRST -> usbRoots + internalRoot
            SearchPolicy.USB_ONLY -> usbRoots
        }
    }

    private fun resolvePersistedTree(context: Context): ScanRoot? {
        val persistedTreeUri = getPersistedTreeUri(context)
        if (persistedTreeUri == null) {
            Timber.tag(TAG).w("Persisted SAF URI отсутствует; поиск OTA-файлов пропущен")
            return null
        }

        if (!hasPersistedReadPermission(context, persistedTreeUri)) {
            Timber.tag(TAG).w("Persisted SAF URI потерял read permission; очищаю сохранённую ссылку")
            clearPersistedTreeUri(context)
            return null
        }

        val documentRoot = DocumentFile.fromTreeUri(context, persistedTreeUri)
        if (documentRoot == null || !documentRoot.exists() || !documentRoot.isDirectory) {
            Timber.tag(TAG).w("Persisted SAF URI недоступен или не является каталогом: $persistedTreeUri")
            return null
        }

        val treeDocumentId = runCatching { DocumentsContract.getTreeDocumentId(persistedTreeUri) }
            .onFailure { Timber.tag(TAG).w(it, "Не удалось получить treeDocumentId для $persistedTreeUri") }
            .getOrNull()
            ?: return null

        val rootDescriptor = parseRootDescriptor(treeDocumentId)
        if (!rootDescriptor.isStorageRoot) {
            Timber.tag(TAG).w("Persisted SAF URI должен указывать на корень тома, получен=$treeDocumentId")
            return null
        }

        val directoryLabel = buildDirectoryLabel(rootDescriptor)
        val sourceLabel = "${rootDescriptor.sourceKind.displayName}: $directoryLabel"
        return ScanRoot(
            sourceKind = rootDescriptor.sourceKind,
            label = sourceLabel,
            directoryLabel = directoryLabel,
            documentRoot = documentRoot,
        )
    }

    private fun inspectFileRoot(root: FileScanRoot): LocatedUpdatePair? {
        if (!root.directory.exists() || !root.directory.isDirectory) {
            logInfo("Файловый корень недоступен: ${root.directoryLabel}")
            return null
        }

        val children = root.directory.listFiles()?.filter { it.isFile }.orEmpty()
        val zip = children.firstOrNull { it.name == RuntimeConfig.UpdateFtp.UPDATE_ZIP_NAME }
        val sig = children.firstOrNull { it.name == RuntimeConfig.UpdateFtp.UPDATE_SIG_NAME }

        logInfo(
            "Проверяю файловый корень: ${root.directoryLabel}, " +
                "zip=${zip?.absolutePath}[exists=${zip != null},isFile=${zip?.isFile == true}], " +
                "sig=${sig?.absolutePath}[exists=${sig != null},isFile=${sig?.isFile == true}]",
        )

        if (zip == null || sig == null) return null

        val zipFile = FileSourceFile(zip)
        val sigFile = FileSourceFile(sig)
        logInfo(
            "Найдена пара обновления в файловом корне: zip=${zip.absolutePath}, sig=${sig.absolutePath}, источник=${root.label}",
        )
        return LocatedUpdatePair(
            sourceKind = root.sourceKind,
            sourceLabel = root.label,
            directoryLabel = root.directoryLabel,
            zipFile = zipFile,
            sigFile = sigFile,
            lastModified = maxOf(zipFile.lastModified, sigFile.lastModified),
            ftpRootDir = root.directory,
        )
    }

    private fun inspectSafRoot(root: ScanRoot): LocatedUpdatePair? {
        val children = root.documentRoot.listFiles().filter { it.isFile }
        val zip = children.firstOrNull { it.name == RuntimeConfig.UpdateFtp.UPDATE_ZIP_NAME }
        val sig = children.firstOrNull { it.name == RuntimeConfig.UpdateFtp.UPDATE_SIG_NAME }

        Timber.tag(TAG).i("Проверяю SAF-корень: ${root.directoryLabel}, " +
                "zip=${zip?.uri}[exists=${zip != null},isFile=${zip?.isFile == true}], " +
                "sig=${sig?.uri}[exists=${sig != null},isFile=${sig?.isFile == true}]",
        )

        if (zip == null || sig == null) return null

        val zipFile = DocumentSourceFile(zip)
        val sigFile = DocumentSourceFile(sig)
        Timber.tag(TAG).i("Найдена пара обновления в SAF-корне: zip=${zip.uri}, sig=${sig.uri}, источник=${root.label}",
        )
        return LocatedUpdatePair(
            sourceKind = root.sourceKind,
            sourceLabel = root.label,
            directoryLabel = root.directoryLabel,
            zipFile = zipFile,
            sigFile = sigFile,
            lastModified = maxOf(zipFile.lastModified, sigFile.lastModified),
        )
    }

    private data class ScanRoot(
        val sourceKind: SourceKind,
        val label: String,
        val directoryLabel: String,
        val documentRoot: DocumentFile,
    )

    private data class DocumentSourceFile(
        private val documentFile: DocumentFile,
    ) : UpdateSourceFile {
        override val name: String = documentFile.name.orEmpty()
        override val debugPath: String = documentFile.uri.toString()
        override val lastModified: Long = documentFile.lastModified()
        override val size: Long = documentFile.length()

        override fun openInputStream(context: Context): InputStream {
            return requireNotNull(context.contentResolver.openInputStream(documentFile.uri)) {
                "Не удалось открыть InputStream для ${documentFile.uri}"
            }
        }
    }

    private data class FileSourceFile(
        private val file: File,
    ) : UpdateSourceFile {
        override val name: String = file.name
        override val debugPath: String = file.absolutePath
        override val lastModified: Long = file.lastModified()
        override val size: Long = file.length()

        override fun openInputStream(context: Context): InputStream = FileInputStream(file)
    }

    private fun logInfo(message: String) {
        runCatching { Timber.tag(TAG).i(message) }
    }

    /**
     * Очищает persisted SAF URI, если он указывает на внутреннюю память.
     * Используется при миграции на USB-only режим.
     */
    fun clearPersistedInternalTreeUri(context: Context): Boolean {
        return clearPersistedInternalTreeUri(getPrefs(context))
    }

    internal fun clearPersistedInternalTreeUri(prefs: SharedPreferences): Boolean {
        val uri = getPersistedTreeUri(prefs) ?: return false
        val treeDocumentId = runCatching { DocumentsContract.getTreeDocumentId(uri) }
            .onFailure { Timber.tag(TAG).w(it, "Не удалось получить treeDocumentId для очистки URI") }
            .getOrNull() ?: return false
        val rootDescriptor = parseRootDescriptor(treeDocumentId)
        if (rootDescriptor.sourceKind != SourceKind.INTERNAL) return false
        Timber.tag(TAG).i("Очищаю persisted SAF URI для внутренней памяти при USB_ONLY режиме")
        return clearPersistedTreeUri(prefs, uri.toString())
    }

    companion object {
        private const val TAG = "UpdateFileLocator"
        private const val PREFS_NAME = "update_file_locator"
        private const val KEY_TREE_URI = "persisted_tree_uri"
        private const val INTERNAL_STORAGE_ROOT = "/storage/emulated/0"

        private data class RootDescriptor(
            val sourceKind: SourceKind,
            val volumeId: String,
            val relativePath: String,
            val isStorageRoot: Boolean,
        )

        private fun parseRootDescriptor(treeDocumentId: String): RootDescriptor {
            val separatorIndex = treeDocumentId.indexOf(':')
            val volumeId = if (separatorIndex >= 0) {
                treeDocumentId.substring(0, separatorIndex)
            } else {
                treeDocumentId
            }.trim()
            val relativePath = if (separatorIndex >= 0) {
                treeDocumentId.substring(separatorIndex + 1).trim('/').trim()
            } else {
                ""
            }
            val isInternal = volumeId.equals("primary", ignoreCase = true)
            return RootDescriptor(
                sourceKind = if (isInternal) SourceKind.INTERNAL else SourceKind.USB,
                volumeId = volumeId,
                relativePath = relativePath,
                isStorageRoot = relativePath.isBlank(),
            )
        }

        private fun buildDirectoryLabel(rootDescriptor: RootDescriptor): String {
            val basePath = if (rootDescriptor.sourceKind == SourceKind.INTERNAL) {
                INTERNAL_STORAGE_ROOT
            } else {
                "/storage/${rootDescriptor.volumeId}"
            }
            return if (rootDescriptor.relativePath.isBlank()) {
                basePath
            } else {
                "$basePath/${rootDescriptor.relativePath}"
            }
        }

        private fun discoverUsbRoots(context: Context): List<File> {
            return context.applicationContext.getExternalFilesDirs(null)
                .orEmpty()
                .mapNotNull(::resolveStorageVolumeRoot)
                .filter { root -> UsbStoragePathMatcher.isUsbStoragePath(root.absolutePath) }
                .distinctBy { root -> root.absolutePath }
                .sortedBy { root -> root.absolutePath }
        }

        private fun resolveStorageVolumeRoot(externalFilesDir: File?): File? {
            val path = externalFilesDir?.absoluteFile?.absolutePath ?: return null
            val androidDataMarker = "${File.separator}Android${File.separator}data"
            val markerIndex = path.indexOf(androidDataMarker)
            val rootPath = if (markerIndex > 0) path.substring(0, markerIndex) else path
            return File(rootPath)
        }

        private fun getPrefs(context: Context) =
            context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        fun persistGrantedTreeUri(
            context: Context,
            treeUri: Uri,
            flags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION,
        ): Boolean {
            val appContext = context.applicationContext
            return persistGrantedTreeUri(
                prefs = getPrefs(context),
                treeUriString = treeUri.toString(),
                flags = flags,
            ) { persistableFlags ->
                appContext.contentResolver.takePersistableUriPermission(treeUri, persistableFlags)
            }
        }

        internal fun persistGrantedTreeUri(
            prefs: SharedPreferences,
            treeUriString: String,
            flags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION,
            onTakePermission: (persistableFlags: Int) -> Unit = {},
        ): Boolean {
            return runCatching {
                val persistableFlags = flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                onTakePermission(persistableFlags)
                prefs.edit().putString(KEY_TREE_URI, treeUriString).commit()
            }.onFailure {
                Timber.tag(TAG).w(it, "Не удалось сохранить persisted SAF URI: $treeUriString")
            }.getOrDefault(false)
        }

        fun clearPersistedTreeUri(context: Context): Boolean {
            val appContext = context.applicationContext
            val treeUri = getPersistedTreeUri(appContext)
            return clearPersistedTreeUri(
                prefs = getPrefs(context),
                treeUriString = treeUri?.toString(),
            ) {
                treeUri?.let { uri ->
                    appContext.contentResolver.releasePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                    )
                }
            }
        }

        internal fun clearPersistedTreeUri(
            prefs: SharedPreferences,
            treeUriString: String?,
            onReleasePermission: (() -> Unit)? = null,
        ): Boolean {
            treeUriString?.let { rawUri ->
                runCatching {
                    onReleasePermission?.invoke()
                }.onFailure {
                    Timber.tag(TAG).w(it, "Не удалось освободить persisted SAF URI: $rawUri")
                }
            }
            return prefs.edit().remove(KEY_TREE_URI).commit()
        }

        fun getPersistedTreeUri(context: Context): Uri? = getPersistedTreeUri(getPrefs(context))

        internal fun getPersistedTreeUriString(prefs: SharedPreferences): String? {
            val rawUri = prefs.getString(KEY_TREE_URI, null)?.trim().orEmpty()
            return rawUri.takeIf { it.isNotBlank() }
        }

        internal fun getPersistedTreeUri(prefs: SharedPreferences): Uri? =
            getPersistedTreeUri(prefs, uriParser = Uri::parse)

        internal fun getPersistedTreeUri(
            prefs: SharedPreferences,
            uriParser: (String) -> Uri,
        ): Uri? {
            val rawUri = getPersistedTreeUriString(prefs) ?: return null
            return runCatching { uriParser(rawUri) }
                .onFailure {
                    Timber.tag(TAG).w(it, "Некорректный persisted SAF URI в SharedPreferences: $rawUri")
                }
                .getOrNull()
        }

        private fun hasPersistedReadPermission(context: Context, treeUri: Uri): Boolean {
            return context.applicationContext.contentResolver.persistedUriPermissions.any { permission ->
                permission.isReadPermission && permission.uri == treeUri
            }
        }
    }
}
