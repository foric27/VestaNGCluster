package ru.foric27.cluster

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.pm.PackageInfoCompat
import okhttp3.Response
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.security.MessageDigest
import java.util.Locale

internal object AppUpdateManager {

    private const val TAG = "AppUpdateManager"
    private const val REPO_OWNER = "foric27"
    private const val REPO_NAME = "VestaNGCluster"
    private const val API_BASE_URL = "https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/releases"
    private const val ROLLING_TAG = "main-latest"
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 60_000
    private const val UPDATE_DIR = "app-updates"
    private const val CHANNEL_MARKER_FILE = "channel.txt"
    private const val MIN_QUERY_INTERVAL_MS = 60_000L
    private const val MAX_BACKOFF_MS = 3_600_000L
    private const val SESSION_STATUS_REQUEST_CODE = 71_092

    private val okHttpClient by lazy {
        okhttp3.OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_MS.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)
            .readTimeout(READ_TIMEOUT_MS.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)
            .build()
    }

    @Volatile private var lastQueryAttemptMs = 0L
    @Volatile private var consecutiveErrors = 0

    sealed interface QueryResult {
        data class DownloadedReady(val update: DownloadedUpdate) : QueryResult
        data class RemoteAvailable(val release: RemoteRelease) : QueryResult
        data class UpToDate(val message: String) : QueryResult
        data class Error(val message: String) : QueryResult
    }

    sealed interface DownloadResult {
        data class Success(val update: DownloadedUpdate) : DownloadResult
        data class Error(val message: String) : DownloadResult
    }

    sealed interface InstallResult {
        object Started : InstallResult
        data class PermissionRequired(val intent: Intent) : InstallResult
        data class Error(val message: String) : InstallResult
    }

    data class RemoteRelease(
        val channel: AppSettings.UpdateChannel,
        val versionName: String,
        val versionCode: Long,
        val buildSha: String?,
        val apkFileName: String,
        val apkUrl: String,
        val checksumUrl: String?,
    )

    data class DownloadedUpdate(
        val apkFile: File,
        val checksumFile: File?,
        val versionName: String,
        val versionCode: Long,
        val buildSha: String?,
    )

    fun queryUpdate(context: Context, channel: AppSettings.UpdateChannel, force: Boolean = false): QueryResult {
        val currentVersionCode = getCurrentVersionCode(context)
        val currentBuildSha = currentBuildSha()
        getCachedUpdate(context, channel)?.let { cached ->
            if (AppUpdateVersionPolicy.isUpdateNewer(channel, cached.versionCode, cached.buildSha, currentVersionCode, currentBuildSha)) {
                return QueryResult.DownloadedReady(cached)
            }
            clearCachedFiles(cached)
        }

        val now = System.currentTimeMillis()
        if (!force) {
            val backoffMs = minOf(MAX_BACKOFF_MS, MIN_QUERY_INTERVAL_MS * (1L shl consecutiveErrors.coerceAtMost(10)))
            val elapsed = now - lastQueryAttemptMs
            if (elapsed < backoffMs) {
                Timber.tag(TAG).d("Пропускаю проверку обновления: cooldown ${backoffMs - elapsed}мс")
                return QueryResult.Error(context.getString(R.string.app_update_error_rate_limited))
            }
        }
        lastQueryAttemptMs = now

        val release = try {
            fetchRemoteRelease(context, channel)
        } catch (t: Throwable) {
            Timber.tag(TAG).w(t, "Не удалось проверить GitHub release")
            consecutiveErrors++
            return QueryResult.Error(errorMessage(context, t))
        }

        consecutiveErrors = 0

        if (!AppUpdateVersionPolicy.isUpdateNewer(channel, release.versionCode, release.buildSha, currentVersionCode, currentBuildSha)) {
            return QueryResult.UpToDate(
                context.getString(
                    R.string.app_update_up_to_date_fmt,
                    BuildConfig.VERSION_NAME,
                    currentVersionCode,
                ),
            )
        }

        return QueryResult.RemoteAvailable(release)
    }

    fun downloadUpdate(context: Context, release: RemoteRelease): DownloadResult {
        return try {
            val updateDir = File(context.cacheDir, UPDATE_DIR).apply { mkdirs() }
            cleanupUpdateDir(updateDir)
            val apkFile = File(updateDir, release.apkFileName)
            File(updateDir, CHANNEL_MARKER_FILE).writeText(release.channel.prefValue)
            downloadToFile(context, release.apkUrl, apkFile)
            val checksumFile = release.checksumUrl?.let { checksumUrl ->
                File(updateDir, "${release.apkFileName}.sha256").also {
                    downloadToFile(context, checksumUrl, it)
                }
            }

            verifyChecksum(context, apkFile, checksumFile)
            val downloaded = inspectDownloadedApk(context, apkFile, checksumFile)
            if (!AppUpdateVersionPolicy.isUpdateNewer(release.channel, downloaded.versionCode, downloaded.buildSha, getCurrentVersionCode(context), currentBuildSha())) {
                clearCachedFiles(downloaded)
                return DownloadResult.Error(context.getString(R.string.app_update_error_not_newer))
            }
            DownloadResult.Success(downloaded)
        } catch (t: Throwable) {
            Timber.tag(TAG).w(t, "Не удалось скачать APK обновления")
            DownloadResult.Error(errorMessage(context, t))
        }
    }

    fun requestInstall(context: Context, update: DownloadedUpdate): InstallResult {
        if (!update.apkFile.exists()) {
            return InstallResult.Error(context.getString(R.string.app_update_error_file_missing))
        }

        if (!isApkSignatureValid(context, update.apkFile)) {
            return InstallResult.Error(context.getString(R.string.app_update_error_signature_mismatch))
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            return InstallResult.PermissionRequired(intent)
        }

        return try {
            installWithPackageInstaller(context, update.apkFile)
            InstallResult.Started
        } catch (t: Throwable) {
            Timber.tag(TAG).w(t, "Не удалось запустить установщик APK")
            InstallResult.Error(errorMessage(context, t))
        }
    }

    private fun installWithPackageInstaller(context: Context, apkFile: File) {
        val packageInstaller = context.packageManager.packageInstaller
        val sessionParams = android.content.pm.PackageInstaller.SessionParams(
            android.content.pm.PackageInstaller.SessionParams.MODE_FULL_INSTALL,
        )
        val sessionId = packageInstaller.createSession(sessionParams)
        val session = packageInstaller.openSession(sessionId)
        try {
            val inputLength = apkFile.length()
            session.openWrite("app-update", 0, inputLength).use { outputStream ->
                apkFile.inputStream().use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        outputStream.write(buffer, 0, read)
                    }
                    outputStream.flush()
                }
            }

            val statusIntent = Intent(context, AppUpdateInstallReceiver::class.java).apply {
                action = AppUpdateInstallReceiver.ACTION_INSTALL_STATUS
                setPackage(context.packageName)
            }
            val statusFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_MUTABLE
            } else {
                android.app.PendingIntent.FLAG_UPDATE_CURRENT
            }
            val statusPendingIntent = android.app.PendingIntent.getBroadcast(
                context,
                SESSION_STATUS_REQUEST_CODE,
                statusIntent,
                statusFlags,
            )

            session.commit(statusPendingIntent.intentSender)
        } catch (t: Throwable) {
            runCatching { session.abandon() }
            throw t
        }
        session.close()
    }

    fun getCachedUpdate(context: Context, channel: AppSettings.UpdateChannel): DownloadedUpdate? {
        val updateDir = File(context.cacheDir, UPDATE_DIR)
        if (!updateDir.exists()) return null
        val cachedChannel = File(updateDir, CHANNEL_MARKER_FILE)
            .takeIf { it.exists() }
            ?.readText()
            ?.trim()
            ?.let(AppSettings.UpdateChannel::fromPref)
        if (cachedChannel != channel) {
            cleanupUpdateDir(updateDir)
            return null
        }
        val apkFile = updateDir.listFiles()?.firstOrNull { it.isFile && it.name.endsWith(".apk") } ?: return null
        val checksumFile = updateDir.listFiles()?.firstOrNull { it.isFile && it.name == "${apkFile.name}.sha256" }
        return runCatching {
            verifyChecksum(context, apkFile, checksumFile)
            inspectDownloadedApk(context, apkFile, checksumFile)
        }.getOrElse { error ->
            Timber.tag(TAG).w(error, context.getString(R.string.app_update_cached_update_invalid))
            apkFile.delete()
            checksumFile?.delete()
            null
        }
    }

    private fun fetchRemoteRelease(context: Context, channel: AppSettings.UpdateChannel): RemoteRelease {
        val releaseUrl = when (channel) {
            AppSettings.UpdateChannel.ROLLING -> "$API_BASE_URL/tags/$ROLLING_TAG"
            AppSettings.UpdateChannel.STABLE -> "$API_BASE_URL/latest"
        }
        val payload = readTextFromUrl(context, releaseUrl)
        val root = JSONObject(payload)
        val body = root.optString("body")
        val versionMetadata = VERSION_LINE_REGEX.find(body)
            ?: throw IllegalStateException(context.getString(R.string.app_update_error_release_notes_version_missing))
        val versionName = versionMetadata.groupValues[1]
        val versionCode = versionMetadata.groupValues[2].toLongOrNull()
            ?: throw IllegalStateException(context.getString(R.string.app_update_error_release_notes_version_code_invalid))
        val bodyBuildSha = AppUpdateReleaseParsing.parseBuildShaFromReleaseNotes(body)

        val assets = root.optJSONArray("assets")
            ?: throw IllegalStateException(context.getString(R.string.app_update_error_release_assets_missing))
        var apkUrl: String? = null
        var apkName: String? = null
        var checksumUrl: String? = null
        for (index in 0 until assets.length()) {
            val asset = assets.getJSONObject(index)
            val name = asset.optString("name")
            val url = asset.optString("browser_download_url")
            when {
                name.endsWith(".apk.sha256") -> checksumUrl = url
                name.endsWith(".apk") -> {
                    apkUrl = url
                    apkName = name
                }
            }
        }
        val safeApkUrl = apkUrl ?: throw IllegalStateException(context.getString(R.string.app_update_error_release_apk_missing))
        val safeApkName = apkName ?: throw IllegalStateException(context.getString(R.string.app_update_error_release_apk_name_missing))
        val buildSha = AppUpdateReleaseParsing.parseBuildShaFromApkName(safeApkName) ?: bodyBuildSha
        validateHttpsUrl(context, safeApkUrl)
        checksumUrl?.let { validateHttpsUrl(context, it) }
        return RemoteRelease(channel, versionName, versionCode, buildSha, safeApkName, safeApkUrl, checksumUrl)
    }

    private fun inspectDownloadedApk(
        context: Context,
        apkFile: File,
        checksumFile: File?,
    ): DownloadedUpdate {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
        val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageArchiveInfo(
                apkFile.absolutePath,
                PackageManager.PackageInfoFlags.of(flags.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageArchiveInfo(apkFile.absolutePath, flags)
        } ?: throw IllegalStateException(context.getString(R.string.app_update_error_apk_metadata))

        val packageName = packageInfo.packageName
        if (packageName != context.packageName) {
            throw SecurityException(
                context.getString(R.string.app_update_error_package_mismatch_fmt, packageName),
            )
        }

        if (!isApkSignatureMatching(context, packageInfo)) {
            throw SecurityException(context.getString(R.string.app_update_error_signature_mismatch))
        }

        val versionCode = PackageInfoCompat.getLongVersionCode(packageInfo)
        val versionName = packageInfo.versionName ?: context.getString(R.string.app_update_unknown_version)
        return DownloadedUpdate(
            apkFile,
            checksumFile,
            versionName,
            versionCode,
            AppUpdateReleaseParsing.parseBuildShaFromApkName(apkFile.name),
        )
    }

    private fun isApkSignatureValid(context: Context, apkFile: File): Boolean {
        return try {
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                PackageManager.GET_SIGNING_CERTIFICATES
            } else {
                @Suppress("DEPRECATION")
                PackageManager.GET_SIGNATURES
            }
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageArchiveInfo(
                    apkFile.absolutePath,
                    PackageManager.PackageInfoFlags.of(flags.toLong()),
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageArchiveInfo(apkFile.absolutePath, flags)
            } ?: return false

            isApkSignatureMatching(context, packageInfo)
        } catch (t: Throwable) {
            Timber.tag(TAG).w(t, "Не удалось проверить подпись APK")
            false
        }
    }

    private fun isApkSignatureMatching(context: Context, packageInfo: android.content.pm.PackageInfo): Boolean {
        val apkSignatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.signingInfo?.apkContentsSigners
        } else {
            @Suppress("DEPRECATION")
            packageInfo.signatures
        }

        if (apkSignatures.isNullOrEmpty()) {
            return false
        }

        val currentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
        val currentPackageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.PackageInfoFlags.of(currentFlags.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, currentFlags)
        }

        val currentSignatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            currentPackageInfo.signingInfo?.apkContentsSigners
        } else {
            @Suppress("DEPRECATION")
            currentPackageInfo.signatures
        }

        if (currentSignatures.isNullOrEmpty()) {
            return false
        }

        val apkBytes = apkSignatures.first().toByteArray()
        val currentBytes = currentSignatures.first().toByteArray()
        val apkDigest = MessageDigest.getInstance("SHA-256").digest(apkBytes)
        val currentDigest = MessageDigest.getInstance("SHA-256").digest(currentBytes)
        return MessageDigest.isEqual(apkDigest, currentDigest)
    }

    private fun getCurrentVersionCode(context: Context): Long {
        val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0)
        }
        return PackageInfoCompat.getLongVersionCode(packageInfo)
    }

    private fun downloadToFile(context: Context, url: String, targetFile: File) {
        validateHttpsUrl(context, url)
        val request = okhttp3.Request.Builder()
            .url(url)
            .addHeader("Accept", "application/octet-stream")
            .addHeader("User-Agent", "$REPO_NAME-app-update")
            .build()
        executeRequest(context, request).use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException(
                    context.getString(R.string.app_update_error_download_http_fmt, response.code),
                )
            }
            val body = response.body ?: throw IllegalStateException(context.getString(R.string.app_update_error_empty_response))
            body.byteStream().use { input ->
                targetFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        }
    }

    private fun readTextFromUrl(context: Context, url: String): String {
        validateHttpsUrl(context, url)
        val request = okhttp3.Request.Builder()
            .url(url)
            .addHeader("Accept", "application/vnd.github+json")
            .addHeader("User-Agent", "$REPO_NAME-app-update")
            .build()
        executeRequest(context, request).use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException(
                    context.getString(R.string.app_update_error_release_http_fmt, response.code),
                )
            }
            return response.body?.string()
                ?: throw IllegalStateException(context.getString(R.string.app_update_error_empty_response))
        }
    }

    private fun executeRequest(context: Context, request: okhttp3.Request): Response {
        return okHttpClient.newCall(request).execute()
    }

    private fun validateHttpsUrl(context: Context, url: String) {
        val parsed = Uri.parse(url)
        if (!parsed.scheme.equals("https", ignoreCase = true)) {
            throw SecurityException(context.getString(R.string.app_update_error_https_only))
        }
    }

    private fun verifyChecksum(context: Context, apkFile: File, checksumFile: File?) {
        if (checksumFile == null || !checksumFile.exists()) {
            throw IllegalStateException(context.getString(R.string.app_update_error_checksum_missing))
        }
        val expected = checksumFile.readText()
            .trim()
            .substringBefore(' ')
            .substringBefore('\t')
            .lowercase(Locale.US)
        val actual = sha256(apkFile)
        if (expected != actual) {
            throw SecurityException(context.getString(R.string.app_update_error_checksum_mismatch))
        }
    }

    private fun errorMessage(context: Context, error: Throwable): String {
        return error.message?.takeIf { it.isNotBlank() }
            ?: context.getString(R.string.app_update_error_generic)
    }

    private fun currentBuildSha(): String? {
        return AppUpdateVersionPolicy.normalizeBuildSha(BuildConfig.GIT_SHA)
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString(separator = "") { "%02x".format(Locale.US, it) }
    }

    private fun cleanupUpdateDir(updateDir: File) {
        updateDir.listFiles()?.forEach { child ->
            if (child.isFile) child.delete()
        }
    }

    private fun clearCachedFiles(update: DownloadedUpdate) {
        update.apkFile.delete()
        update.checksumFile?.delete()
    }

    private val VERSION_LINE_REGEX = Regex("""\*\*Version:\*\*\s*`([^`]+)`\s*\(`([^`]+)`\)""")
}
