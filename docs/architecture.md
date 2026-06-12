# Архитектура Vesta NG Cluster

## Обзор

Vesta NG Cluster — Android-приложение для вывода навигации и мультимедиа на цифровую комбинацию приборов Lada Vesta NG через нештатную Android-магнитолу.

Основной streaming-требует **root-доступ** для настройки USB/RNDIS-сети, policy routing, root-launch на secondary display и поддержания транспортного канала.

## Общая схема

```
┌─────────────────────────────────────────────────────────────┐
│                    External triggers                         │
│  MainActivity · BootReceiver · ClusterFocusRequestReceiver  │
│                    MediaNotificationListenerService          │
└──────────────────────────┬──────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────┐
│                    UdpStreamService                          │
│              foreground service orchestrator                 │
├─────────────────────────────────────────────────────────────┤
│  UdpNetworkPreparationCoordinator                           │
│    └─ NetworkInterfaceSelector / RootNetUtil / policy route  │
│  UdpStartupFlowCoordinator                                  │
│    └─ UdpSender / UdpStartupProbeCoordinator                │
│  UdpPipelineStartCoordinator                                │
│    ├─ VideoEncoder → VirtualDisplay → GL → MediaCodec → UDP │
│    ├─ UdpStatusSyncCoordinator → status port                │
│    ├─ UdpTransportStatsCoordinator                          │
│    └─ UdpConnectivityWatchdogCoordinator                    │
│  UdpUpdateServerCoordinator → FTP OTA                       │
│  UdpWakeRecoveryController                                  │
│  UdpServiceRestartController                                │
│  ProcessRecoveryManager                                     │
└─────────────────────────────────────────────────────────────┘
```

## Video Pipeline

Единый pipeline используется для NAVI и MED. Различается только target activity.

### NAVI path

```
External NAV app (Яндекс.Навигатор)
  → root am start --display 10
  → VirtualDisplay (PersistentVirtualDisplay)
  → SurfaceTexture → GlFrameComposer
  → MediaCodec (H.264 encoder, OMX.sprd.h264.encoder)
  → VideoCodecOutputProcessor (Annex B, SPS/PPS, keyframes)
  → UdpSender (192.168.40.2:5004)
```

### MED path

```
MediaNotificationListenerService
  → MediaCoverState (title, artist, cover, progress)
  → MediaCoverActivity (Compose UI на secondary display)
  → VirtualDisplay → GL → MediaCodec → UdpSender
```

### Ключевые компоненты

| Компонент | Роль |
|-----------|------|
| `VideoEncoder` | Управляет MediaCodec, VirtualDisplay, GlFrameComposer |
| `VideoCodecOutputProcessor` | Сборка Annex B, добавление SPS/PPS к keyframes |
| `VideoDisplayLauncher` | Root-запуск activity на secondary display |
| `PersistentVirtualDisplay` | Управление生命周期 VirtualDisplay |
| `GlFrameComposer` | Копирование кадров из SurfaceTexture в input surface кодека |

## Root Network

Подготовка сети выполняется под root через `libsu`:

1. `ip link show dev <iface>` — проверка наличия интерфейса
2. `ip addr replace <cidr> dev <iface>` — статический IP
3. `ip route add <gateway> dev <iface>` — маршрут
4. `ip rule add from <src> table <id>` — policy routing
5. `ip route add default via <gateway> dev <iface> table <id>` — default route в table
6. `iptables -t mangle -A PREROUTING ... -j MARK --set-mark <mark>` — mark трафика
7. `ip route get <dst>` — верификация маршрута

### Интерфейсы

- `NetworkInterfaceSelector` — выбор интерфейса по приоритету (eth0 > seth_lte* > wlan0)
- `RootNetUtil` — статические методы для root-операций
- `NetworkRootShell` — выполнение root-команд через libsu

## Status Sync

Отдельный UDP-канал для синхронизации состояния с комбинацией приборов:

- **Порт:** 5001 (отличается от video порта 5004)
- **Содержимое:** время, язык кластера, состояние streaming
- **Компонент:** `UdpStatusSyncCoordinator`
- **Формат:** `SyncHandler` с поддержкой time/lang/state пакетов

## Recovery

### Restart/Backoff

`UdpServiceRestartController` управляет перезапусками:
- Начальный backoff: 700мс
- Удвоение при каждом retry
- Максимум: 15000мс
- Debounce: 250мс (обычные), 3000мс (codec errors)

### Watchdog

`UdpConnectivityWatchdogCoordinator` проверяет:
- Состояние link (ip link show)
- Наличие интерфейса
- Маршрут до целевого IP
- Недавняя video-отправка
- Состояние encoder/pipeline

### Process Recovery

`ProcessRecoveryManager` контролирует crash-петлю:
- Окно: `PROCESS_CRASH_WINDOW_MS`
- Максимум крашей: `PROCESS_CRASH_MAX_IN_WINDOW`
- Подавление: `PROCESS_CRASH_SUPPRESS_MS`

## Update Subsystems

### FTP OTA для IVI/Cluster

`UdpUpdateServerCoordinator` → `UpdateServerManager` → `EmbeddedFtpServer`
- Ищет `ICUpdate.zip` + `ICUpdate.zip.sig` на USB
- SHA-256 верификация
- Встроенный Apache FTP Server на порту 2121

### Self-update APK

`AppUpdateManager` → GitHub Releases API
- Rolling канал: `main-latest` tag
- Stable канал: `latest` release
- Проверка: SHA-256 checksum, package name, versionCode, build SHA
- Установка через PackageInstaller Session API (без root)

## Конфигурация

### RuntimeConfig

Делится на namespaces:
- `Root` — iface, localCidr, gateway
- `Network` — bindIp, targetHost, targetPort
- `Video` — width, height, fps, bitrate
- `Service` — backoff delays, recovery parameters
- `UpdateFtp` — port, passive ports, credentials
- `TargetApp` — launch component, cluster activity

### AppSettings

Persistent preferences через DataStore:
- `UiStreamMode` — NAV, MED, ABS/TRIP
- `UpdateChannel` — rolling/stable
- Developer settings (screen, log, network)

## Пакеты

| Пакет | Назначение |
|-------|------------|
| `cluster/` | Bootstrap: ClusterApp, ClusterMode, receivers |
| `cluster/service/` | UdpStreamService, координаторы, контроллеры |
| `cluster/service/coordinator/` | 8 координаторов (startup, pipeline, network, etc.) |
| `cluster/video/` | VideoEncoder, PersistentVirtualDisplay, GlFrameComposer |
| `cluster/network/` | RootNetUtil, NetworkRootShell, routing |
| `cluster/config/` | ProductConfig, RuntimeConfig, AppSettings |
| `cluster/update/` | OTA/FTP + app self-update |
| `cluster/ui/` | Activities (Compose), YandexLaunchTarget |
| `cluster/util/` | Логирование, VdspState, Sha256Util |
