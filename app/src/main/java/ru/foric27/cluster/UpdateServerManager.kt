package ru.foric27.cluster

import android.content.Context
import android.util.Log
import androidx.annotation.StringRes
import java.util.concurrent.atomic.AtomicReference

/**
 * Полный цикл подготовки и запуска FTP-сервера обновления.
 */
object UpdateServerManager {

    enum class Status {
        STOPPED,
        PREPARING,
        RUNNING,
        ERROR,
    }

    data class State(
        val status: Status,
        val message: String,
        val fileInfo: PreparedUpdateRepository.PreparedFileInfo? = null,
        val boundAddress: EmbeddedFtpServerFactory.BoundAddress? = null,
        val retrySuggested: Boolean = false,
        val detectedLocation: String? = null,
    )

    data class Result(
        val success: Boolean,
        val message: String,
        val fileInfo: PreparedUpdateRepository.PreparedFileInfo? = null,
        val boundAddress: EmbeddedFtpServerFactory.BoundAddress? = null,
        val retrySuggested: Boolean = false,
        val detectedLocation: String? = null,
    )

    private const val TAG = "UpdateServerManager"

    private val lock = Any()
    private val locator = UpdateFileLocator()
    private val verifier = Sha256Verifier()
    private val repository = PreparedUpdateRepository()
    private val ftpFactory = EmbeddedFtpServerFactory()
    private val currentState = AtomicReference(State(Status.STOPPED, "FTP-сервер не запущен"))

    @Volatile private var appContext: Context? = null
    @Volatile private var preparedUpdate: PreparedUpdateRepository.PreparedUpdate? = null
    @Volatile private var runningServer: EmbeddedFtpServerFactory.RunningServer? = null
    @Volatile private var lastSearchPolicy: UpdateFileLocator.SearchPolicy = UpdateFileLocator.SearchPolicy.INTERNAL_ONLY
    @Volatile private var preparedSourceKind: UpdateFileLocator.SourceKind? = null
    @Volatile private var preparedSourceDirectory: String? = null
    @Volatile private var lastDetectedLocation: String? = null

    fun prepareAndStartServer(
        context: Context,
        searchPolicy: UpdateFileLocator.SearchPolicy = UpdateFileLocator.SearchPolicy.INTERNAL_ONLY,
    ): Result {
        synchronized(lock) {
            val applicationContext = context.applicationContext
            appContext = applicationContext
            lastSearchPolicy = searchPolicy
            currentState.set(State(Status.PREPARING, buildPreparingMessage(searchPolicy), detectedLocation = lastDetectedLocation))

            if (!StorageAccessManager.isAllFilesAccessGranted()) {
                Log.w(TAG, "Нет MANAGE_EXTERNAL_STORAGE, запуск FTP обновления отложен")
                return stopServerAndFailLocked(
                    message = StorageAccessManager.buildMissingAccessMessage(applicationContext),
                    detectedLocation = lastDetectedLocation,
                )
            }

            return try {
                val searchResult = locateFirstValidPair(applicationContext, searchPolicy)
                lastDetectedLocation = searchResult.detectedLocation
                val validatedPair = searchResult.validatedPair ?: run {
                    return stopServerAndFailLocked(
                        buildNoValidPairMessage(searchResult.rejectionMessages),
                        detectedLocation = searchResult.detectedLocation,
                    )
                }

                if (isSamePreparedUpdateLocked(validatedPair)) {
                    val state = currentState.get()
                    if (state.status == Status.RUNNING) {
                        Log.i(TAG, "Повторный запуск FTP не требуется: уже активен тот же пакет обновления")
                        return resultFromState(state)
                    }
                }

                reloadValidatedPairLocked(applicationContext, searchPolicy, validatedPair)
            } catch (t: Throwable) {
                Log.e(TAG, "Ошибка запуска FTP-сервера обновления", t)
                val retrySuggested = isTransientFtpStartError(t)
                stopServerAndFailLocked(
                    message = buildStartFailureMessage(t, retrySuggested),
                    retrySuggested = retrySuggested,
                    detectedLocation = lastDetectedLocation,
                )
            }
        }
    }

    fun stopServer() {
        synchronized(lock) {
            stopServerLocked(clearPrepared = true)
            currentState.set(State(Status.STOPPED, str(R.string.update_server_stopped), detectedLocation = lastDetectedLocation))
        }
    }

    fun restartServer(): Result {
        val context = appContext ?: return failState("Контекст приложения ещё не инициализирован")
        return prepareAndStartServer(context, lastSearchPolicy)
    }

    fun handleUsbInserted(context: Context): Result {
        return prepareAndStartServer(context, UpdateFileLocator.SearchPolicy.USB_FIRST)
    }

    fun handleUsbRemoved(context: Context): Result {
        return prepareAndStartServer(context, UpdateFileLocator.SearchPolicy.INTERNAL_ONLY)
    }

    fun pollAvailableStorage(context: Context): Result {
        synchronized(lock) {
            val applicationContext = context.applicationContext
            appContext = applicationContext

            val searchPolicy = UpdateFileLocator.SearchPolicy.USB_FIRST

            if (!StorageAccessManager.isAllFilesAccessGranted()) {
                return stopServerAndFailLocked(
                    message = StorageAccessManager.buildMissingAccessMessage(applicationContext),
                    detectedLocation = lastDetectedLocation,
                )
            }

            return try {
                val searchResult = locateFirstValidPair(applicationContext, searchPolicy)
                lastDetectedLocation = searchResult.detectedLocation
                val validatedPair = searchResult.validatedPair
                if (validatedPair == null) {
                    return stopServerAndFailLocked(
                        buildNoValidPairMessage(searchResult.rejectionMessages),
                        detectedLocation = searchResult.detectedLocation,
                    )
                }

                if (isSamePreparedUpdateLocked(validatedPair)) {
                    return resultFromState(currentState.get())
                }

                Log.i(
                    TAG,
                    str(R.string.update_server_poll_new_update_fmt, validatedPair.pair.directoryLabel),
                )
                reloadValidatedPairLocked(applicationContext, searchPolicy, validatedPair)
            } catch (t: Throwable) {
                Log.e(TAG, "Ошибка периодического опроса обновления во внутренней памяти", t)
                val retrySuggested = isTransientFtpStartError(t)
                stopServerAndFailLocked(
                    message = buildStartFailureMessage(t, retrySuggested),
                    retrySuggested = retrySuggested,
                    detectedLocation = lastDetectedLocation,
                )
            }
        }
    }

    fun getServerState(): State = currentState.get()

    fun getPreparedFileInfo(): PreparedUpdateRepository.PreparedFileInfo? = currentState.get().fileInfo

    fun getBoundAddress(): EmbeddedFtpServerFactory.BoundAddress? = currentState.get().boundAddress

    private fun startValidatedPairLocked(
        context: Context,
        searchPolicy: UpdateFileLocator.SearchPolicy,
        validatedPair: ValidatedPair,
    ): Result {
        lastSearchPolicy = searchPolicy

        Log.i(TAG, "Проверка SHA-256 успешна: ${validatedPair.verification.actualSha256}")
        val prepared = repository.prepare(context, validatedPair.pair, validatedPair.verification.actualSha256)
        val server = ftpFactory.create(FtpServerConfig.fromProject(), prepared.rootDir)
        server.ftpServer.start()

        preparedUpdate = prepared
        runningServer = server
        preparedSourceKind = validatedPair.pair.sourceKind
        preparedSourceDirectory = validatedPair.pair.directoryLabel
        lastDetectedLocation = validatedPair.pair.directoryLabel

        val sourceText = "${validatedPair.pair.sourceKind.displayName}: ${validatedPair.pair.directoryLabel}"
        val message = str(
            R.string.update_server_started_fmt,
            server.boundAddress.host,
            server.boundAddress.port,
            sourceText,
        )
        Log.i(TAG, message)
        val successState = State(
            status = Status.RUNNING,
            message = message,
            fileInfo = prepared.info,
            boundAddress = server.boundAddress,
            detectedLocation = validatedPair.pair.directoryLabel,
        )
        currentState.set(successState)
        return Result(
            success = true,
            message = message,
            fileInfo = prepared.info,
            boundAddress = server.boundAddress,
            retrySuggested = false,
            detectedLocation = validatedPair.pair.directoryLabel,
        )
    }

    private fun resultFromState(state: State): Result {
        return Result(
            success = state.status == Status.RUNNING,
            message = state.message,
            fileInfo = state.fileInfo,
            boundAddress = state.boundAddress,
            retrySuggested = state.retrySuggested,
            detectedLocation = state.detectedLocation,
        )
    }

    private fun locateFirstValidPair(
        context: Context,
        searchPolicy: UpdateFileLocator.SearchPolicy,
    ): CandidateSearchResult {
        val candidates = locator.findCandidates(context, searchPolicy)
        if (candidates.isEmpty()) {
            Log.w(TAG, "Кандидаты обновления не найдены")
            lastDetectedLocation = null
            return CandidateSearchResult(validatedPair = null, rejectionMessages = emptyList(), detectedLocation = null)
        }

        val rejectionMessages = ArrayList<String>()
        lastDetectedLocation = candidates.firstOrNull()?.directoryLabel
        candidates.forEach { candidate ->
            Log.i(
                TAG,
                "Проверяю кандидата: источник=${candidate.sourceKind.displayName}, каталог=${candidate.directoryLabel}, zip=${candidate.zipFile.debugPath}, sig=${candidate.sigFile.debugPath}",
            )
            val verification = verifier.verify(context, candidate.zipFile, candidate.sigFile)
            if (verification.valid) {
                Log.i(TAG, "Выбран источник ${candidate.sourceKind.displayName}: ${candidate.directoryLabel}")
                Log.i(TAG, "SHA-256 совпал: ${verification.actualSha256}")
                return CandidateSearchResult(
                    validatedPair = ValidatedPair(candidate, verification),
                    rejectionMessages = rejectionMessages,
                    detectedLocation = candidate.directoryLabel,
                )
            }
            val rejectionMessage = buildRejectionMessage(candidate, verification)
            rejectionMessages += rejectionMessage
            Log.w(TAG, rejectionMessage)
        }
        return CandidateSearchResult(
            validatedPair = null,
            rejectionMessages = rejectionMessages,
            detectedLocation = candidates.firstOrNull()?.directoryLabel,
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

    private fun isSamePreparedUpdateLocked(validatedPair: ValidatedPair): Boolean {
        val preparedInfo = preparedUpdate?.info ?: return false
        return runningServer != null &&
            preparedSourceKind == validatedPair.pair.sourceKind &&
            preparedSourceDirectory == validatedPair.pair.directoryLabel &&
            preparedInfo.sha256 == validatedPair.verification.actualSha256 &&
            preparedInfo.size == validatedPair.pair.zipFile.size
    }

    private fun stopServerLocked(clearPrepared: Boolean) {
        runningServer?.let { server ->
            try {
                server.ftpServer.stop()
                Log.i(TAG, "FTP-сервер остановлен")
            } catch (_: UnsupportedOperationException) {
                Log.i(TAG, "FTP-сервер завершился с известной ошибкой dispose; очищаю состояние без повторного шума")
            } catch (t: Throwable) {
                Log.w(TAG, "Ошибка остановки FTP-сервера", t)
            }
        }
        runningServer = null
        preparedSourceKind = null
        preparedSourceDirectory = null

        if (clearPrepared) {
            appContext?.let { repository.clear(it) }
            preparedUpdate = null
        }
    }

    private fun reloadValidatedPairLocked(
        context: Context,
        searchPolicy: UpdateFileLocator.SearchPolicy,
        validatedPair: ValidatedPair,
    ): Result {
        stopServerLocked(clearPrepared = true)
        return startValidatedPairLocked(context, searchPolicy, validatedPair)
    }
    private fun stopServerAndFailLocked(
        message: String,
        retrySuggested: Boolean = false,
        detectedLocation: String? = null,
    ): Result {
        stopServerLocked(clearPrepared = true)
        return failState(
            message = message,
            retrySuggested = retrySuggested,
            detectedLocation = detectedLocation,
        )
    }

    private fun buildPreparingMessage(searchPolicy: UpdateFileLocator.SearchPolicy): String {
        return when (searchPolicy) {
            UpdateFileLocator.SearchPolicy.INTERNAL_ONLY -> str(R.string.update_server_preparing_internal)
            UpdateFileLocator.SearchPolicy.USB_FIRST -> str(R.string.update_server_preparing_usb_first)
        }
    }

    private fun failState(
        message: String,
        retrySuggested: Boolean = false,
        detectedLocation: String? = null,
    ): Result {
        val result = Result(
            success = false,
            message = message,
            retrySuggested = retrySuggested,
            detectedLocation = detectedLocation,
        )
        currentState.set(
            State(
                status = Status.ERROR,
                message = message,
                retrySuggested = retrySuggested,
                detectedLocation = detectedLocation,
            ),
        )
        return result
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
            R.string.update_server_preparing_internal -> "Подготовка FTP-сервера обновления: поиск во внутренней памяти"
            R.string.update_server_preparing_usb_first -> "Подготовка FTP-сервера обновления: поиск на USB и во внутренней памяти"
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
        var current: Throwable? = error
        while (current != null) {
            when (current) {
                is java.net.BindException,
                is java.net.SocketException,
                is org.apache.ftpserver.ftplet.FtpException -> return true
            }
            val message = current.message?.lowercase().orEmpty()
            if (message.contains("не найден ipv4") ||
                message.contains("cannot assign requested address") ||
                message.contains("failed to bind") ||
                message.contains("bind") ||
                message.contains("address already in use")
            ) {
                return true
            }
            current = current.cause
        }
        return false
    }
}
