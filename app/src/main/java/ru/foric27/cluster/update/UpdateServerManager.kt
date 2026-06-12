package ru.foric27.cluster.update
import ru.foric27.cluster.R
import ru.foric27.cluster.util.*

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import timber.log.Timber
import androidx.annotation.StringRes
import java.util.concurrent.atomic.AtomicReference

/**
 * Полный цикл подготовки и запуска FTP-сервера обновления.
 */
internal object UpdateServerManager {

    enum class Status {
        STOPPED,
        PREPARING,
        RUNNING,
        ERROR,
    }

    data class PreparedFileInfo(
        val path: String,
        val size: Long,
        val sha256: String,
    )

    data class State(
        val status: Status,
        val message: String,
        val fileInfo: PreparedFileInfo? = null,
        val boundAddress: EmbeddedFtpServerFactory.BoundAddress? = null,
        val retrySuggested: Boolean = false,
        val detectedLocation: String? = null,
        val sourceFilePath: String? = null,
    )

    data class Result(
        val success: Boolean,
        val message: String,
        val fileInfo: PreparedFileInfo? = null,
        val boundAddress: EmbeddedFtpServerFactory.BoundAddress? = null,
        val retrySuggested: Boolean = false,
        val detectedLocation: String? = null,
        val sourceFilePath: String? = null,
    )

    private const val TAG = "UpdateServerManager"
    private const val FTP_START_ATTEMPTS = 2
    private const val FTP_START_RETRY_DELAY_MS = 200L

    private val lock = Any()
    private val locator = UpdateFileLocator()
    private val verifier = Sha256Verifier()
    private val ftpFactory = EmbeddedFtpServerFactory()
    private val currentState = AtomicReference(State(Status.STOPPED, "FTP-сервер не запущен"))

    @Volatile private var appContext: Context? = null
    @Volatile private var runningServer: EmbeddedFtpServerFactory.RunningServer? = null
    @Volatile private var lastSearchPolicy: UpdateFileLocator.SearchPolicy = UpdateFileLocator.SearchPolicy.USB_ONLY
    @Volatile private var preparedSourceKind: UpdateFileLocator.SourceKind? = null
    @Volatile private var preparedSourceDirectory: String? = null
    @Volatile private var runningZipPath: String? = null
    @Volatile private var runningZipLastModified: Long = 0L
    @Volatile private var runningZipSize: Long = 0L
    @Volatile private var runningZipSha256: String? = null
    @Volatile private var lastDetectedLocation: String? = null
    @Volatile private var lastDetectedFilePath: String? = null
    @Volatile private var lastPrepareAttemptMs: Long = 0L
    @Volatile private var lastPrepareAttemptPolicy: UpdateFileLocator.SearchPolicy? = null
    @Volatile private var lastPrepareAttemptFailed: Boolean = false
    @Volatile private var lastAccessDeniedLogMessage: String? = null

    fun prepareAndStartServer(
        context: Context,
        searchPolicy: UpdateFileLocator.SearchPolicy = UpdateFileLocator.SearchPolicy.USB_ONLY,
    ): Result {
        val startedAtMs = SystemClock.elapsedRealtime()
        val outcome = synchronized(lock) {
            val applicationContext = context.applicationContext
            appContext = applicationContext
            lastSearchPolicy = searchPolicy
            if (shouldSkipRepeatedPrepareLocked(startedAtMs, searchPolicy)) {
                val message = "Повторная подготовка FTP пропущена: предыдущее неуспешное USB_ONLY-сканирование ещё актуально"
                Timber.tag(TAG).i(message)
                return@synchronized ResolveStartOutcome(
                    result = failState(
                        message = message,
                        retrySuggested = false,
                        detectedLocation = lastDetectedLocation,
                        sourceFilePath = lastDetectedFilePath,
                    ),
                )
            }
            locator.clearPersistedInternalTreeUri(applicationContext)
            val previousState = currentState.get()
            setState(
                State(
                    status = Status.PREPARING,
                    message = buildPreparingMessage(searchPolicy),
                    detectedLocation = lastDetectedLocation,
                    sourceFilePath = lastDetectedFilePath,
                ),
            )
            resolveAndStartLocked(
                context = applicationContext,
                searchPolicy = searchPolicy,
                accessDeniedLogMessage = "Нет MANAGE_EXTERNAL_STORAGE, запуск FTP обновления отложен",
                failureLogMessage = "Ошибка запуска FTP-сервера обновления",
                samePreparedHandler = { validatedPair ->
                    if (previousState.status == Status.RUNNING && isSamePreparedUpdateLocked(validatedPair)) {
                        Timber.tag(TAG).i("Повторный запуск FTP не требуется: уже активен тот же пакет обновления")
                        setState(previousState)
                        ResolveStartOutcome(resultFromState(previousState))
                    } else {
                        null
                    }
                },
                runningServerHandler = {
                    if (runningServer != null && previousState.status == Status.RUNNING) {
                        Timber.tag(TAG).i("FTP уже активен; откладываю переключение источника обновления, чтобы не сбить рабочий listener")
                        setState(previousState)
                        ResolveStartOutcome(resultFromState(previousState))
                    } else {
                        null
                    }
                },
            )
        }
        outcome.serverToStop?.let(::performStop)
        val durationMs = SystemClock.elapsedRealtime() - startedAtMs
        val result = outcome.result
        synchronized(lock) {
            recordPrepareAttemptLocked(startedAtMs, searchPolicy, result)
        }
        if (result.success) {
            Timber.tag(TAG).i("FTP READY за ${durationMs}мс, source=${result.sourceFilePath}")
        } else {
            Timber.tag(TAG).w("FTP не готов за ${durationMs}мс: ${result.message}")
        }
        return result
    }

    fun stopServer() {
        val server = synchronized(lock) {
            val s = stopServerLocked(clearPrepared = true)
            setState(State(Status.STOPPED, str(R.string.update_server_stopped), detectedLocation = lastDetectedLocation, sourceFilePath = lastDetectedFilePath))
            s
        }
        server?.let { performStop(it) }
    }

    fun restartServer(): Result {
        val context = appContext ?: return failState("Контекст приложения ещё не инициализирован")
        return prepareAndStartServer(context, lastSearchPolicy)
    }

    fun replaceAndStartServer(
        context: Context,
        searchPolicy: UpdateFileLocator.SearchPolicy,
        clearDetection: Boolean = false,
    ): Result {
        val server = synchronized(lock) {
            appContext = context.applicationContext
            val stoppedServer = stopServerLocked(clearPrepared = true)
            if (clearDetection) {
                lastDetectedLocation = null
                lastDetectedFilePath = null
                clearPrepareCooldownLocked()
            }
            setState(
                State(
                    status = Status.STOPPED,
                    message = str(R.string.update_server_stopped),
                    detectedLocation = lastDetectedLocation,
                    sourceFilePath = lastDetectedFilePath,
                ),
            )
            stoppedServer
        }
        server?.let {
            performStop(it)
            Thread.sleep(FTP_START_RETRY_DELAY_MS)
        }
        return prepareAndStartServer(context, searchPolicy)
    }

    fun handleUsbInserted(context: Context): Result {
        return prepareAndStartServer(context, UpdateFileLocator.SearchPolicy.USB_ONLY)
    }

    fun handleUsbRemoved(context: Context): Result {
        return prepareAndStartServer(context, UpdateFileLocator.SearchPolicy.USB_ONLY)
    }

    @Deprecated("Периодический опрос удалён в пользу event-driven обнаружения USB")
    fun pollAvailableStorage(context: Context): Result {
        return prepareAndStartServer(context, UpdateFileLocator.SearchPolicy.USB_ONLY)
    }

    fun getServerState(): State = currentState.get()

    fun getPreparedFileInfo(): PreparedFileInfo? = currentState.get().fileInfo

    fun getBoundAddress(): EmbeddedFtpServerFactory.BoundAddress? = currentState.get().boundAddress

    private fun startValidatedPairLocked(
        context: Context,
        searchPolicy: UpdateFileLocator.SearchPolicy,
        validatedPair: ValidatedPair,
    ): Result {
        lastSearchPolicy = searchPolicy

        val ftpRootDir = validatedPair.pair.ftpRootDir
        if (ftpRootDir == null) {
            val message = "Источник обновления не поддерживает прямой FTP-root: ${validatedPair.pair.directoryLabel}"
            Timber.tag(TAG).w(message)
            return failState(
                message = message,
                detectedLocation = validatedPair.pair.directoryLabel,
                sourceFilePath = validatedPair.pair.zipFile.debugPath,
            )
        }

        Timber.tag(TAG).i("Проверка SHA-256 успешна: ${validatedPair.verification.actualSha256}")
        val server = startFtpServerWithCleanup(FtpServerConfig.fromProject(), ftpRootDir)

        runningServer = server
        preparedSourceKind = validatedPair.pair.sourceKind
        preparedSourceDirectory = validatedPair.pair.directoryLabel
        runningZipPath = validatedPair.pair.zipFile.debugPath
        runningZipLastModified = validatedPair.pair.zipFile.lastModified
        runningZipSize = validatedPair.pair.zipFile.size
        runningZipSha256 = validatedPair.verification.actualSha256
        lastDetectedLocation = validatedPair.pair.directoryLabel
        lastDetectedFilePath = validatedPair.pair.zipFile.debugPath

        val fileInfo = PreparedFileInfo(
            path = validatedPair.pair.zipFile.debugPath,
            size = validatedPair.pair.zipFile.size,
            sha256 = validatedPair.verification.actualSha256,
        )

        val sourceText = "${validatedPair.pair.sourceKind.displayName}: ${validatedPair.pair.directoryLabel}"
        val message = str(
            R.string.update_server_started_fmt,
            server.boundAddress.host,
            server.boundAddress.port,
            sourceText,
        )
        Timber.tag(TAG).i(message)
        val successState = State(
            status = Status.RUNNING,
            message = message,
            fileInfo = fileInfo,
            boundAddress = server.boundAddress,
            detectedLocation = validatedPair.pair.directoryLabel,
            sourceFilePath = validatedPair.pair.zipFile.debugPath,
        )
        setState(successState)
        return Result(
            success = true,
            message = message,
            fileInfo = fileInfo,
            boundAddress = server.boundAddress,
            retrySuggested = false,
            detectedLocation = validatedPair.pair.directoryLabel,
            sourceFilePath = validatedPair.pair.zipFile.debugPath,
        )
    }

    private fun startFtpServerWithCleanup(
        config: FtpServerConfig,
        rootDir: java.io.File,
    ): EmbeddedFtpServerFactory.RunningServer {
        var firstFailure: Throwable? = null
        repeat(FTP_START_ATTEMPTS) { attemptIndex ->
            val server = ftpFactory.create(
                config = config,
                ftpRoot = rootDir,
                onTransferError = { errorMsg ->
                    Timber.tag(TAG).w("Ошибка передачи FTP: $errorMsg")
                    synchronized(lock) {
                        if (runningServer != null) {
                            runningServer = null
                            val errorState = State(
                                status = Status.ERROR,
                                message = errorMsg,
                                detectedLocation = lastDetectedLocation,
                                sourceFilePath = lastDetectedFilePath,
                                retrySuggested = true,
                            )
                            setState(errorState)
                        }
                    }
                },
            )
            try {
                server.ftpServer.start()
                if (attemptIndex > 0) {
                    Timber.tag(TAG).i("FTP-сервер запущен после повторной очистки порта")
                }
                return server
            } catch (t: Throwable) {
                if (firstFailure == null) firstFailure = t
                runCatching { server.ftpServer.stop() }
                    .onFailure { stopError -> Timber.tag(TAG).w(stopError, "Не удалось закрыть FTP-сервер после ошибки старта") }
                if (attemptIndex + 1 < FTP_START_ATTEMPTS && isTransientFtpStartError(t)) {
                    Timber.tag(TAG).w(t, "Повторяю запуск FTP после очистки частично открытого listener")
                    Thread.sleep(FTP_START_RETRY_DELAY_MS)
                } else {
                    throw t
                }
            }
        }
        throw firstFailure ?: IllegalStateException("FTP-сервер не запущен")
    }

    private fun resultFromState(state: State): Result {
        return Result(
            success = state.status == Status.RUNNING,
            message = state.message,
            fileInfo = state.fileInfo,
            boundAddress = state.boundAddress,
            retrySuggested = state.retrySuggested,
            detectedLocation = state.detectedLocation,
            sourceFilePath = state.sourceFilePath,
        )
    }

    private fun locateFirstValidPair(
        context: Context,
        searchPolicy: UpdateFileLocator.SearchPolicy,
    ): CandidateSearchResult {
        val candidates = locator.findCandidates(context, searchPolicy)
        if (candidates.isEmpty()) {
            Timber.tag(TAG).w("Кандидаты обновления не найдены")
            lastDetectedLocation = null
            lastDetectedFilePath = null
            return CandidateSearchResult(validatedPair = null, rejectionMessages = emptyList(), detectedLocation = null, sourceFilePath = null)
        }

        val rejectionMessages = ArrayList<String>()
        lastDetectedLocation = candidates.firstOrNull()?.directoryLabel
        lastDetectedFilePath = candidates.firstOrNull()?.zipFile?.debugPath
        candidates.forEach { candidate ->
            Timber.tag(TAG).i("Проверяю кандидата: источник=${candidate.sourceKind.displayName}, каталог=${candidate.directoryLabel}, zip=${candidate.zipFile.debugPath}, sig=${candidate.sigFile.debugPath}",
            )
            val verification = verifier.verify(context, candidate.zipFile, candidate.sigFile)
            if (verification.valid) {
                Timber.tag(TAG).i("Выбран источник ${candidate.sourceKind.displayName}: ${candidate.directoryLabel}")
                Timber.tag(TAG).i("SHA-256 совпал: ${verification.actualSha256}")
                return CandidateSearchResult(
                    validatedPair = ValidatedPair(candidate, verification),
                    rejectionMessages = rejectionMessages,
                    detectedLocation = candidate.directoryLabel,
                    sourceFilePath = candidate.zipFile.debugPath,
                )
            }
            val rejectionMessage = buildRejectionMessage(candidate, verification)
            rejectionMessages += rejectionMessage
            Timber.tag(TAG).w(rejectionMessage)
        }
        return CandidateSearchResult(
            validatedPair = null,
            rejectionMessages = rejectionMessages,
            detectedLocation = candidates.firstOrNull()?.directoryLabel,
            sourceFilePath = candidates.firstOrNull()?.zipFile?.debugPath,
        )
    }

    private data class ValidatedPair(
        val pair: UpdateFileLocator.LocatedUpdatePair,
        val verification: Sha256Verifier.VerificationResult,
    )

    private data class CandidateSearchResult(
        val validatedPair: ValidatedPair?,
        val rejectionMessages: List<String>,
        val detectedLocation: String?,
        val sourceFilePath: String?,
    )

    private data class ResolveStartOutcome(
        val result: Result,
        val serverToStop: EmbeddedFtpServerFactory.RunningServer? = null,
    )

    private fun buildRejectionMessage(
        candidate: UpdateFileLocator.LocatedUpdatePair,
        verification: Sha256Verifier.VerificationResult,
    ): String {
        return str(
            R.string.update_server_rejection_fmt,
            candidate.directoryLabel,
            verification.details,
            verification.expectedSha256,
            verification.actualSha256,
        )
    }

    private fun buildNoValidPairMessage(rejectionMessages: List<String>): String {
        if (rejectionMessages.isEmpty()) {
            return str(R.string.update_server_no_valid_pair)
        }
        return buildString {
            append(str(R.string.update_server_no_valid_pair)).append('.')
            rejectionMessages.forEach { rejectionMessage ->
                append('\n')
                append(rejectionMessage)
            }
        }
    }

    private fun resolveAndStartLocked(
        context: Context,
        searchPolicy: UpdateFileLocator.SearchPolicy,
        accessDeniedLogMessage: String? = null,
        failureLogMessage: String,
        samePreparedHandler: (ValidatedPair) -> ResolveStartOutcome?,
        runningServerHandler: () -> ResolveStartOutcome?,
        beforeStart: (ValidatedPair) -> Unit = {},
    ): ResolveStartOutcome {
        if (!StorageAccessManager.isAllFilesAccessGranted()) {
            accessDeniedLogMessage?.let { logAccessDeniedOnce(it) }
            val serverToStop = stopServerLocked(clearPrepared = true)
            return ResolveStartOutcome(
                result = failState(
                    message = StorageAccessManager.buildMissingAccessMessage(context),
                    detectedLocation = lastDetectedLocation,
                    sourceFilePath = lastDetectedFilePath,
                ),
                serverToStop = serverToStop,
            )
        }

        return {
            val searchResult = locateFirstValidPair(context, searchPolicy)
            lastDetectedLocation = searchResult.detectedLocation
            lastDetectedFilePath = searchResult.sourceFilePath
            val validatedPair = searchResult.validatedPair

            if (validatedPair == null) {
                ResolveStartOutcome(
                    result = failState(
                        message = buildNoValidPairMessage(searchResult.rejectionMessages),
                        detectedLocation = searchResult.detectedLocation,
                        sourceFilePath = searchResult.sourceFilePath,
                    ),
                    serverToStop = stopServerLocked(clearPrepared = true),
                )
            } else {
                samePreparedHandler(validatedPair)
                    ?: runningServerHandler()
                    ?: run {
                        beforeStart(validatedPair)
                        val serverToStop = stopServerLocked(clearPrepared = true)
                        ResolveStartOutcome(
                            result = startValidatedPairLocked(context, searchPolicy, validatedPair),
                            serverToStop = serverToStop,
                        )
                    }
            }
        }.runCatchingTimber(TAG, failureLogMessage).getOrElse { error ->
            val retrySuggested = isTransientFtpStartError(error)
            ResolveStartOutcome(
                result = failState(
                    message = buildStartFailureMessage(error, retrySuggested),
                    retrySuggested = retrySuggested,
                    detectedLocation = lastDetectedLocation,
                    sourceFilePath = lastDetectedFilePath,
                ),
                serverToStop = stopServerLocked(clearPrepared = true),
            )
        }
    }

    private fun logAccessDeniedOnce(message: String) {
        if (message == lastAccessDeniedLogMessage) return
        lastAccessDeniedLogMessage = message
        Timber.tag(TAG).i(message)
    }

    private fun isSamePreparedUpdateLocked(validatedPair: ValidatedPair): Boolean {
        return runningServer != null &&
            preparedSourceKind == validatedPair.pair.sourceKind &&
            preparedSourceDirectory == validatedPair.pair.directoryLabel &&
            runningZipPath == validatedPair.pair.zipFile.debugPath &&
            runningZipLastModified == validatedPair.pair.zipFile.lastModified &&
            runningZipSize == validatedPair.pair.zipFile.size &&
            runningZipSha256 == validatedPair.verification.actualSha256
    }

    private fun stopServerLocked(clearPrepared: Boolean): EmbeddedFtpServerFactory.RunningServer? {
        val server = runningServer
        runningServer = null
        preparedSourceKind = null
        preparedSourceDirectory = null

        if (clearPrepared) {
            runningZipPath = null
            runningZipLastModified = 0L
            runningZipSize = 0L
            runningZipSha256 = null
        }
        return server
    }

    private fun performStop(server: EmbeddedFtpServerFactory.RunningServer) {
        try {
            server.ftpServer.stop()
            Timber.tag(TAG).i("FTP-сервер остановлен")
        } catch (_: UnsupportedOperationException) {
            Timber.tag(TAG).i("FTP-сервер завершился с известной ошибкой dispose; очищаю состояние без повторного шума")
        } catch (t: Throwable) {
            Timber.tag(TAG).w(t, "Ошибка остановки FTP-сервера")
        }
    }

    private fun buildPreparingMessage(searchPolicy: UpdateFileLocator.SearchPolicy): String {
        return str(R.string.update_server_preparing_usb_only)
    }

    private fun failState(
        message: String,
        retrySuggested: Boolean = false,
        detectedLocation: String? = null,
        sourceFilePath: String? = null,
    ): Result {
        val result = Result(
            success = false,
            message = message,
            retrySuggested = retrySuggested,
            detectedLocation = detectedLocation,
            sourceFilePath = sourceFilePath,
        )
        setState(
            State(
                status = Status.ERROR,
                message = message,
                retrySuggested = retrySuggested,
                detectedLocation = detectedLocation,
                sourceFilePath = sourceFilePath,
            ),
        )
        return result
    }

    private fun shouldSkipRepeatedPrepareLocked(
        nowMs: Long,
        searchPolicy: UpdateFileLocator.SearchPolicy,
    ): Boolean {
        if (!lastPrepareAttemptFailed) return false
        if (lastPrepareAttemptPolicy != searchPolicy) return false
        if (searchPolicy != UpdateFileLocator.SearchPolicy.USB_ONLY) return false
        val previousDetectedPath = lastDetectedFilePath
        if (!previousDetectedPath.isNullOrBlank()) return false
        return nowMs - lastPrepareAttemptMs < PREPARE_RETRY_COOLDOWN_MS
    }

    private fun recordPrepareAttemptLocked(
        nowMs: Long,
        searchPolicy: UpdateFileLocator.SearchPolicy,
        result: Result,
    ) {
        lastPrepareAttemptMs = nowMs
        lastPrepareAttemptPolicy = searchPolicy
        lastPrepareAttemptFailed = !result.success
    }

    private fun clearPrepareCooldownLocked() {
        lastPrepareAttemptMs = 0L
        lastPrepareAttemptPolicy = null
        lastPrepareAttemptFailed = false
    }

    private fun setState(state: State) {
        currentState.also { reference ->
            reference.set(state)
            appContext?.let { context ->
                runCatching {
                    context.sendBroadcast(
                        Intent(ACTION_UPDATE_SERVER_STATE_CHANGED).setPackage(context.packageName),
                    )
                }.onFailure { error ->
                    Timber.tag(TAG).e(error, "Не удалось отправить состояние FTP в UI")
                }
            }
        }
    }

    private fun buildStartFailureMessage(t: Throwable, retrySuggested: Boolean): String {
        val details = (t.message ?: t.javaClass.simpleName).orEmpty().trim()
        if (!retrySuggested) {
            return details.ifBlank { str(R.string.update_server_start_failed) }
        }
        return if (details.isBlank()) {
            str(R.string.update_server_start_failed_retry)
        } else {
            str(R.string.update_server_start_failed_retry_details_fmt, details)
        }
    }

    private fun str(@StringRes resId: Int, vararg args: Any?): String {
        val context = appContext ?: return fallbackString(resId, args)
        return if (args.isEmpty()) context.getString(resId) else context.getString(resId, *args)
    }

    private fun fallbackString(@StringRes resId: Int, args: Array<out Any?>): String {
        return when (resId) {
            R.string.update_server_not_running -> "FTP-сервер не запущен"
            R.string.update_server_stopped -> "FTP-сервер остановлен"
            R.string.update_server_context_not_initialized -> "Контекст приложения ещё не инициализирован"
            R.string.update_server_preparing_usb_only -> "Подготовка FTP-сервера обновления: поиск на USB"
            R.string.update_server_started_fmt -> "FTP-сервер обновления запущен: ${args[0]}:${args[1]}; источник ${args[2]}"
            R.string.update_server_poll_new_update_fmt -> "Периодический опрос обнаружил новое или изменённое обновление: ${args[0]}"
            R.string.update_server_rejection_fmt -> "Кандидат отклонён: ${args[0]}. ${args[1]}. expected=${args[2]}, actual=${args[3]}"
            R.string.update_server_no_valid_pair -> "Валидная пара ICUpdate.zip и ICUpdate.zip.sig не найдена"
            R.string.update_server_start_failed -> "FTP-сервер обновления не запущен"
            R.string.update_server_start_failed_retry -> "FTP-сервер обновления не запущен: сеть ещё не готова, будет повторная попытка запуска"
            R.string.update_server_start_failed_retry_details_fmt -> "FTP-сервер обновления не запущен: ${args[0]}. Будет повторная попытка запуска"
            else -> resId.toString()
        }
    }

    private fun isTransientFtpStartError(error: Throwable): Boolean {
        return generateSequence(error) { it.cause }.any { current ->
            when (current) {
                is java.net.BindException,
                is java.net.SocketException,
                is org.apache.ftpserver.ftplet.FtpException -> return@any true
            }
            val message = current.message?.lowercase().orEmpty()
            message.contains("не найден ipv4") ||
                message.contains("cannot assign requested address") ||
                message.contains("failed to bind") ||
                message.contains("bind") ||
                message.contains("address already in use")
        }
    }

    private fun <T> (() -> T).runCatchingTimber(
        tag: String,
        failureMessage: String,
    ): kotlin.Result<T> {
        return runCatching(this).onFailure { error ->
            Timber.tag(tag).e(error, failureMessage)
        }
    }

    const val ACTION_UPDATE_SERVER_STATE_CHANGED = "ru.foric27.cluster.action.UPDATE_SERVER_STATE_CHANGED"
    private const val PREPARE_RETRY_COOLDOWN_MS = 2_000L
}
