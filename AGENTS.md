# AGENTS

**Generated:** 2026-04-23  
**Language:** Russian (default), English (values-en/)  
**Stack:** Android Kotlin, XML + ViewBinding, no Compose

---

## Обзор

Single-module Android-приложение для трансляции cluster/navigation UI на автомобильную комбинацию приборов.

Пайплайн: VirtualDisplay → OpenGL → MediaCodec H.264 → UDP + статус на отдельном порту + FTP для OTA-обновлений.

---

## Ключевые ограничения

| Ограничение | Значение | Почему важно |
|-------------|----------|--------------|
| Разрешение видео | `1920x640` | Жёсткий продуктовый контракт |
| Язык UI | `ru`, `en` | Только эти локали |
| Сеть | Root-only, `eth0` | Non-root fallback удалён |
| Target app | `ru.yandex.yandexnavi` | Проприетарный контракт |
| Storage | Non-recursive ZIP lookup | Только корень internal/USB |

---

## Архитектура: Coordinator Pattern

`UdpStreamService` — тонкий façade, владеющий ~10 coordinators. Каждый coordinator — отдельная зона ответственности.

```
UdpStreamService (facade)
├── UdpNetworkPreparationCoordinator     [root IP + route setup]
├── UdpStartupFlowCoordinator            [create UdpSender]
│   └── UdpStartupProbeCoordinator       [poll route ready]
├── UdpPipelineStartCoordinator          [start VideoEncoder + monitoring]
│   ├── UdpStatusSyncCoordinator         [status UDP port]
│   ├── UdpTransportStatsCoordinator     [stats logging]
│   └── UdpConnectivityWatchdogCoordinator [health monitoring]
├── UdpUpdateServerCoordinator           [FTP + OTA lifecycle]
├── UdpWakeRecoveryController            [wake-from-sleep]
├── UdpServiceRestartController          [backoff restart]
└── UdpServiceRecoveryScheduler          [AlarmManager recovery]
```

**Singleton Managers:** `UpdateServerManager`, `ProcessRecoveryManager`, `BatteryOptimizationManager`, `StorageAccessManager` — stateless utility `object`'s.

---

## Точки входа

| Компонент | Назначение |
|-----------|------------|
| `MainActivity` | Launcher, permissions preflight, foreground service start |
| `UdpStreamService` | Foreground service, lifecycle orchestrator |
| `BootReceiver` | BOOT_COMPLETED, USB mount events |
| `ClusterLaunchProxyActivity` | Transparent proxy for cluster focus |
| `UpdateAlertActivity` | OTA update dialog (translucent, noHistory) |
| `DeveloperActivity` | Runtime config overrides |

---

## Критические секции (не трогать без причины)

### Lifecycle порядок
```kotlin
// VideoEncoder.kt — shutdown sequence:
1. encoder.stop()
2. codecThread.join(THREAD_JOIN_TIMEOUT_MS)
3. release GL/Surface
```
Нарушение → GPU crashes.

### Service.onStartCommand
`startForeground()` вызывается на **всех** путях (FTP-refresh, invalid config, catch-blocks). Исправляет `RemoteServiceException`.

### Root network
- Приоритеты `ip rule`: 50/51/52 (ранее 11000/11001)
- iptables mangle mark `0x1` для gateway traffic
- Очистка legacy-правил при каждом apply

---

## Где читать код

### Для изменения поведения
- `UdpStreamService.kt` — entry point, restart wiring
- `VideoEncoder.kt` — VirtualDisplay + MediaCodec lifecycle
- `RootNetUtil.kt` — root commands, policy routing
- `UdpNetworkPreparationCoordinator.kt` — network prep

### Для настроек/конфигурации
- `ProductConfig.kt` — дефолты, wire-контракты
- `RuntimeConfig.kt` — runtime overrides
- `RuntimeConfigFieldSpecs.kt` — UI specs for DeveloperActivity

### Для recovery/restart
- `UdpServiceRestartController.kt` — backoff logic
- `UdpServiceRecoveryScheduler.kt` — AlarmManager scheduling
- `UdpWakeRecoveryController.kt` — wake handling
- `ProcessRecoveryManager.kt` — crash-loop guard

### Для FTP/OTA
- `UdpUpdateServerCoordinator.kt` — orchestration
- `UpdateServerManager.kt` — FTP lifecycle
- `UpdateFileLocator.kt` — ZIP/sig discovery

---

## Команды

```bash
# Debug
./gradlew.bat assembleDebug

# Tests
./gradlew.bat testDebugUnitTest

# Lint
./gradlew.bat lintDebug

# Full check
$env:JAVA_HOME="$PWD/.tools/jdk-21.0.10"; $env:PATH="$env:JAVA_HOME/bin;$env:PATH"; ./gradlew.bat assembleDebug testDebugUnitTest lintDebug assembleRelease

# Release
./gradlew.bat assembleRelease

# Install release
.\.tools\platform-tools\adb.exe install -r .\app\build\outputs\apk\release\app-release.apk
```

---

## Build конфигурация

| Параметр | Значение |
|----------|----------|
| AGP | 9.1.0 |
| Gradle | 9.4.1 |
| compileSdk/targetSdk | 37 |
| minSdk | 26 |
| Java | 17 |
| JDK (local) | 21 (`.tools/jdk-21.0.10`) |
| Kotlin | built-in (AGP) |
| ProGuard | enabled (release) |

**Non-standard:**
- `org.gradle.daemon=false` — single-use builds
- `android.suppressUnsupportedCompileSdk=37.0`
- Custom BOM handling in signing config parser

---

## Anti-patterns (запрещено)

| Практика | Причина запрета |
|----------|-----------------|
| Root ping | Удалён; используй UDP-probe |
| Shizuku/Sui | Только `su` privileged path |
| Non-root network fallback | Удалён |
| Возврат startup-логики в сервис | Уже в coordinators |
| Сборка на системном Java 26 | Ломает `jlink` |
| `startForegroundService` без `startForeground` | ANR/crash |
| Unordered encoder shutdown | GPU crashes |
| FTP stop в `synchronized` | Блокирует main thread |

---

## Тесты

JUnit 4 unit tests в `app/src/test/java/ru/foric27/cluster/`:
- `RuntimeConfigTest.kt` — config logic
- `SyncHandlerTest.kt` — sync protocol
- `VideoCodecOutputProcessorTest.kt` — H.264 framing
- `VideoFrameTimingControllerTest.kt` — timing
- `NetworkInterfaceSelectorTest.kt` — iface selection
- `ConnectivityHealthTest.kt` — health checks
- `RootNetUtilTest.kt` — root command formatting
- `ClusterModeTest.kt` — mode logic

**Note:** Только JVM unit tests. Instrumented/UI tests отсутствуют.

---

## CI/CD

GitHub Actions (`.github/workflows/android-release.yml`):
- JDK 21 Temurin
- Android SDK 37
- Signing из secrets или unsigned fallback
- Artifact в GitHub Release

---

## Layout квалификаторы

| Квалификатор | Назначение |
|--------------|------------|
| `layout/` | Портрет телефона |
| `layout-land/` | Landscape телефон |
| `layout-sw600dp/` | 7" планшет |
| `layout-sw600dp-land/` | 7" планшет landscape |
| `layout-sw720dp/` | 10"+ планшет |
| `layout-sw800dp/` | 12"+ / Android TV |

---

## Последние крупные изменения (апрель 2026)

- Удалён `UdpPeerReachabilityChecker` (root ping)
- Policy routing: приоритеты 50/51/52 + iptables mangle
- Signature verification в `MainActivity.onCreate`
- Adaptive layouts для планшетов/TV
- Update dialog (`UpdateAlertActivity`)
- Все `Thread` → daemon
- `WeakReference` в `AppWarningCenter`

---

## Дочерние AGENTS.md

- `app/src/main/java/ru/foric27/cluster/AGENTS.md` — детали по исходному коду
