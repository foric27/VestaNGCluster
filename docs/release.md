# Release Process

## Автоматический релиз (рекомендуется)

GitHub Actions автоматически собирает и публикует release APK при каждом push в `main`.

### Как это работает

1. Push в `main` запускает workflow `.github/workflows/android-release.yml`
2. Workflow собирает signed release APK (если настроены secrets)
3. APK публикуется в GitHub Releases с тегом `main-latest`

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

При обновлении rolling release страница полностью перезаписывается.

## Ручной релиз

### Предварительные требования

- JDK 21
- Android SDK
- Настроенный `keystore.properties` (см. [signing.md](signing.md))

### Сборка

```bash
export JAVA_HOME=/path/to/jdk-21
./gradlew assembleRelease
```

APK будет доступен по пути:
`app/build/outputs/apk/release/app-release.apk`

### Проверка подписи

```bash
# Проверить, что APK подписан
jarsigner -verify -verbose app/build/outputs/apk/release/app-release.apk

# Проверить SHA256 сертификата
keytool -printcert -jarfile app/build/outputs/apk/release/app-release.apk
```

### Установка

```bash
adb push app/build/outputs/apk/release/app-release.apk /data/local/tmp/
adb shell pm install -r /data/local/tmp/app-release.apk
adb shell rm /data/local/tmp/app-release.apk
```

## Версионирование

Проект использует Semantic Versioning:
- `versionCode` — в `app/build.gradle`
- `versionName` — в `app/build.gradle`

При значительных изменениях обновите оба значения.
