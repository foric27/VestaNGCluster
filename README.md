# VestaNGCluster

Android-приложение для кластерного сценария: вывод навигации на `VirtualDisplay`, кодирование в `H.264`, передача видеопотока по `UDP` и раздача обновлений через встроенный `FTP`-сервер.

## Что делает проект

- захватывает изображение со второго виртуального дисплея
- кодирует поток в `H.264`
- отправляет видео и служебные статусы по `UDP`
- поднимает встроенный `FTP` для раздачи `ICUpdate.zip` и `ICUpdate.zip.sig`
- восстанавливает стрим и сетевую часть после ошибок и части системных событий
- поддерживает runtime-настройки через `SharedPreferences`

## Технологии

- Android Gradle Plugin `9.1.0`
- Gradle `9.3.1`
- Java `17`
- Kotlin через встроенную поддержку AGP
- `compileSdk 36`
- `targetSdk 36`

## Структура проекта

```text
VestaNGClusterFlowStudio/
├─ app/
│  ├─ build.gradle
│  └─ src/main/
│     ├─ AndroidManifest.xml
│     ├─ java/ru/foric27/cluster/
│     └─ res/
├─ gradle/
├─ scripts/
├─ .github/workflows/
├─ build.gradle
├─ gradle.properties
├─ keystore.properties.example
└─ README.md
```

## Требования

- Android SDK и JDK `17`
- доступный `adb`
- для root-сетевого режима устройству нужно выдать `su`
- для раздачи обновлений приложению нужен доступ `MANAGE_EXTERNAL_STORAGE`

## Сборка

Debug APK:

```powershell
.\gradlew.bat assembleDebug
```

Release APK:

```powershell
.\gradlew.bat assembleRelease
```

## Подпись release

Release-подпись берётся из одного из двух источников:

1. Переменные окружения:
   - `ANDROID_KEYSTORE_FILE`
   - `ANDROID_KEYSTORE_PASSWORD`
   - `ANDROID_KEY_ALIAS`
   - `ANDROID_KEY_PASSWORD`
2. Локальный `keystore.properties` в корне проекта

Пример файла лежит в `keystore.properties.example`.

`keystore.properties` и сам keystore в git не хранятся.

## Установка на устройство

Подключение тестового устройства:

```powershell
& '.\.tools\platform-tools\adb.exe' connect 192.168.1.163:5555
```

Установка debug APK:

```powershell
& '.\.tools\platform-tools\adb.exe' -s 192.168.1.163:5555 install -r '.\app\build\outputs\apk\debug\app-debug.apk'
```

Запуск приложения:

```powershell
& '.\.tools\platform-tools\adb.exe' -s 192.168.1.163:5555 shell am start -W -n ru.foric27.cluster/.MainActivity
```

## Минимальный smoke-тест

После установки полезно проверить:

```powershell
& '.\.tools\platform-tools\adb.exe' -s 192.168.1.163:5555 shell dumpsys activity activities | Select-String 'topResumedActivity|ru.foric27.cluster'
```

```powershell
$pid = (& '.\.tools\platform-tools\adb.exe' -s 192.168.1.163:5555 shell pidof ru.foric27.cluster).Trim()
if ($pid) { & '.\.tools\platform-tools\adb.exe' -s 192.168.1.163:5555 logcat --pid=$pid -d -t 500 }
```

Если поднялся `FTP`, дополнительно:

```powershell
Test-NetConnection 192.168.1.163 -Port 2121
```

## Диагностика

Полезные признаки в логах:

- успешный root-сценарий сети: `Статический IP применён`
- успешный старт стрима: `Стрим успешно запущен`
- keepalive при статичной картинке: `Отправляю keepalive-кадр`
- восстановление после сна или ошибки: `Немедленное восстановление`
- снимок внутреннего состояния сервиса: `Снимок сервиса | ...`

Очистить `logcat` перед тестом:

```powershell
& '.\.tools\platform-tools\adb.exe' logcat -c
```

## GitHub Actions

В репозитории настроен workflow `Android Release`:

- запускается на каждый push в `main`
- умеет запускаться вручную через `workflow_dispatch`
- собирает `release` APK
- обновляет prerelease `main-latest`
- прикладывает APK к GitHub Releases

Для подписанной GitHub-сборки должны быть настроены secrets:

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

## Известные ограничения

- системный `displayId` назначает Android, жёстко зафиксировать его на конкретное число приложением нельзя
- без `su` нельзя штатно назначить статический IP на интерфейс `eth0`
- при первом запуске возможны системные экраны разрешений
