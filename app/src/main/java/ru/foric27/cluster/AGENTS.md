# AGENTS: Kotlin runtime

## Scope

Этот каталог содержит основную runtime-логику приложения. Пакет плоский — **не раскладывать по подпакетам без явной причины**.

## Где искать

- Bootstrap / app lifecycle: `ClusterApp.kt`, `MainActivity.kt`, `MainAccessPreflight.kt`
- Service orchestration: `UdpStreamService.kt`, `Udp*Coordinator.kt`, `Udp*Controller.kt`
- Root/network: `RootNetUtil.kt`, `RootNetworkAddressing.kt`, `NetworkInterfaceSelector.kt`, `NetworkRootShell.kt`
- Video pipeline: `VideoEncoder.kt`, `PersistentVirtualDisplay.kt`, `VideoCodecOutputProcessor.kt`
- Launch / display: `VideoDisplayLauncher.kt`, `YandexLaunchTarget.kt`, `ClusterLaunchProxyActivity.kt`, `MediaCoverActivity.kt`
- Runtime config: `ProductConfig.kt`, `RuntimeConfig.kt`, `RuntimeConfigStore.kt`, `RuntimeConfigFieldSpecs.kt`, `DeveloperActivity.kt`
- OTA/FTP: `UpdateServerManager.kt`, `UpdateFileLocator.kt`, `FtpServerConfig.kt`, `EmbeddedFtpServerFactory.kt`

## Локальные правила

- `UdpStreamService.kt` — фасад/оркестратор; не раздувать его новой логикой без необходимости.
- `RootNetUtil.kt` и route-планирование: не ломать policy routing, host pinning и auto USB iface.
- `VideoEncoder.kt` / `PersistentVirtualDisplay.kt`: порядок release критичен.
- MED/NAVI overlay-логика чувствительна к theme/layout/display launch path.
- `ProductConfig` — immutable defaults; пользовательские overrides идут через `RuntimeConfig`.
- Комментарии в коде — только на русском языке.

## Hotspots

- `UdpStreamService.kt`
- `RootNetUtil.kt`
- `VideoEncoder.kt`
- `UpdateServerManager.kt`

Перед правками в этих файлах сначала читай код, потом меняй минимально.
