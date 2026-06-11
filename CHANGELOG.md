# Changelog

Все значимые изменения проекта документируются в этом файле.

Формат основан на [Keep a Changelog](https://keepachangelog.com/ru/1.0.0/),
и проект придерживается [Semantic Versioning](https://semver.org/lang/ru/).

## [Unreleased]

### Added
- Version catalog (`gradle/libs.versions.toml`) для централизации зависимостей
- ProGuard rules (`app/proguard-rules.pro`) для release minification
- Unit-тесты: `VideoEncoderTest`, `AppUpdateManagerTest`, `PersistentVirtualDisplayTest`

### Changed
- Включён `minifyEnabled` и `shrinkResources` для release builds
- `UdpStreamService`: объединены дублирующие методы `stopInternalKeepService`/`stopInternalKeepPipelineOnly` в единый `stopInternal(stopCoordinators)`
- Миграция зависимостей на version catalog в `build.gradle`

### Fixed
- **Критический:** `UpdateServerManager.setState()` — broadcast о состоянии FTP не отправлялся (lambda создана, но не вызвана)
- **Безопасность:** USB media receiver зарегистрирован с `RECEIVER_NOT_EXPORTED` вместо `RECEIVER_EXPORTED`
- **Безопасность:** Добавлен `CertificatePinner` для GitHub API в `AppUpdateManager`
- **Параллелизм:** `VideoEncoder.hasPendingSurfaceFrame` и `hasRenderedAnyFrame` помечены `@Volatile` для thread safety

### Security
- Удалена runtime-проверка подписи APK (SignatureVerifier)
- Удалены утёкшие signing secrets из git history
- Добавлен `.gitignore` для signing-скриптов

## [1.0.2] - 2026-05-30

### Fixed
- Исправлен UDP EINVAL fallback
- Исправлено дублирование медиа-событий
- Улучшена обработка frame drops в VideoEncoder
- Улучшено rate-limiting для повторяющихся ошибок

## [1.0.1] - 2026-05-21

### Fixed
- Исправлены UI regressions cluster overlay
- Улучшено логирование

## [1.0.0] - 2026-05-19

### Added
- Начальная публичная версия
- Video pipeline: VirtualDisplay → OpenGL → MediaCodec → UDP
- Root network management
- FTP server для OTA
- Runtime configuration
- Recovery scenarios
