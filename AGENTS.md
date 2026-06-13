# AGENTS.md — VestaNGClusterFlowStudio

## Язык

- Всегда отвечай пользователю **на русском языке**.
- Все пояснения, планы, отчёты и комментарии к изменениям — **на русском языке**.
- Не раскрывай скрытую цепочку рассуждений; давай только краткое резюме: что сделано, почему, какие файлы изменены, что проверить.

## Роль

Действуй как инженер-практик: Android/Kotlin/XML, reverse engineering, APK/smali/jadx/apktool, shell/PowerShell, Git, логи, сетевые и автомобильные сценарии.

## Базовые правила

- Сначала изучай структуру проекта и существующий стиль.
- Делай минимальные, но полноценные изменения.
- Не расширяй scope без явного запроса.
- Сохраняй обратную совместимость, если пользователь не попросил иначе.
- Если есть риск поломки или неполная проверка — прямо укажи это.
- Всегда собирать именно `release`-версию; `debug` использовать только как промежуточную проверку.
- Для `release` всегда использовать ключ `foric27`, если пользователь явно не попросил другой ключ.
- Если Gradle/Android задачи на Windows падают на `R-def.txt`, `parse*Resources`, `lintVitalReport*`, Kotlin cache/backup или file-lock ошибках после параллельного запуска, сначала считай это инфраструктурной гонкой и перепроверь всё последовательным прогоном, а не расширяй scope фикса кода.

## Работа с AGENTS.md

- При старте проверяй корневой `AGENTS.md` и ближайший дочерний `AGENTS.md` в рабочей папке.
- Обновляй `AGENTS.md`, если меняются правила, структура, команды сборки/тестов/анализа.
- Не дублируй большие блоки между файлами: корень хранит общие правила, дочерние — только локальные.
- Держи `AGENTS.md` короткими: только то, что реально меняет поведение агента.

## Git

- Если проект не является git-репозиторием, а пользователь работает с исходниками проекта: `git init`.
- Коммиты делать только по явному запросу пользователя.

## Краткая карта проекта

- `app/` — Android-приложение (single-module, Kotlin + Compose + Material3).
- `app/src/main/java/ru/foric27/cluster/` — bootstrap: `ClusterApp`, `ClusterMode`, receivers.
- `app/src/main/java/ru/foric27/cluster/di/` — Koin DI-модули: `AppModule`, `VideoModule`, `UpdateModule`, `UiModule`.
- `app/src/main/java/ru/foric27/cluster/service/` — `UdpStreamService`, координаторы, контроллеры, `TcpHandshakeServer`.
- `app/src/main/java/ru/foric27/cluster/video/` — video pipeline: `VideoEncoder`, `GlFrameComposer`, I-frame buffer.
- `app/src/main/java/ru/foric27/cluster/network/` — root networking: `RootNetUtil`, `NetworkRootShell`.
- `app/src/main/java/ru/foric27/cluster/config/` — `ProductConfig`, `RuntimeConfig`, `AppSettings`.
- `app/src/main/java/ru/foric27/cluster/update/` — OTA/FTP + app self-update (GitHub Releases).
- `app/src/main/java/ru/foric27/cluster/ui/` — Activities (Compose, Material3), `YandexLaunchTarget`.
- `app/src/main/java/ru/foric27/cluster/util/` — утилиты: логирование, `VdspState`, `Sha256Util`.
- `app/src/main/res/` — XML resources и локализация `ru` + `values-en`.
- `app/src/test/java/ru/foric27/cluster/` — JVM unit tests (зеркальная структура).
- `test-client/` — Python-утилиты для UDP-диагностики.
- `oem/` — read-only OEM reference material (HUMAX/AvtoVAZ); использовать для сравнения, не редактировать.
- Build: Kotlin DSL (`build.gradle.kts`, `settings.gradle.kts`).
- DI: Koin 4.1.0 — модули в `di/`, инициализация в `ClusterApp.onCreate()`.
- Threading: корутины (`CoroutineWorker`) для координаторов, `Handler` для codec thread, `synchronized` для блокировок.
- Crash analytics: AppMetrica 8.3.0 — `CrashAnalytics` в `util/`, инициализация в `ClusterApp.onCreate()`. Timber integration для автоматического захвата логов.

## Ключевые домены

- Video pipeline: `VirtualDisplay -> OpenGL -> MediaCodec -> UDP`.
- I-frame buffer: `UdpSender` буферизует последний IDR-кадр и переотправляет при подключении нового клиента (keep-alive).
- TCP handshake: `TcpHandshakeServer` на порту 5151 — обнаружение подключения приёмника кластера (OEM паттерн).
- Root network: авто-поиск USB iface, policy routing, route pinning.
- Launch: `am start --display` / proxy-activity / MED overlay.
- OTA: встроенный FTP и поиск `ICUpdate.zip`.
- App update: self-update APK из GitHub Releases (`main-latest` / `latest`) без root через системный installer.
- OEM reference: `oem/ClusterRendererServiceExtension` — штатный рендерер кластера (HUMAX), эталонная архитектура.

## Локальные AGENTS

- `app/src/main/java/ru/foric27/cluster/AGENTS.md` — архитектура Kotlin/runtime.
- `app/src/main/res/AGENTS.md` — resources/localization.
- `app/src/test/java/ru/foric27/cluster/AGENTS.md` — unit tests.
- `test-client/AGENTS.md` — Python UDP tools.

## Команды

```powershell
# Требование: JDK 26 (поддерживается Gradle 9.4.0+).
# Системный JDK 26 используется по умолчанию.
./gradlew.bat assembleDebug
./gradlew.bat :app:testDebugUnitTest
./gradlew.bat lintDebug
# Основная целевая сборка: release, подписанная ключом foric27.
# Release подписан, minify + shrinkResources включены.
./gradlew.bat assembleRelease
```

## CI / Release

- GitHub Actions: сначала `CI` (`:app:testDebugUnitTest` + `lintDebug`), потом `Android Release`.
- `CI` должен запускаться на каждый `push` в любую ветку и на каждый `pull_request` в `main`, чтобы каждый отправленный commit проходил тесты и lint.
- `Android Release` должен собирать и публиковать APK только из того `SHA`, который уже успешно прошёл `CI` на `main`.
- Rolling release `main-latest` должен содержать только одну актуальную пару assets (`.apk` + `.sha256`); старые rolling assets нужно удалять перед upload.

## Local Git Hooks

- Версионируемый hook лежит в `.githooks/pre-push`.
- Локально должен быть подключён `core.hooksPath=.githooks`, чтобы перед каждым `git push` запускались `:app:testDebugUnitTest` и `lintDebug`.
- Для осознанного обхода hook можно временно выставить `SKIP_LOCAL_PRE_PUSH_TESTS=1`, но по умолчанию обход не использовать.

## Установка на устройство

- Только обновление: `pm install -r`.
- Путь: push APK в `/data/local/tmp/` -> install -> удалить temp-файл.
- Не использовать `adb uninstall`, если пользователь явно не попросил.

## Cleanup

- После установки: удалять `/data/local/tmp/app-release*.apk`.
- После локальных сборок не оставлять мусор в проекте: `.gradle/`, `.kotlin/`, `build/`, `app/build/`, `.sisyphus/`, `opencode.json`, временные логи/скриншоты.
- `.tools/` и `local.properties` не удалять без явного запроса.
- Перед коммитом `git status --short` должен показывать только осознанные изменения.
