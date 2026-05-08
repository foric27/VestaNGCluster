# PROJECT KNOWLEDGE BASE

**Generated:** 2026-05-08  
**Commit:** `0b5795b`  
**Branch:** `main`  
**Language:** Russian default, English only in `values-en/`  
**Stack:** single-module Android app, Kotlin + XML/ViewBinding, no Compose

---

## OVERVIEW

Android-приложение транслирует cluster/navigation UI на автомобильную комбинацию приборов.
Основной pipeline: `VirtualDisplay` → OpenGL → `MediaCodec` H.264 → UDP video/status, плюс встроенный FTP для OTA `ICUpdate.zip`.

---

## STRUCTURE

```
VestaNGClusterFlowStudio/
├── app/                                  # single Android application module
│   ├── build.gradle                      # AGP 9.1, ViewBinding, release shrink/minify
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml       # app components + privileged permissions
│       │   ├── java/ru/foric27/cluster/  # flat Kotlin package, coordinators/managers
│       │   └── res/                      # adaptive XML layouts + ru/en strings
│       └── test/java/ru/foric27/cluster/ # JVM JUnit 4 tests only
├── .github/workflows/                    # release + opencode workflows
├── docs/                                 # app icon sources
├── oem/                                  # decompiled OEM references; read-only comparison material
├── scripts/                              # signing-secret helper
├── test-client/                          # Python-утилиты для приёма UDP-стрима и диагностики
├── gradle/                               # wrapper
├── build.gradle                          # root AGP declaration
├── settings.gradle                       # single :app module
└── lint.xml                              # Android lint configuration
```

---

## WHERE TO LOOK

| Task | Location | Notes |
|------|----------|-------|
| App bootstrap / crash recovery | `ClusterApp.kt`, `ProcessRecoveryManager.kt`, `AppRecoveryReceiver.kt` | Timber/runtime init + process crash-loop guard |
| Service lifecycle | `UdpStreamService.kt`, `UdpStartupFlowCoordinator.kt`, `UdpPipelineStartCoordinator.kt`, `UdpStartupProbeCoordinator.kt` | Foreground-service facade; `startForeground()` on every path |
| Root network | `RootNetUtil.kt`, `RootNetworkAddressing.kt`, `UdpNetworkPreparationCoordinator.kt` | `su`, `eth0`, policy routing, iptables marks, pure IPv4/CIDR parsing |
| Video capture/encode | `VideoEncoder.kt`, `GlFrameComposer.kt`, `PersistentVirtualDisplay.kt` | shutdown order is product-critical |
| Navigator launch | `VideoDisplayLauncher.kt`, `YandexLaunchTarget.kt`, `ClusterLaunchProxyActivity.kt` | root-only `am start --display` via proxy-activity |
| FTP/OTA | `UdpUpdateServerCoordinator.kt`, `UpdateServerManager.kt`, `UpdateFileLocator.kt` | non-recursive `ICUpdate.zip` + `.sig` discovery |
| Runtime overrides | `ProductConfig.kt`, `RuntimeConfig.kt`, `RuntimeConfigStore.kt`, `RuntimeConfigPreferenceDataStore.kt`, `RuntimeConfigFieldSpecs.kt`, `DeveloperActivity.kt` | Developer screen edits config live |
| UI mode persistence | `AppSettings.kt`, `SyncHandler.kt` | DataStore + optional Settings.Global sync |
| App recovery | `UdpServiceRestartController.kt`, `UdpServiceRecoveryScheduler.kt`, `UdpWakeRecoveryController.kt`, `ProcessRecoveryManager.kt` | backoff, alarm recovery, crash-loop guard |
| Update confirmation UI | `UpdateAlertActivity.kt` | transient UI for discovered OTA package |
| UI/resources | `app/src/main/res/AGENTS.md` | layout qualifiers and ru/en strings |
| Unit tests | `app/src/test/java/ru/foric27/cluster/AGENTS.md` | JVM-only tests and reflection patterns |
| UDP-тестовый клиент | `test-client/AGENTS.md` | Python-утилиты для приёма стрима и диагностики |

---

## CODE MAP

| Symbol/File | Type | Role |
|-------------|------|------|
| `UdpStreamService` | `Service` | owns coordinators, workers, notification, recovery wiring |
| `VideoEncoder` | class | VirtualDisplay + MediaCodec + GL lifecycle |
| `RootNetUtil` | object | privileged route/IP/iptables command formatting and execution |
| `RootNetworkAddressing` | object | pure IPv4/CIDR parsing and network address calculation |
| `UpdateServerManager` | object | validates update pair, prepares FTP root, owns Apache FtpServer |
| `RuntimeConfig` / `ProductConfig` | objects | runtime overrides over immutable product defaults |
| `AppSettings` | object | selected cluster mode persistence through DataStore / Settings.Global |
| `ClusterApp` | `Application` | runtime bootstrap, Timber and process recovery initialization |
| `MainActivity` | activity | permission preflight, status UI, service start |
| `BootReceiver` | receiver | boot/package/USB events |
| `ClusterLaunchProxyActivity` | activity | transparent Yandex cluster-focus proxy |

LSP is unavailable in this environment (`kotlin-lsp` missing); use AST/direct reads for symbol discovery.

---

## CONVENTIONS

- Flat package is intentional: categorize by filename suffix, not subpackage.
- `*Coordinator` = lifecycle orchestration owned by `UdpStreamService`.
- `*Manager` = singleton `object` utility/state owner.
- `*Controller` = state machine/control logic.
- Product defaults live in `ProductConfig`; user overrides go through `RuntimeConfig`/`RuntimeConfigStore`.
- User-facing cluster mode state lives in `AppSettings`; do not merge it into `RuntimeConfig` unless it becomes a product default override.
- Root/network pure helpers may be split into standalone files (for example `RootNetworkAddressing.kt`) inside the flat package; do not create subpackages casually.
- UI language set is exactly `ru` + `en`; add/update both string files.
- OTA lookup is non-recursive: only storage root of internal/USB.

---

## SLEEP / WAKE BEHAVIOR

- При `ACTION_SCREEN_OFF` стрим останавливается и `PersistentVirtualDisplay` полностью освобождается (`releaseAll()`).
- При `ACTION_SCREEN_ON` / `ACTION_USER_PRESENT` стрим перезапускается через `attemptRestart()`.
- Это экономит заряд: на сне не держим VirtualDisplay, Surface, MediaCodec и wake-lock.

---

## COMMANDS

```powershell
# Use local JDK 21 for reliable Android builds
$env:JAVA_HOME="$PWD/.tools/jdk-21.0.10"; $env:PATH="$env:JAVA_HOME/bin;$env:PATH"

./gradlew.bat assembleDebug
./gradlew.bat :app:testDebugUnitTest
./gradlew.bat lintDebug
./gradlew.bat assembleRelease

# Full local check
./gradlew.bat assembleDebug testDebugUnitTest lintDebug assembleRelease

# Install current release APK
.\.tools\platform-tools\adb.exe install -r .\app\build\outputs\apk\release\app-release.apk
```

---

## BUILD / CI NOTES

- AGP `9.1.1`, Gradle wrapper `9.4.1`, compile/target SDK `37`, min SDK `26`.
- `gradle.properties`: `org.gradle.daemon=false`, `android.builtInKotlin=true`, `android.suppressUnsupportedCompileSdk=37.0`.
- Release uses shrink/minify + `app/proguard-rules.pro`; manifest entry points and Apache MINA NIO processor are kept explicitly.
- Signing reads env vars first, then `keystore.properties`; BOM-prefixed `storeFile` key is normalized.
- CI workflow publishes rolling prerelease tag `main-latest`; if secrets are missing, APK may be unsigned.
- `.github/workflows/android-release.yml` runs `assembleRelease` and publishes the rolling `main-latest` release.
- `.github/workflows/opencode.yml` is comment-triggered automation for `/oc` and `/opencode` comments.

## SIGNING

- Единственный release-keystore: `.tools/signing/foric27.jks` (alias `foric27`).
- `keystore.properties` указывает на `foric27.jks`; старый `release-keystore.jks` удалён.
- SHA-256 сертификата `foric27` зафиксирован в `ProductConfig.Security.EXPECTED_SIGNATURE_SHA256`.

## OBFUSCATION

- R8 full mode: `android.enableR8.fullMode=true` в `gradle.properties`.
- ProGuard rules: `-repackageclasses 'a'`, `-allowaccessmodification` для максимальной обфускации.
- Keep rules: точки входа manifest, Apache MINA NIO processor, `StreamConfig`, `SignatureVerifier`, `ProductConfig$Security`.

## OPTIMIZATION FLAGS

- `org.gradle.parallel=true`, `org.gradle.configureondemand=true`, `org.gradle.caching=true`.
- JVM heap: `-Xmx4096m`.

## DEVICE INSTALL (MCP)

- **Только обновление** (`pm install -r`), никогда полная переустановка — сохраняются данные и настройки.
- Путь: push APK в `/data/local/tmp/`, затем `pm install -r`, затем удалить temp-файл.
- Использовать MCP adb tools: `adb_push` → `adb_shell` → `adb_rm`.
- Не использовать `adb uninstall` перед установкой.

## CLEANUP

- После установки на устройство: `adb shell rm -f /data/local/tmp/app-release*.apk`.
- После тестов/сборки: `./gradlew.bat clean`.

---

## DEVELOPER SETTINGS (RuntimeConfig)

Скрытый экран разработчика (`DeveloperActivity`) доступен через MainActivity → «Настройки разработчика». Все изменения применяются сразу; перезапуск сервиса происходит автоматически для полей, влияющих на стрим/сеть.

### Target App (целевое приложение на кластере)
| Поле | Тип | По умолчанию | Описание |
|------|-----|--------------|----------|
| `target_package_name` | string | `ru.yandex.yandexnavi` | Пакет целевого приложения |
| `target_cluster_component` | string | `ru.yandex.yandexnavi/com.yandex.navi.cluster.ClusterNavigationActivity` | Полное имя cluster-activity |
| `target_action_main` | string | `android.intent.action.MAIN` | Системный action для запуска |
| `target_category_cluster_navigation` | string | `android.car.cluster.NAVIGATION` | Категория навигации кластера |

### Visible Area (область видимости кластера)
| Поле | Тип | По умолчанию | Описание |
|------|-----|--------------|----------|
| `visible_left` | int | 712 | Левая граница visible area (px) |
| `visible_top` | int | 12 | Верхняя граница visible area (px) |
| `visible_right` | int | 1208 | Правая граница visible area (px) |
| `visible_bottom` | int | 524 | Нижняя граница visible area (px) |

### Video (параметры видеокодирования)
| Поле | Тип | По умолчанию | Описание |
|------|-----|--------------|----------|
| `video_width` | int | 1920 | Ширина выходного видео |
| `video_height` | int | 640 | Высота выходного видео |
| `video_fps_limit` | int | 30 | Максимальный FPS |
| `video_dynamic_fps` | boolean | false | Динамический FPS по обновлениям UI |
| `video_default_dpi` | int | 320 | DPI виртуального дисплея |
| `video_bitrate` | int | 8000000 | Битрейт H.264 (bps) |
| `video_iframe_interval_sec` | int | 1 | Интервал между I-frame (сек) |
| `video_black_bottom_px` | int | 106 | Высота чёрной маски снизу (px) |

### Network (сетевые параметры)
| Поле | Тип | По умолчанию | Описание |
|------|-----|--------------|----------|
| `network_target_ip` | string | 192.168.40.2 | IP-адрес принимающей стороны |
| `network_video_port` | int | 5004 | UDP-порт видеопотока |
| `network_status_port` | int | 5001 | UDP-порт статуса |
| `network_local_cidr` | string | 192.168.40.1/24 | Локальный адрес/маска (CIDR) |
| `network_gateway` | string | 192.168.40.2 | Шлюз для маршрута |
| `network_bind_ip` | string | 192.168.40.1 | IP для привязки UDP-сокета |
| `network_udp_max_payload_bytes` | int | 1400 | Макс. размер UDP payload (без фрагментации) |
| `network_udp_pacing_max_bps` | int | 16000000 | Ограничение скорости UDP (bps) |

### Root (root-команды и кеширование)
| Поле | Тип | По умолчанию | Описание |
|------|-----|--------------|----------|
| `root_iface` | string | eth0 | Имя сетевого интерфейса для root-сети |
| `root_iface_cache_ttl_ms` | long | 10000 | TTL кеша проверки интерфейса (мс) |
| `root_route_cache_ttl_ms` | long | 3000 | TTL кеша проверки маршрута (мс) |
| `root_su_timeout_ms` | long | 10000 | Таймаут su-команды (мс) |

### FTP Update (OTA-сервер)
| Поле | Тип | По умолчанию | Описание |
|------|-----|--------------|----------|
| `ftp_update_zip_name` | string | ICUpdate.zip | Имя архива обновления |
| `ftp_update_sig_name` | string | ICUpdate.zip.sig | Имя файла подписи |
| `ftp_port` | int | 2121 | Управляющий порт FTP |
| `ftp_passive_port_start` | int | 22100 | Начало диапазона пассивных портов |
| `ftp_passive_port_end` | int | 22109 | Конец диапазона пассивных портов |
| `ftp_user` | string | anonymous | Имя FTP-пользователя |
| `ftp_password` | string | fetisov | Пароль FTP-пользователя |
| `ftp_root_dir_name` | string | ftp_root | Имя рабочей директории FTP |
| `ftp_internal_poll_period_ms` | long | 10000 | Период опроса обновления (мс) |
| `ftp_retry_delay_ms` | long | 3000 | Задержка повторной попытки FTP (мс) |

### Service (сервис и восстановление)
| Поле | Тип | По умолчанию | Описание |
|------|-----|--------------|----------|
| `service_notification_id` | int | 1201 | ID foreground-уведомления |
| `service_notification_channel_id` | string | cluster_stream | ID канала уведомлений |
| `service_notification_channel_name` | string | Трансляция кластера | Имя канала уведомлений |
| `service_restart_backoff_start_ms` | long | 700 | Начальная задержка перезапуска (мс) |
| `service_restart_backoff_max_ms` | long | 15000 | Максимальная задержка перезапуска (мс) |
| `service_recovery_delay_ms` | long | 700 | Стандартная задержка восстановления (мс) |
| `service_task_removed_recovery_delay_ms` | long | 500 | Задержка восстановления после удаления задачи (мс) |
| `service_recovery_request_code` | int | 1101 | Код запроса PendingIntent восстановления |
| `service_restart_request_debounce_ms` | long | 250 | Мин. интервал между запросами рестарта (мс) |
| `service_codec_error_restart_debounce_ms` | long | 3000 | Мин. интервал рестартов при ошибках кодека (мс) |
| `service_route_wait_timeout_ms` | long | 3000 | Макс. время ожидания маршрута (мс) |
| `service_route_wait_step_ms` | long | 100 | Шаг проверки маршрута (мс) |
| `service_no_route_restart_backoff_min_ms` | long | 2500 | Мин. задержка при отсутствии маршрута (мс) |
| `service_iface_missing_restart_backoff_min_ms` | long | 4000 | Мин. задержка при отсутствии интерфейса (мс) |
| `service_connectivity_watchdog_period_ms` | long | 1500 | Период watchdog проверки связности (мс) |
| `service_route_recent_send_grace_ms` | long | 5000 | Окно актуальности отправки (мс) |
| `service_route_failures_before_restart` | int | 2 | Допустимое количество отказов маршрута |
| `service_thread_join_timeout_ms` | long | 250 | Таймаут ожидания потоков (мс) |
| `service_status_period_ms` | int | 250 | Период отправки статуса (мс) |
| `service_status_key_sync_interval` | int | 2400 | Интервал полной синхронизации статуса |
| `service_status_error_log_every` | int | 10 | Частота логирования ошибок статуса |
| `service_process_crash_recovery_delay_ms` | long | 1500 | Задержка восстановления после падения процесса (мс) |
| `service_process_crash_window_ms` | long | 60000 | Окно наблюдения crash loop (мс) |
| `service_process_crash_max_in_window` | int | 3 | Макс. падений в окне до подавления recovery |
| `service_process_crash_suppress_ms` | long | 180000 | На сколько подавлять recovery после crash loop (мс) |

---

## AGENT RULES

- **Язык общения:** агент размышляет и отвечает исключительно на русском языке.
- **Язык комментариев:** все комментарии в коде (KDoc, inline, TODO) пишутся только на русском.
- **Исключения:** названия классов/методов/API, строковые ресурсы `values-en/`, и имена собственных библиотек остаются на английском.

---

## CHILD AGENTS

- `app/src/main/java/ru/foric27/cluster/AGENTS.md` — Kotlin package architecture.
- `app/src/main/res/AGENTS.md` — XML layouts, dimensions, localization.
- `app/src/test/java/ru/foric27/cluster/AGENTS.md` — JVM unit-test conventions.
- `test-client/AGENTS.md` — Python-утилиты для приёма UDP-стрима и диагностики.
