# AGENTS

## Язык и локальные утилиты

- Отвечай и рассуждай на русском языке, если пользователь явно не просит другой язык.
- Для локальных Gradle-запусков используй project-local JDK из `.tools/jdk-21.0.10`, а не системный Java.
- Для подключения к телефону, установки APK, запуска приложений и UI/smoke-проверок по возможности используй mobile MCP; local `adb` из `.tools/platform-tools/adb.exe` оставляй для shell/logcat/install только когда MCP не покрывает нужный сценарий.
- Если нужна ручная подпись или проверка APK, сначала ищи утилиты в папке проекта; если их там нет, используй Android SDK build-tools из `local.properties`.

## Что это за репозиторий

- Single-module Android-проект `:app` на Kotlin с XML + ViewBinding; Compose нет.
- Основной runtime-поток: `MainActivity` поднимает `UdpStreamService`, сервис готовит сеть, запускает `VideoEncoder`, шлет H.264 по UDP и отдельно поднимает FTP-обновление.
- Это не обычное UI-приложение: самые рискованные зоны находятся в lifecycle foreground-сервиса, recovery, root-network и update/FTP контракте.

## Где читать код сначала

- `app/src/main/java/ru/foric27/cluster/UdpStreamService.kt` — главный orchestration-класс сервиса; сейчас в нем уже оставлены в основном start/stop flow, `onStartCommand`/restart wiring и связка coordinator'ов.
- `app/src/main/java/ru/foric27/cluster/UdpServiceRestartController.kt` + `UdpServiceRecoveryScheduler.kt` + `UdpServiceAlerts.kt` — restart/backoff, recovery alarms и warning/reporting, уже вынесенные из сервиса.
- `app/src/main/java/ru/foric27/cluster/UdpStatusSyncCoordinator.kt` + `UdpTransportStatsCoordinator.kt` + `UdpConnectivityWatchdogCoordinator.kt` — отдельно вынесенные status sync, transport stats и watchdog; если правишь их поведение, проверяй интеграцию обратно через `UdpStreamService`.
- `app/src/main/java/ru/foric27/cluster/UdpStartupFlowCoordinator.kt` + `UdpStartupProbeCoordinator.kt` + `UdpPipelineStartCoordinator.kt` — уже вынесенная orchestration-ветка `startPipelineAsync(...)`; не собирай sender/probe/video startup обратно в сервис.
- `app/src/main/java/ru/foric27/cluster/UdpNetworkPreparationCoordinator.kt` — общий prepareNetwork path для обычного старта и restart; если меняешь root route setup/bind IP, правь здесь, а не дублируй ветки в сервисе.
- `app/src/main/java/ru/foric27/cluster/UdpUpdateServerCoordinator.kt` + `UpdateServerManager.kt` — orchestration FTP/update lifecycle; в сервисе больше не держи дублирующий poll/retry state поверх coordinator.
- `app/src/main/java/ru/foric27/cluster/UdpPeerReachabilityChecker.kt` — peer ping, TTL cache и обработка отсутствующего `ping`; это отдельный helper, не возвращай этот state обратно в сервис.
- `app/src/main/java/ru/foric27/cluster/MainActivity.kt` — launcher entrypoint; обычный запуск старается оставить UI в фоне после старта сервиса.
- `app/src/main/java/ru/foric27/cluster/ProductConfig.kt` — продуктовые дефолты и wire-контракты.
- `app/src/main/java/ru/foric27/cluster/RuntimeConfig.kt` + `DeveloperActivity.kt` — runtime overrides поверх `ProductConfig`.
- `app/src/main/java/ru/foric27/cluster/UpdateServerManager.kt` + `UpdateFileLocator.kt` — логика поиска `ICUpdate.zip` / `.sig` и lifecycle встроенного FTP.
- `app/src/main/AndroidManifest.xml` — реальные exported-компоненты, boot/media receivers и OEM focus actions.

## Проверенные команды

- Локальная debug-сборка на Windows: `./gradlew.bat assembleDebug`
- Unit tests: `./gradlew.bat testDebugUnitTest`
- Lint: `./gradlew.bat lintDebug`
- Полная локальная проверка после безопасных правок: `$env:JAVA_HOME="$PWD/.tools/jdk-21.0.10"; $env:PATH="$env:JAVA_HOME/bin;$env:PATH"; ./gradlew.bat assembleDebug testDebugUnitTest lintDebug assembleRelease`
- Release-сборка: `$env:JAVA_HOME="$PWD/.tools/jdk-21.0.10"; $env:PATH="$env:JAVA_HOME/bin;$env:PATH"; ./gradlew.bat assembleRelease`
- Установка release APK на устройство: `.\.tools\platform-tools\adb.exe install -r .\app\build\outputs\apk\release\app-release.apk`
- Проверка подключенных устройств: `.\.tools\platform-tools\adb.exe devices`
- Установка release APK через MCP: `mobile-mcp_mobile_install_app` с путём `app/build/outputs/apk/release/app-release.apk`
- Экспорт signing secrets для GitHub Actions из локального `.tools/signing`: `powershell -ExecutionPolicy Bypass -File .\scripts\export-signing-secrets.ps1`
- CI на GitHub (`.github/workflows/android-release.yml`) собирает release через `./gradlew assembleRelease` на Java 21 + Android 37.
- При корректно заполненном `keystore.properties` локальный `assembleRelease` должен сразу выпускать подписанный `app/build/outputs/apk/release/app-release.apk`.
- `adb` на этом стенде лучше гонять только последовательно: не запускай несколько `adb`-команд параллельно и не смешивай install/logcat/shell в parallel tool calls, иначе соединение с `192.168.1.132:5555` периодически подвисает.
- Для подключения к девайсу `192.168.1.132:5555` по умолчанию опирайся на MCP-устройство `192.168.1.132:5555`; повторный `adb connect` нужен только если работаешь именно через local `adb` и соединение уже потеряно.

## Build и release quirks

- AGP: `com.android.application` `9.1.0`, Gradle wrapper `9.4.1`, `compileSdk/targetSdk` = 37, `android.builtInKotlin=true`.
- Локальные Gradle-запуски здесь лучше делать на project-local JDK 21 из `.tools/jdk-21.0.10`: системный Java 26 уже ломал `assembleRelease` на `jlink` при `android-36/37`.
- В `gradle.properties` уже зафиксированы `org.gradle.daemon=false` и `android.suppressUnsupportedCompileSdk=37.0`; не удивляйся single-use daemon и отсутствию warning про compileSdk.
- `compileSdk 37` на AGP 9.1 дает warning про unsupported compileSdk, но локальные `assembleDebug/test/lint/release` сейчас проходят.
- Release подпись берется либо из переменных окружения `ANDROID_KEYSTORE_FILE`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`, либо из локального `keystore.properties`.
- Если signing secrets не заданы, `assembleRelease` и CI все равно могут собрать unsigned release APK.
- В release включены `minifyEnabled true` и `shrinkResources true`; изменения в signing/release-потоке проверяй именно на release-сборке.

## Runtime-ограничения, которые легко сломать

- Не меняй без явной причины дефолты в `ProductConfig`: здесь зашиты целевой пакет/cluster component, IP/порты, root iface, FTP-имена и credentials.
- Целевое приложение сейчас зашито как `ru.yandex.yandexnavi` + `com.yandex.navi.cluster.ClusterNavigationActivity`; это продуктовый контракт, а не просто удобный default.
- Видео-поток по умолчанию фиксирован как `1920x640`; это часть продуктового контракта, а не просто UI-настройка.
- Сетевой режим здесь больше не переключаемый: стрим всегда идет через root-path и только через `eth0`; non-root fallback уже удален.
- `RootNetUtil.applyStaticIfaceNetwork()` теперь не просто назначает `192.168.40.1/24`, а добивает policy routing до `192.168.40.2` через `eth0`. Если трогаешь root route setup, проверяй на устройстве и `ip route get 192.168.40.2`, и новые логи `Проверка маршрута (...): маршрут через eth0 подтверждён`.
- `MainActivity` и recovery-path разделены: обычный launcher-старт уводит задачу в фон только после полного preflight по доступам, а recovery после boot/crash должен поднимать только сервис, без UI.
- `BootReceiver` обрабатывает не только `BOOT_COMPLETED` / `MY_PACKAGE_REPLACED`, но и USB mount/unmount события, сводя их к быстрому сигналу сервису обновить FTP-состояние.
- FTP/update flow зависит от `MANAGE_EXTERNAL_STORAGE` на Android 11+; без этого `UpdateServerManager` не поднимает сервер.
- Первый ручной запуск UI теперь обязан пройти preflight: `POST_NOTIFICATIONS`, `MANAGE_EXTERNAL_STORAGE`, исключение из battery optimization. Для двух последних Android не дает обычный runtime popup, поэтому приложение ведет пользователя в системные экраны до подтверждения доступа.
- На OEM вроде Teyes CC3 экран battery optimization может отсутствовать; `MainActivity` теперь не должен блокировать уход в фон только из-за этого. Если меняешь preflight, сохраняй fallback через `BatteryOptimizationManager.isSettingsFlowAvailable()`.
- `RootShell` теперь отдельно проверяет доступность root-утилит (`ip`, `ping`, `am`) и публикует пользовательское предупреждение, если команды нет. Если добавляешь новый root-path, сначала реши, нужна ли явная проверка утилиты через `su`.
- Shizuku/Sui backend больше не используется: privileged path в проекте теперь только через `su`. Не возвращай binder/permission flow Shizuku в `MainActivity`, `RootShell` или manifest без явного запроса.
- После последних декомпозиций ветка `startPipelineAsync(...)` уже вынесена в startup coordinators; следующий логичный cleanup-кандидат внутри `UdpStreamService` — `onStartCommand(...)` / `attemptRestart(...)`, а не возврат уже вынесенных startup/status/watchdog/network helpers обратно в сервис.
- Верхняя warning-панель в `MainActivity` теперь фактически журнал последних сообщений: кнопка `Очистить` чистит и локальный UI-буфер, и `AppWarningCenter`, а очередь предупреждений хранит до `128` сообщений. Не возвращай flood-публикацию одинаковых warning'ов в сервисе, иначе UI снова начнет лагать.
- Для восстановления после падения процесса есть `ClusterApp` + `ProcessRecoveryManager`; recovery должен поднимать только сервис. Если меняешь этот path, не возвращай автозапуск `MainActivity` и не убирай crash-loop guard.
- `UpdateFileLocator` специально не делает рекурсивный обход: ищет `ICUpdate.zip` и `ICUpdate.zip.sig` только в корне внутренней памяти и в корне USB-томов.
- В `AndroidManifest.xml` зашиты OEM/Yandex focus actions. Если трогаешь receiver/manifest, сохрани эту совместимость.
- `MANAGE_EXTERNAL_STORAGE` здесь оставлен сознательно как продуктовый контракт для FTP/update flow; если lint снова ругается на `ScopedStorage`, не удаляй permission автоматически, а сначала проверь, не ломает ли это встроенный update server.
- Exported boot/OEM focus receivers теперь должны оставаться под custom permission `ru.foric27.cluster.permission.PROTECTED_BROADCAST`; если меняешь manifest wiring, не снимай эту защиту молча.
- Launcher-иконка должна опираться на `docs/app-icon.svg` как на source of truth; если меняешь launcher resources, синхронно проверяй `ic_launcher_foreground`, legacy `mipmap-*` формы и не включай `monochrome`, если нужна фиксированная фирменная расцветка без themed icons.
- После обновления launcher-иконки учитывай, что лаунчер может держать старый кеш; если пользователь видит старую/светлую иконку, сначала проверь themed icon path и только потом подозревай, что APK не обновился.

## Тесты и область покрытия

- Автотесты — это JUnit 4 unit tests в `app/src/test/java/ru/foric27/cluster`; в основном они покрывают pure Kotlin-логику runtime config, network selection, sync и video timing.
- После изменений в root routing, permission preflight, process recovery, wake recovery или launcher icon одного `testDebugUnitTest` недостаточно: нужен хотя бы device smoke на устройстве, предпочтительно через MCP.
- Если меняешь чистую логику в `RuntimeConfig`, sync/network helper'ах или video timing, дешевле и безопаснее добавить/обновить unit test, чем проверять это только руками.
- Если меняешь `UdpStreamService`, recovery, root-network, USB/FTP или manifest wiring, одних unit tests недостаточно: нужен хотя бы ручной smoke на устройстве.

## Файловые и репозиторные мелочи

- `AGENTS.md`, `dist/`, `.tools/`, `keystore.properties`, APK/AAB и keystore-файлы уже в `.gitignore`; локальные toolchain и signed artifacts здесь сознательно вне git.
- Текстовые файлы держи в UTF-8; `.editorconfig` задает LF почти для всего, кроме `*.bat`.
- UI показывает версию через `BuildConfig.VERSION_NAME`; сейчас release-ветка уже на `versionCode 2` / `versionName 1.0.1`.
- **Временные файлы тестов** — скриншоты устройства (`screenshot_*.png`, `device_screenshot*.png`), логи (`*.log`, `crash_log.txt`, `full_log.txt`) и прочие артефакты smoke-тестирования **не должны попадать в git**. `.gitignore` уже содержит соответствующие паттерны; после тестов такие файлы следует удалять из рабочей директории, чтобы не захламлять проект.

## Недавние крупные изменения (апрель 2026)

Проведён полный аудит и исправление критических/высоких/средних проблем, а также очистка неиспользуемых ресурсов. Изменения прошли `assembleDebug`, `testDebugUnitTest`, `lintDebug`.

### Критические исправления
- **`UdpStreamService.onStartCommand`** — `startForeground` вынесен на самый верх метода и гарантированно вызывается на **всех** путях (FTP-refresh, невалидный конфиг, duplicate-start, catch-блоки). Исправляет `RemoteServiceException` при вызове через `startForegroundService`.
- **`VideoEncoder.safeStopInternal`** — порядок остановки перестроён: сначала `encoder.stop()`, затем `codecThread.join()`, и только потом освобождение GL/Surface. Исправляет GPU-крэши и нарушения lifecycle `MediaCodec`.
- **`UpdateServerManager.stopServerLocked`** — `ftpServer.stop()` вынесен за пределы `synchronized(lock)` в отдельный `performStop()`, чтобы не блокировать главный поток на неопределённое время.
- **`MainActivity.restartServer()`** — обёрнут в фоновый daemon-поток, чтобы не ANR-ить UI при перезапуске FTP.

### Высокий приоритет
- **`UdpStreamService`** — `sender` стал `@Volatile`; добавлен атомарный флаг `restartInProgress` под `serviceLock`; все `mainHandler.post` проверяют `sServiceRunning`; `stopInternalKeepService` синхронизирован через `serviceLock`.
- **`RootShell`** — `rootUnavailableCached` теперь сбрасывается в `clearToolCache()`.
- **`SyncHandler`** — `timeChanged` и `langChanged` стали `@Volatile`.
- **`VideoEncoder`** — `GlFrameComposer` обёрнут в `try/catch` с `release()` при неудаче; `encoder`, `virtualDisplay`, `codecHandler` — `@Volatile`.
- **`proguard-rules.pro`** — добавлен `-keep` для `StreamConfig` (реализует `Serializable` и передаётся через `Intent` extras).
- **`AndroidManifest.xml`** — добавлен `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`.

### Средний приоритет
- **Coordinators** — поля потоков (`UdpTransportStatsCoordinator`, `UdpConnectivityWatchdogCoordinator`, `UdpStatusSyncCoordinator`) и флаги (`UdpServiceAlerts`) стали `@Volatile`.
- **`AppWarningCenter`** — слушатели хранятся как `WeakReference<WarningListener>`, чтобы предотвратить утечку `Activity`.
- **`RootNetUtil`** — результаты таймаута (`result.timedOut`) больше не кешируются в `probeIfaceState` и `checkRouteTo`.

### Очистка ресурсов
- Удалены: `ic_launcher_monochrome.xml`, `backup_rules.xml`, `data_extraction_rules.xml`, пустой `mipmap-anydpi-v26/`.
- Из `AndroidManifest.xml` убраны `android:dataExtractionRules` и `android:fullBackupContent`.

### Качество кода
- Все raw `Thread`, создаваемые вручную (`UdpStreamService.launchWorker`, `RootShell.startReaderThread`, фоновый поток в `MainActivity`), теперь маркируются `isDaemon = true`.
- Нет мёртвого Kotlin-кода, старых TODO/FIXME, остатков Shizuku/Sui или non-root fallback.

## Проверка подписи APK (апрель 2026)

Добавлена runtime-проверка цифровой подписи APK для защиты от несанкционированной модификации:
- **`SignatureVerifier`** — извлекает SHA-256 первого сертификата подписи через `PackageManager` (GET_SIGNING_CERTIFICATES / GET_SIGNATURES) и сравнивает с ожидаемым хешем.
- **`ProductConfig.Security.EXPECTED_SIGNATURE_SHA256`** — константа с ожидаемым хешем release-подписи.
- **`MainActivity.onCreate`** — при несовпадении подписи показывает немодальный `AlertDialog` с сообщением о нарушении безопасности и принудительно закрывает приложение через `finish()` + `Process.killProcess()`.
- **Debug-сборки** — проверка подписи пропускается (`BuildConfig.DEBUG`), так как debug APK подписан другим ключом.
- **ProGuard** — добавлены `-keep` для `SignatureVerifier` и `ProductConfig$Security`, чтобы R8 не удалил проверку.

## Launcher-иконка и локализация (апрель 2026)

### Иконка
- Удалены плотностные `mipmap-{hdpi,mdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher.xml` и `ic_launcher_round.xml`, которые содержали неверный `<layer-list>` вместо `<adaptive-icon>`.
- Теперь только `mipmap-anydpi/ic_launcher.xml` и `mipmap-anydpi/ic_launcher_round.xml` с `<adaptive-icon>` — корректный adaptive icon для всех плотностей (minSdk=26).
- `AndroidManifest.xml` указывает `android:icon="@mipmap/ic_launcher"` и `android:roundIcon="@mipmap/ic_launcher_round"`.

### Локализация
- Добавлен `values-en/strings.xml` — полный английский перевод всех пользовательских строк.
- `values/strings.xml` остаётся на русском и служит default/fallback для всех локалей, кроме английской.
- Таким образом, **русский — язык по умолчанию**, английский подключается автоматически при системной локали en-*. Язык приложения не привязан жёстко к русскому; система Android сама выбирает подходящий `values-*` на основе языка устройства.

## Тема и адаптивные layout'ы (апрель 2026)

### Тема
- Тема приложения принудительно тёмная: `Theme.Material3.Dark.NoActionBar`.
- Убран `DayNight` — приложение всегда отображается в тёмной палитре независимо от системной темы.

### Адаптивные layout'ы
Добавлены альтернативные layout-ресурсы для разных ориентаций и размеров экрана:
- `layout-land/activity_main.xml` — двухколоночный layout для телефонов в landscape.
- `layout-sw600dp/activity_main.xml` — двухколоночный layout для планшетов в портретной ориентации.
- `layout-sw600dp-land/activity_main.xml` — двухколоночный layout для планшетов в landscape.
- `layout-land/activity_developer.xml` — landscape-версия экрана разработчика.
- `layout-sw600dp/activity_developer.xml` — планшетная версия экрана разработчика.
- `values-land/dimens.xml` — скорректированные отступы для landscape (меньшая высота панели уведомлений).

### Поддержка 2K/4K и больших экранов (апрель 2026)
Добавлены ресурсы для высоких разрешений и очень больших экранов:
- `values-xxxhdpi/dimens.xml` — адаптация для 2K/4K мобильных экранов (640dpi+, QHD/UHD телефоны). Увеличены шрифты и отступы для лучшей читаемости на очень высокой плотности пикселей.
- `values-sw720dp/dimens.xml` + `layout-sw720dp/` + `layout-sw720dp-land/` — ресурсы для больших планшетов 10" и выше (sw >= 720dp). Двухколоночные layout'ы с увеличенными текстом и отступами.
- `values-sw800dp/dimens.xml` + `layout-sw800dp/` + `layout-sw800dp-land/` — ресурсы для очень больших планшетов 12"+ и Android TV (sw >= 800dp). Ещё более крупные шрифты и щедрые отступы для комфортного просмотра с расстояния.
- Все layout'ы для sw720dp/sw800dp используют те же двухколоночные структуры, что и sw600dp, но с разными dimens через соответствующие values-квалификаторы.

## Диалог обнаружения обновления и разрешения (апрель 2026)

### Диалог обновления
- Добавлен `UpdateAlertActivity` — прозрачная Activity, показывающая `AlertDialog` при обнаружении нового обновления через периодический опрос.
- Диалог содержит информацию об обновлении (путь, размер, SHA-256) и две кнопки: «Обновить» (перезапускает FTP с политикой `USB_FIRST`) и «Отмена» (закрывает диалог).
- Activity использует `Theme.Translucent.NoTitleBar`, `excludeFromRecents="true"`, `noHistory="true"` — не остаётся в списке недавних.
- В `UdpUpdateServerCoordinator` добавлена логика отслеживания `lastKnownUpdateSha256` и throttle 5 минут между показами диалога (`ALERT_THROTTLE_MS`).

### Разрешения хранилища
- Для Android 10 и ниже (API < 30) добавлен runtime-запрос `READ_EXTERNAL_STORAGE` через `MainAccessPreflight`.
- На Android 11+ (API 30+) по-прежнему используется `MANAGE_EXTERNAL_STORAGE` с переходом в системные настройки.
- `StorageAccessManager.isAllFilesAccessGranted()` на API < 30 возвращает `true` без проверок, но теперь `MainAccessPreflight` явно запрашивает `READ_EXTERNAL_STORAGE` до запуска сервиса.
- Строки `main_read_storage_granted` и `main_read_storage_missing` добавлены в `values/strings.xml` (ru) и `values-en/strings.xml` (en).
