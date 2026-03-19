# Vesta NG Cluster

![Иконка приложения](docs/app-icon-1024.png)

`Vesta NG Cluster` — Android-приложение для вывода cluster/navigation UI на комбинацию приборов.  
Основной пайплайн проекта: `VirtualDisplay` -> OpenGL-компоновка -> `MediaCodec` H.264 -> UDP-передача.

## Что делает приложение

- запускает и удерживает foreground-сервис трансляции;
- выводит навигационный интерфейс на виртуальный дисплей;
- кодирует изображение в `H.264`;
- отправляет видео и статусные данные по `UDP`;
- поднимает встроенный `FTP` для выдачи `ICUpdate.zip` и `ICUpdate.zip.sig`;
- восстанавливает трансляцию после ошибок, потери сети и части системных событий.

## Ключевые особенности

- фиксированное разрешение видеопотока `1920x640`;
- root-сеть со статическим IP для целевого сценария;
- status sync на отдельном UDP-порту;
- режимы и runtime-настройки через экран разработчика;
- совместимость с cluster focus broadcast заводских приложений;
- автоматические recovery-сценарии для сервиса, стрима и FTP.

## Структура проекта

```text
.
├── app/
│   ├── src/main/java/ru/foric27/cluster/
│   ├── src/main/res/
│   └── build.gradle
├── docs/
├── scripts/
├── .github/workflows/
├── keystore.properties.example
└── README.md
```

## Важные каталоги и файлы

- [`app/src/main/java/ru/foric27/cluster`](app/src/main/java/ru/foric27/cluster) — основной Kotlin-код проекта
- [`app/src/main/res`](app/src/main/res) — ресурсы, layout, строки, иконки
- [`app/src/main/AndroidManifest.xml`](app/src/main/AndroidManifest.xml) — manifest приложения
- [`app/build.gradle`](app/build.gradle) — конфигурация Android-модуля
- [`dist`](dist) — локально сохранённые production APK
- [`docs/app-icon.svg`](docs/app-icon.svg) — крупная SVG-версия иконки приложения

## Основные компоненты

- [`UdpStreamService.kt`](app/src/main/java/ru/foric27/cluster/UdpStreamService.kt) — orchestration сервиса, restart/recovery, статус, FTP и сеть
- [`VideoEncoder.kt`](app/src/main/java/ru/foric27/cluster/VideoEncoder.kt) — видеокодирование, `VirtualDisplay`, запуск target activity
- [`RootNetUtil.kt`](app/src/main/java/ru/foric27/cluster/RootNetUtil.kt) — root-команды для IP и маршрутов
- [`UpdateServerManager.kt`](app/src/main/java/ru/foric27/cluster/UpdateServerManager.kt) — update flow и FTP-сервер
- [`MainActivity.kt`](app/src/main/java/ru/foric27/cluster/MainActivity.kt) — основной экран и пользовательский контроль
- [`DeveloperActivity.kt`](app/src/main/java/ru/foric27/cluster/DeveloperActivity.kt) — runtime-настройки для разработчика
- [`RuntimeConfig.kt`](app/src/main/java/ru/foric27/cluster/RuntimeConfig.kt) — runtime-переопределения поверх `ProductConfig`
- [`ProductConfig.kt`](app/src/main/java/ru/foric27/cluster/ProductConfig.kt) — базовые дефолты и wire-контракты

## Как использовать

- установите приложение на устройство;
- запустите [`MainActivity`](app/src/main/java/ru/foric27/cluster/MainActivity.kt);
- дайте необходимые системные разрешения;
- при необходимости откройте экран разработчика и измените runtime-настройки;
- для production-версии используйте APK из каталога [`dist`](dist).

## Что важно знать

- поток всегда передаётся в размере `1920x640`, даже если UI адаптирован под другой экран;
- язык панели ограничен `ru` и `en`;
- root-интерфейс по умолчанию — `eth0`, а его изменение доступно только через экран разработчика;
- внешний viewer на ПК может показывать decode-ошибки при слишком большом `UDP max payload`, поэтому для такой проверки обычно используют диапазон `1200-1400`.

## GitHub Actions

В репозитории настроен workflow [`Android Release`](.github/workflows/android-release.yml), который собирает релизный APK и публикует его в GitHub Releases.
