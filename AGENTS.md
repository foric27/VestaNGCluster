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

- `app/` — Android-приложение (single-module, Kotlin + XML/ViewBinding, без Compose).
- `app/src/main/java/ru/foric27/cluster/` — основная runtime-логика приложения.
- `app/src/main/res/` — XML layout/resources и локализация `ru` + `values-en`.
- `app/src/test/java/ru/foric27/cluster/` — JVM unit tests.
- `test-client/` — Python-утилиты для UDP-диагностики.
- `oem/` — read-only OEM reference material; использовать для сравнения, не редактировать.

## Ключевые домены

- Video pipeline: `VirtualDisplay -> OpenGL -> MediaCodec -> UDP`.
- Root network: авто-поиск USB iface, policy routing, route pinning.
- Launch: `am start --display` / proxy-activity / MED overlay.
- OTA: встроенный FTP и поиск `ICUpdate.zip`.
- App update: self-update APK из GitHub Releases (`main-latest` / `latest`) без root через системный installer.

## Локальные AGENTS

- `app/src/main/java/ru/foric27/cluster/AGENTS.md` — архитектура Kotlin/runtime.
- `app/src/main/res/AGENTS.md` — layouts/resources/localization.
- `app/src/test/java/ru/foric27/cluster/AGENTS.md` — unit tests.
- `test-client/AGENTS.md` — Python UDP tools.

## Команды

```powershell
# Требование: JDK 21 LTS. Предпочтительно system JDK 21;
# repo `.tools/jdk-21.0.10` использовать только как fallback.
$env:JAVA_HOME="C:\Program Files\Java\jdk-21"; $env:PATH="$env:JAVA_HOME/bin;$env:PATH"
./gradlew.bat assembleDebug
./gradlew.bat :app:testDebugUnitTest
./gradlew.bat lintDebug
# Основная целевая сборка: release, подписанная ключом foric27.
# Minify/shrink/proguard для app release отключены.
./gradlew.bat assembleRelease
```

## CI / Release

- GitHub Actions: сначала `CI` (`:app:testDebugUnitTest` + `lintDebug`), потом `Android Release`.
- `Android Release` должен собирать и публиковать APK только из того `SHA`, который уже успешно прошёл `CI` на `main`.
- Rolling release `main-latest` должен содержать только одну актуальную пару assets (`.apk` + `.sha256`); старые rolling assets нужно удалять перед upload.

## Установка на устройство

- Только обновление: `pm install -r`.
- Путь: push APK в `/data/local/tmp/` -> install -> удалить temp-файл.
- Не использовать `adb uninstall`, если пользователь явно не попросил.

## Cleanup

- После установки: удалять `/data/local/tmp/app-release*.apk`.
- После локальных сборок не оставлять мусор в проекте: `.gradle/`, `.kotlin/`, `build/`, `app/build/`, `.sisyphus/`, `opencode.json`, временные логи/скриншоты.
- `.tools/` и `local.properties` не удалять без явного запроса.
- Перед коммитом `git status --short` должен показывать только осознанные изменения.
