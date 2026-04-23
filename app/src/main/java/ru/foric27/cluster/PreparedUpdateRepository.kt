package ru.foric27.cluster

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * Готовит изолированный FTP-root, в который копируется только валидная пара
 * файлов обновления.
 */
internal class PreparedUpdateRepository {

    data class PreparedFileInfo(
        val path: String,
        val size: Long,
        val sha256: String,
    )

    data class PreparedUpdate(
        val rootDir: File,
        val zipFile: File,
        val sigFile: File,
        val info: PreparedFileInfo,
    )

    fun prepare(
        context: Context,
        pair: UpdateFileLocator.LocatedUpdatePair,
        sha256: String,
    ): PreparedUpdate {
        val rootDir = resolveRootDir(context)
        recreateRootDir(rootDir)

        val preparedZip = File(rootDir, RuntimeConfig.UpdateFtp.UPDATE_ZIP_NAME)
        val preparedSig = File(rootDir, RuntimeConfig.UpdateFtp.UPDATE_SIG_NAME)

        copyToFile(context, pair.zipFile, preparedZip)
        copyToFile(context, pair.sigFile, preparedSig)

        return PreparedUpdate(
            rootDir = rootDir,
            zipFile = preparedZip,
            sigFile = preparedSig,
            info = PreparedFileInfo(
                path = preparedZip.absolutePath,
                size = preparedZip.length(),
                sha256 = sha256,
            ),
        )
    }

    fun clear(context: Context) {
        val rootDir = resolveRootDir(context)
        if (rootDir.exists()) {
            rootDir.deleteRecursively()
        }
    }

    private fun resolveRootDir(context: Context): File {
        val externalBase = context.getExternalFilesDir(null)
        return if (externalBase != null) {
            File(externalBase, RuntimeConfig.UpdateFtp.ROOT_DIR_NAME)
        } else {
            File(context.filesDir, RuntimeConfig.UpdateFtp.ROOT_DIR_NAME)
        }
    }

    private fun recreateRootDir(rootDir: File) {
        if (rootDir.exists() && !rootDir.deleteRecursively()) {
            throw IllegalStateException("Не удалось очистить рабочую директорию FTP: ${rootDir.absolutePath}")
        }
        if (!rootDir.mkdirs() && !rootDir.exists()) {
            throw IllegalStateException("Не удалось создать рабочую директорию FTP: ${rootDir.absolutePath}")
        }
    }

    private fun copyToFile(
        context: Context,
        source: UpdateFileLocator.UpdateSourceFile,
        destination: File,
    ) {
        Log.i(TAG, "Копирую ${source.debugPath} -> ${destination.absolutePath}")
        source.openInputStream(context).use { input ->
            FileOutputStream(destination).use { output ->
                input.copyTo(output)
                output.fd.sync()
            }
        }
    }

    private companion object {
        private const val TAG = "PreparedUpdateRepo"
    }
}
