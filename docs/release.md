# Release Process

## Автоматический релиз (рекомендуется)

GitHub Actions публикует rolling release только после того, как текущий commit успешно проходит локальные проверки в CI.

### Как это работает

1. Push в любую ветку запускает workflow `.github/workflows/ci.yml`.
2. Для `main` workflow `CI` выполняет:
   - `:app:testDebugUnitTest`
   - `lintDebug`
3. Только после успешного `CI` на `main` workflow `.github/workflows/android-release.yml` собирает release APK.
4. Release workflow публикует rolling release с тегом `main-latest`.
5. Перед upload старые rolling assets удаляются, поэтому в `main-latest` всегда остаётся только одна актуальная пара файлов.

### Что попадает в rolling release

- APK с именем `VestaNGCluster-{version}-{sha}.apk`
- checksum-файл `VestaNGCluster-{version}-{sha}.apk.sha256`
- release notes с:
  - `Version`
  - `Commit`
  - `Build Date` (MSK)
  - `Signing`

### In-app update

Встроенный self-update приложения использует GitHub Releases как backend и ожидает от release фиксированный формат.

- По умолчанию приложение проверяет rolling release `main-latest`.
- В developer screen можно переключить канал на `stable`.
- Для корректного определения новой версии release notes должны содержать строку формата:

```md
- **Version:** `1.0.2` (`3`)
```

- Для rolling build приложение также использует commit SHA из release notes / имени APK, чтобы различать сборки с одинаковым `versionCode`.
- В assets релиза обязательно должны присутствовать оба файла:
  - `VestaNGCluster-{version}-{sha}.apk`
  - `VestaNGCluster-{version}-{sha}.apk.sha256`

### Формат страницы релиза

Rolling release оформляется единообразно:

- **Заголовок:** `Vesta NG Cluster {version} — rolling build`
- **Build Info:**
  - Version — `versionName` (`versionCode`) из `app/build.gradle`
  - Commit — короткий SHA и ссылка на GitHub
  - Build Date — дата и время по московскому времени (MSK)
  - Signing — `SIGNED` или `UNSIGNED`
- **Downloads:**
  - `VestaNGCluster-{version}-{sha}.apk`
  - `VestaNGCluster-{version}-{sha}.apk.sha256`

## Ручной релиз

### Предварительные требования

- JDK 26
- Android SDK
- Настроенный `keystore.properties` или env secrets (см. [signing.md](signing.md))

### Сборка

```bash
export JAVA_HOME=/path/to/jdk-26
./gradlew assembleRelease
```

APK будет доступен по пути:
`app/build/outputs/apk/release/app-release.apk`

### Проверка подписи

```bash
# Проверить, что APK подписан
jarsigner -verify -verbose app/build/outputs/apk/release/app-release.apk

# Проверить сертификат APK
keytool -printcert -jarfile app/build/outputs/apk/release/app-release.apk
```

### Установка

```bash
adb push app/build/outputs/apk/release/app-release.apk /data/local/tmp/
adb shell pm install -r /data/local/tmp/app-release.apk
adb shell rm /data/local/tmp/app-release.apk
```

## Замечания по архитектуре релизов

- FTP OTA (`ICUpdate.zip`) и app self-update APK из GitHub Releases - это разные подсистемы.
- FTP OTA не обновляет APK самого приложения.
- App self-update использует `.apk` + `.apk.sha256`, GitHub API и стандартный Android installer через `FileProvider`.
- Root нужен для основного streaming/runtime-сценария, но не для самой установки APK self-update.

## Версионирование

Проект использует пару `versionName` / `versionCode` из `app/build.gradle`.

- `versionCode` нужен для обычного update path и Android installer.
- Для rolling release дополнительно важен commit SHA, потому что несколько rolling-сборок могут иметь один и тот же `versionCode`.

При значимых изменениях обновляйте `versionName` и `versionCode` осознанно.
