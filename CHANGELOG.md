# Changelog

Все значимые изменения проекта документируются в этом файле.

Формат основан на [Keep a Changelog](https://keepachangelog.com/ru/1.0.0/),
и проект придерживается [Semantic Versioning](https://semver.org/lang/ru/).

## [Unreleased]

### Security
- Удалена runtime-проверка подписи APK (SignatureVerifier)
- Удалены утёкшие signing secrets из git history
- Добавлен `.gitignore` для signing-скриптов

### Changed
- Улучшено оформление репозитория для open-source
- Добавлена лицензия Apache 2.0
- Обновлен README с badges и архитектурой

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
