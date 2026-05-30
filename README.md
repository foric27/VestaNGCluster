# Vesta NG Cluster

<p align="center">
  <img src="docs/app-icon-1024.png" width="120" height="120" alt="Vesta NG Cluster Icon">
</p>

<p align="center">
  <a href="https://github.com/foric27/VestaNGCluster/actions/workflows/android-release.yml">
    <img src="https://github.com/foric27/VestaNGCluster/actions/workflows/android-release.yml/badge.svg" alt="Android Release">
  </a>
  <a href="https://github.com/foric27/VestaNGCluster/releases">
    <img src="https://img.shields.io/github/v/release/foric27/VestaNGCluster?include_prereleases" alt="Latest Release">
  </a>
  <a href="LICENSE">
    <img src="https://img.shields.io/badge/License-Apache%202.0-blue.svg" alt="License">
  </a>
</p>

**Vesta NG Cluster** — Android-приложение для вывода cluster/navigation UI на комбинацию приборов автомобиля.

Основной пайплайн: `VirtualDisplay` → OpenGL-компоновка → `MediaCodec` H.264 → UDP-передача.

## Возможности

- **Foreground-сервис трансляции** — устойчивая работа в фоне с автоматическим recovery
- **Виртуальный дисплей** — вывод навигационного интерфейса на dedicated display
- **H.264 видеокодирование** — аппаратное кодирование через MediaCodec
- **UDP-стриминг** — передача видео и статусных данных по сети
- **Встроенный FTP-сервер** — выдача обновлений `ICUpdate.zip` для IVI-клиента
- **Root-сеть** — автоматическая настройка IP, маршрутов и policy routing
- **Runtime-конфигурация** — настройки через экран разработчика без пересборки
- **Recovery-сценарии** — автоматическое восстановление после ошибок и потери сети

## Архитектура

```
┌─────────────────┐     ┌──────────────┐     ┌─────────────┐     ┌──────────┐
│  Target App     │────▶│ VirtualDisplay│────▶│ OpenGL/GL   │────▶│ MediaCodec│
│  (Navigation)   │     │  (1920x640)   │     │ Composer    │     │ H.264     │
└─────────────────┘     └──────────────┘     └─────────────┘     └────┬─────┘
                                                                      │
                                                                      ▼
┌─────────────────┐     ┌──────────────┐     ┌──────────────────────────┐
│   FTP Server    │◀────│  UDP Status  │◀────│      UDP Sender          │
│ (ICUpdate.zip)  │     │   Sync       │     │  (Video + Status Packets)│
└─────────────────┘     └──────────────┘     └──────────────────────────┘
```

## Требования

- Android 8.0+ (API 26)
- Root-доступ (для настройки сети)
- JDK 21
- Android SDK с platform `android-37.0`

## Сборка

```bash
# Убедитесь, что JAVA_HOME указывает на JDK 21
export JAVA_HOME=/path/to/jdk-21

# Сборка release APK
./gradlew assembleRelease

# Сборка debug APK
./gradlew assembleDebug

# Запуск тестов
./gradlew :app:testDebugUnitTest

# Линт
./gradlew lintDebug
```

## Установка

```bash
# Push APK на устройство
adb push app/build/outputs/apk/release/app-release.apk /data/local/tmp/

# Установка (обновление)
adb shell pm install -r /data/local/tmp/app-release.apk

# Очистка
adb shell rm /data/local/tmp/app-release.apk
```

## GitHub Release Signing

Для подписи release APK через GitHub Actions необходимо настроить Secrets:

1. Сгенерируйте release keystore:
   ```bash
   keytool -genkey -v -keystore release-keystore.jks -alias release \
     -keyalg RSA -keysize 2048 -validity 10000
   ```

2. Закодируйте в base64:
   ```bash
   base64 -w 0 release-keystore.jks
   ```

3. Добавьте в GitHub Secrets репозитория:
   - `ANDROID_KEYSTORE_BASE64` — base64-кодированный keystore
   - `ANDROID_KEYSTORE_PASSWORD` — пароль keystore
   - `ANDROID_KEY_ALIAS` — alias ключа
   - `ANDROID_KEY_PASSWORD` — пароль ключа

Подробнее см. [`docs/signing.md`](docs/signing.md).

## Структура проекта

```
.
├── app/
│   ├── src/main/java/ru/foric27/cluster/    # Основной Kotlin-код
│   ├── src/main/res/                        # Ресурсы, layout, строки
│   └── build.gradle                         # Конфигурация Android-модуля
├── docs/                                    # Документация
├── .github/workflows/                       # GitHub Actions
├── keystore.properties.example              # Пример конфигурации подписи
├── LICENSE                                  # Apache License 2.0
└── README.md                                # Этот файл
```

## Основные компоненты

- [`UdpStreamService.kt`](app/src/main/java/ru/foric27/cluster/UdpStreamService.kt) — orchestration сервиса, restart/recovery, статус, FTP
- [`VideoEncoder.kt`](app/src/main/java/ru/foric27/cluster/VideoEncoder.kt) — видеокодирование, VirtualDisplay, запуск target activity
- [`RootNetUtil.kt`](app/src/main/java/ru/foric27/cluster/RootNetUtil.kt) — root-команды для IP и маршрутов
- [`UpdateServerManager.kt`](app/src/main/java/ru/foric27/cluster/UpdateServerManager.kt) — update flow и FTP-сервер
- [`MainActivity.kt`](app/src/main/java/ru/foric27/cluster/MainActivity.kt) — основной экран и пользовательский контроль
- [`DeveloperActivity.kt`](app/src/main/java/ru/foric27/cluster/DeveloperActivity.kt) — runtime-настройки
- [`RuntimeConfig.kt`](app/src/main/java/ru/foric27/cluster/RuntimeConfig.kt) — runtime-переопределения
- [`ProductConfig.kt`](app/src/main/java/ru/foric27/cluster/ProductConfig.kt) — базовые дефолты

## Безопасность

- Signing secrets (keystore, пароли) **не хранятся** в репозитории
- Используйте GitHub Secrets для CI/CD подписи
- Локальная подпись через `keystore.properties` (не коммитьте в git)
- Подробнее см. [`SECURITY.md`](SECURITY.md)

## Contributing

Мы приветствуем вклады! См. [`CONTRIBUTING.md`](CONTRIBUTING.md) для правил и рекомендаций.

## Лицензия

Этот проект распространяется под лицензией Apache License 2.0.
См. [`LICENSE`](LICENSE) для подробностей.

## Благодарности

- [libsu](https://github.com/topjohnwu/libsu) — root shell execution
- [Apache MINA](https://mina.apache.org/) — FTP server core
- [Timber](https://github.com/JakeWharton/timber) — логирование
