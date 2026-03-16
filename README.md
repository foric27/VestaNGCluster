# VestaNGCluster

Android-приложение для автомобильного cluster-сценария: захват изображения, кодирование в H.264, отправка потока по UDP и выдача обновлений через встроенный FTP-сервер.

## Что умеет проект

- захватывать изображение с virtual display
- кодировать поток в H.264
- отправлять видео и статусные пакеты по UDP
- поднимать встроенный FTP-сервер для выдачи `ICUpdate.zip` и `ICUpdate.zip.sig`
- работать с runtime-настройками через `SharedPreferences`
- устанавливаться и проверяться на Android-устройстве через `adb`

## Стек

- Android Gradle Plugin 9.1
- Gradle 9.3.1
- Java 17
- Kotlin через built-in поддержку AGP
- compileSdk 36 / targetSdk 36

## Сборка

Debug APK:

```powershell
.\gradlew.bat assembleDebug
```

Release APK:

```powershell
.\gradlew.bat assembleRelease
```

## Локальная подпись release APK

Release-подпись берётся из одного из двух источников:

1. Переменные окружения:
   - `ANDROID_KEYSTORE_FILE`
   - `ANDROID_KEYSTORE_PASSWORD`
   - `ANDROID_KEY_ALIAS`
   - `ANDROID_KEY_PASSWORD`
2. Локальный файл `keystore.properties` в корне проекта

Пример `keystore.properties`:

```properties
storeFile=C:\\path\\to\\release-keystore.jks
storePassword=...
keyAlias=...
keyPassword=...
```

Файл `keystore.properties` добавлен в `.gitignore`.

## GitHub Actions

В репозитории настроена автосборка через workflow:

- на каждый push в `main`
- вручную через `workflow_dispatch`

Workflow:

- собирает `release` APK
- создаёт или обновляет prerelease `main-latest`
- прикладывает APK в GitHub Releases
- подписывает APK, если в GitHub secrets добавлены signing-секреты

Необходимые secrets в GitHub:

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

## Где лежит локальный keystore

Локально ключ подписи можно хранить только вне git, например в:

`C:\my_soft\android\VestaNGClusterFlowStudio\.tools\signing\`

Эта папка уже игнорируется.

## Установка на устройство

Пример установки debug APK:

```powershell
& '.\.tools\platform-tools\adb.exe' connect 192.168.1.163:5555
& '.\.tools\platform-tools\adb.exe' -s 192.168.1.163:5555 install -r '.\app\build\outputs\apk\debug\app-debug.apk'
```

## Логи

Пример съёма логов приложения:

```powershell
& '.\.tools\platform-tools\adb.exe' -s 192.168.1.163:5555 logcat --pid=(& '.\.tools\platform-tools\adb.exe' -s 192.168.1.163:5555 shell pidof ru.foric27.cluster).Trim() -d -t 500
```

## Важно

- приватный ключ подписи нельзя коммитить в репозиторий
- workflow GitHub готов к подписанию, но без secrets будет собирать только неподписанный release APK
- для публичного репозитория использовать keystore только через GitHub secrets
