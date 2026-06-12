# AGENTS: Kotlin runtime

## Scope

Корневой пакет `ru.foric27.cluster` содержит bootstrap-файлы. Основная логика разнесена по подпакетам.

## Структура пакетов

- `cluster/` — `ClusterApp`, `ClusterMode`, `BootReceiver`, `AppRecoveryReceiver`, `ClusterFocusRequestReceiver`
- `cluster/service/` — `UdpStreamService`, `StreamConfig`, `SyncHandler`, `UdpSender`, координаторы/контроллеры
- `cluster/service/coordinator/` — `Udp*Coordinator` (8 шт.)
- `cluster/service/controller/` — `UdpWakeRecoveryController`
- `cluster/video/` — `VideoEncoder`, `PersistentVirtualDisplay`, `GlFrameComposer`, pipeline
- `cluster/network/` — `RootNetUtil`, `NetworkRootShell`, routing, addressing
- `cluster/config/` — `ProductConfig`, `RuntimeConfig`, `AppSettings`
- `cluster/update/` — `AppUpdateManager`, FTP/OTA, `Sha256Verifier`
- `cluster/ui/` — Activities, `YandexLaunchTarget`, `MediaCoverState`
- `cluster/util/` — `AppWarningCenter`, logging, `VdspState`, `Sha256Util`

## Hotspots

- `service/UdpStreamService.kt`
- `network/RootNetUtil.kt`
- `video/VideoEncoder.kt`
- `update/UpdateServerManager.kt`
- `update/AppUpdateManager.kt`

## Локальные правила

- `UdpStreamService.kt` — фасад/оркестратор; не раздувать его новой логикой без необходимости.
- `RootNetUtil.kt` и route-планирование: не ломать policy routing, host pinning и auto USB iface.
- `VideoEncoder.kt` / `PersistentVirtualDisplay.kt`: порядок release критичен.
- `ProductConfig` — immutable defaults; пользовательские overrides идут через `RuntimeConfig`.
- Все новые пользовательские настройки добавляй в `DeveloperActivity` одновременно с backend-логикой.
- App self-update не смешивать с FTP/USB OTA: это отдельный поток, отдельные строки и отдельный UX.
- Комментарии в коде — только на русском языке.
