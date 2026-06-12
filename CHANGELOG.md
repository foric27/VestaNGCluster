# Changelog

Все значимые изменения проекта документируются в этом файле.

Формат основан на [Keep a Changelog](https://keepachangelog.com/ru/1.0.0/),
и проект придерживается [Semantic Versioning](https://semver.org/lang/ru/).

## [Unreleased]

### Added
- 11 новых тестовых файлов (~65 тест-кейсов): `Sha256UtilTest`, `UsbStoragePathMatcherTest`, `VdspStateTest`, `InMemoryLogBufferTest`, `FtpServerConfigTest`, `StreamConfigTest`, `UdpServiceRestartControllerTest`, `UdpStartupProbeCoordinatorTest`, `UdpNetworkPreparationCoordinatorTest`, `ProcessRecoveryManagerTest`, `UdpServiceAlertsTest`
- `kotlinx-coroutines-test` dependency для тестирования
- Подавление `LocalContextGetResourceValueCall` в `lint.xml` (новое правило compose-bom 2026.05.01)
- Документация: `docs/architecture.md`, `docs/api.md`, KDoc для ключевых классов

### Changed
- Compose BOM: `2025.05.01` → `2026.05.01`
- OkHttp: `5.3.2` → `5.4.0`
- `NoticePanel`: высота ограничена 200dp со внутренним скроллом
- `MediaCoverActivity`: удалён `finish()` из `onPause()` (чёрный экран)
- `UdpStreamService`: guard в `handleRestartPipelineAction()` — пропуск при неизменном режиме
- `MainActivity.onModeSelected()`: пропуск restart если режим не изменился
- Удалён дублирующий runtime USB receiver из `UdpStreamService` (BootReceiver покрывает все MEDIA_* broadcast)

### Fixed
- **Self-update:** `requestInstall()` разрешает установку при `signatureMismatch = true` (checksum уже проверен в `inspectDownloadedApk()`)
- **Self-update:** `AppUpdateInstallReceiver` использует правильный ключ `"android.content.pm.extra.STATUS"` вместо `Intent.EXTRA_INTENT`
- **Self-update:** `session.close()` перемещён внутрь try блока (race condition)
- **UI:** кнопка «Очистить» в `NoticePanel` теперь работает (Compose отслеживает параметр `trigger`)
- **UI:** режим мультимедиа показывает UI обложки вместо чёрного экрана
- **Pipeline:** предотвращение лишних restart при переключении режимов

### Security
- Все 32 Dependabot alerts dismissed (false positives — все зависимости на безопасных версиях)
- Все 24 Code Scanning alerts dismissed (false positives)
- Закрыты Dependabot PR #25 (compose-bom) и #26 (kotlin-compose 2.4.0 — несовместим с AGP 9.2.1)

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
