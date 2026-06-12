# API Reference

Публичные API ключевых компонентов Vesta NG Cluster.

## UdpStreamService

Центральный foreground-сервис. Управляется через intents.

### Actions

| Action | Описание |
|--------|----------|
| `ACTION_START_STREAM` | Запуск streaming pipeline |
| `ACTION_STOP_STREAM` | Остановка streaming |
| `ACTION_RESTART_PIPELINE_NOW` | Перезапуск pipeline (без сетевой подготовки) |
| `ACTION_RESTART_SERVICE_NOW` | Полный перезапуск сервиса |
| `ACTION_REFRESH_USB_FTP_NOW` | Обновление FTP после USB mount |
| `ACTION_REFRESH_USB_REMOVED_FTP_NOW` | Обновление FTP после USB unmount |

### Static Methods

```kotlin
UdpStreamService.startServiceCompat(context: Context)
UdpStreamService.stopServiceCompat(context: Context)
UdpStreamService.restartPipelineCompat(context: Context)
UdpStreamService.restartServiceCompat(context: Context)
UdpStreamService.refreshUsbFtpCompat(context: Context)
UdpStreamService.refreshUsbRemovedFtpCompat(context: Context)
```

## StreamConfig

Data class конфигурации streaming pipeline.

```kotlin
data class StreamConfig(
    val ip: String,
    val port: Int,
    val launchComponent: String?,
    val localCidr: String?,
    val gateway: String?,
    val bindIp: String?,
    val width: Int,
    val height: Int,
    val dpi: Int,
    val fps: Int,
    val bitrate: Int,
    val iframeIntervalSec: Int,
) : Serializable
```

### Factory Methods

```kotlin
StreamConfig.fixedConfig(
    context: Context? = null,
    mode: AppSettings.UiStreamMode = UiStreamMode.NAV
): StreamConfig
```

Создаёт конфиг из `RuntimeConfig`. Для MED режима `launchComponent` = `MediaCoverActivity`.

## RoutePreparationResult

Sealed class результатов подготовки сети.

```kotlin
sealed class RoutePreparationResult {
    abstract val bindIp: String?
    abstract val ifaceName: String?

    data class Success(bindIp, ifaceName)
    data class IfaceMissing(ifaceName, details)    // bindIp = null
    data class RootUnavailable(ifaceName, details)  // bindIp = null
    data class RouteTimeout(ifaceName, details)     // bindIp = null
    data class RouteNotApplied(ifaceName, bindIp, details)
    data class NetworkUnreachable(ifaceName, bindIp, details)

    val statusName: String  // "Success", "IfaceMissing", etc.
}
```

### UdpNetworkPreparationResult

```kotlin
data class UdpNetworkPreparationResult(
    val routePreparation: RoutePreparationResult,
) {
    val bindIp: String?
    val ifaceName: String?
    val ifacePresent: Boolean   // true если НЕ IfaceMissing
    val rootRequired: Boolean   // true если RootUnavailable
    val routeReady: Boolean     // true если Success
}
```

## VideoEncoder

Управление video encoding pipeline.

### Lifecycle

```kotlin
class VideoEncoder(
    cfg: StreamConfig,
    preferredLaunchComponent: String?,
    sender: UdpSender,
    onCodecOutput: (ByteArray, Int, Int) -> Unit,
    onEncoderStopped: () -> Unit,
    onEncoderError: (Throwable) -> Unit,
    clock: () -> Long = SystemClock::elapsedRealtime,
)
```

### Key Methods

```kotlin
encoder.start()
encoder.stop()
encoder.release()
```

### State Machine

```
IDLE → START_REQUESTED → PREPARING → START_COMPLETED → RUNNING → STOPPED → IDLE
                                    ↘ ERROR → IDLE
```

## VideoDisplayLauncher

Root-запуск activity на secondary display.

```kotlin
class VideoDisplayLauncher(
    private val preferredLaunchComponent: String?,
    private val rootActivityStarter: RootActivityStarter,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
)

fun launchOnDisplay(displayId: Int, force: Boolean = false)
```

### RootActivityStarter Interface

```kotlin
fun interface RootActivityStarter {
    fun start(command: String): RootCommandResult
}
```

## AppUpdateManager

Self-update из GitHub Releases.

### Query

```kotlin
AppUpdateManager.queryUpdate(
    context: Context,
    channel: AppSettings.UpdateChannel,
    force: Boolean = false,
): QueryResult
```

### Download

```kotlin
AppUpdateManager.downloadUpdate(
    context: Context,
    release: AppUpdateRelease,
): DownloadedUpdate
```

### Install

```kotlin
AppUpdateManager.requestInstall(
    context: Context,
    update: DownloadedUpdate,
): InstallResult
```

### Result Types

```kotlin
sealed class QueryResult {
    data class Ready(val release: AppUpdateRelease) : QueryResult()
    data class DownloadedReady(val cached: DownloadedUpdate) : QueryResult()
    data class Error(val message: String) : QueryResult()
    object UpToDate : QueryResult()
}

sealed class InstallResult {
    object Started : InstallResult()
    data class PermissionRequired(val settingsIntent: Intent) : InstallResult()
    data class Error(val message: String) : InstallResult()
}
```

## RuntimeConfig

Runtime-переопределения конфигурации.

### Key Namespaces

```kotlin
RuntimeConfig.Root       // iface, localCidr, gateway
RuntimeConfig.Network    // bindIp, targetHost, targetPort
RuntimeConfig.Video      // width, height, fps, bitrate, codecProfile
RuntimeConfig.Service    // backoff delays, recovery params
RuntimeConfig.UpdateFtp  // port, passive ports, credentials
RuntimeConfig.TargetApp  // launch component, cluster activity
RuntimeConfig.VisibleArea // rect, offset
```

### Read/Write

```kotlin
RuntimeConfig.init(context: Context)           // инициализация из DataStore
RuntimeConfig.getInt(key, default)             // чтение
RuntimeConfig.putString(key, value)            // запись
RuntimeConfig.getBoolean(key, default)         // чтение boolean
```

## AppSettings

Persistent preferences.

### Stream Mode

```kotlin
AppSettings.getSelectedMode(context: Context): UiStreamMode
AppSettings.applySelectedMode(context: Context, mode: UiStreamMode): ApplyModeResult

enum class UiStreamMode(val settingValue: Int) {
    TRIP(0),      // ABS/TRIP — без видеотрансляции
    CLASSIC_NAV(1),
    MEDIA_NAV(2),
    SPORT_NAV(3),
    // ...
    SPORT_MEDIA(8),
    NOT_USED(9),
}
```

### Update Channel

```kotlin
enum class UpdateChannel { ROLLING, STABLE }
```

## VdspState

Глобальное состояние VirtualDisplay.

```kotlin
object VdspState {
    fun set(displayId: Int, width: Int, height: Int): DisplayState
    fun clear()
    fun getDisplayId(): Int
    fun getDisplayState(): DisplayState

    enum class DisplayState(val wireValue: String) {
        UNKNOWN("unknown"),
        ADDED("added"),
        CHANGED("changed"),
        REMOVED("removed"),
    }
}
```
