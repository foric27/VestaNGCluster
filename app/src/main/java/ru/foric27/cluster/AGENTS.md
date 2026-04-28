# AGENTS: app/src/main/java/ru/foric27/cluster

**Scope:** Kotlin source package  
**Shape:** flat package, coordinator architecture  
**Do not split into subpackages casually:** filename suffixes are the local architecture map.

---

## OVERVIEW

This package owns the whole runtime: `Application` bootstrap, launcher UI, foreground stream service, root network setup, video encoder, UDP/status transport, FTP OTA, recovery, UI mode persistence, and config overrides.

---

## WHERE TO LOOK

| Task | Files |
|------|-------|
| App bootstrap / permissions | `ClusterApp.kt`, `MainActivity.kt`, `MainAccessPreflight.kt`, `StorageAccessManager.kt`, `BatteryOptimizationManager.kt` |
| Service orchestration | `UdpStreamService.kt`, `UdpStartupFlowCoordinator.kt`, `UdpPipelineStartCoordinator.kt`, `UdpStartupProbeCoordinator.kt`, `UdpConnectivityWatchdogCoordinator.kt`, `UdpTransportStatsCoordinator.kt`, `UdpStatusSyncCoordinator.kt`, `Udp*Controller.kt` |
| Root networking | `RootNetUtil.kt`, `RootNetworkAddressing.kt`, `NetworkRootShell.kt`, `RootCommandRunner.kt`, `NetworkInterfaceSelector.kt` |
| Video pipeline | `VideoEncoder.kt`, `GlFrameComposer.kt`, `VideoCodecOutputProcessor.kt`, `VideoFrameTimingController.kt`, `PersistentVirtualDisplay.kt` |
| Yandex cluster launch | `VideoDisplayLauncher.kt`, `YandexLaunchTarget.kt`, `ClusterLaunchProxyActivity.kt`, `ClusterFocusRequestReceiver.kt` |
| FTP update | `UdpUpdateServerCoordinator.kt`, `UpdateServerManager.kt`, `UpdateFileLocator.kt`, `PreparedUpdateRepository.kt`, `EmbeddedFtpServerFactory.kt`, `FtpServerConfig.kt`, `Sha256Verifier.kt`, `UsbStoragePathMatcher.kt`, `UpdateAlertActivity.kt` |
| Runtime config | `ProductConfig.kt`, `RuntimeConfig.kt`, `RuntimeConfigStore.kt`, `RuntimeConfigPreferenceDataStore.kt`, `RuntimeConfigFieldSpecs.kt`, `DeveloperActivity.kt` |
| UI mode / sync | `AppSettings.kt`, `SyncHandler.kt`, `SyncPayloadBuilder.kt` |
| Recovery | `ClusterApp.kt`, `ProcessRecoveryManager.kt`, `AppRecoveryReceiver.kt`, `UdpServiceRecoveryScheduler.kt`, `UdpWakeRecoveryController.kt`, `UdpServiceRestartController.kt` |
| Warnings/status UI | `AppWarningCenter.kt`, `MainNoticeLog.kt`, `VdspState.kt`, `UdpServiceAlerts.kt`, `ConnectivityHealth.kt` |

---

## LARGE FILES / HOTSPOTS

| File | Risk |
|------|------|
| `UdpStreamService.kt` (~1200 lines) | façade only; keep new behavior in coordinators/managers |
| `RootNetUtil.kt` (~730 lines) | root command formatting and policy routing priorities; keep pure helpers in separate files when safe |
| `VideoEncoder.kt` (~620 lines) | MediaCodec/GL/VirtualDisplay ordering; codec thread assumptions |
| `UpdateServerManager.kt` (~570 lines) | lock/state/server lifecycle; never block with FTP stop under lock |
| `UpdateFileLocator.kt` (~400 lines) | non-recursive direct file roots + SAF fallback |
| `MainActivity.kt` / `DeveloperActivity.kt` | UI state and runtime override side effects |

---

## CONVENTIONS

- Coordinators receive callbacks from `UdpStreamService`; they should not own Android service lifecycle directly.
- Worker threads created for long-running loops must be daemon or managed through service helpers.
- Cross-thread service state is mostly `@Volatile`, `Atomic*`, or callback-mediated.
- `RuntimeConfig` mirrors `ProductConfig`; add new defaults to both config specs and UI strings.
- `AppSettings` owns user-selected cluster mode; keep this separate from `RuntimeConfig` product overrides.
- `ClusterApp` owns process-level bootstrap; do not scatter global initialization across activities/services.
- Pure parsing/formatting helpers may be extracted into focused flat-package files such as `RootNetworkAddressing.kt`; keep filenames responsibility-based.
- `AppWarningCenter` messages are de-duplicated and can outlive an Activity; clear stale domain warnings explicitly on success.
- `UpdateServerManager` state changes broadcast `ACTION_UPDATE_SERVER_STATE_CHANGED`; UI should observe state, not infer from sockets.
- `RootCommandRunner` is the generic one-shot libsu runner; `NetworkRootShell` is the persistent libsu wrapper restricted to network commands.

---

## CRITICAL SEQUENCES

### Video shutdown
Keep `VideoEncoder` release order:
1. stop encoder
2. join codec thread with timeout
3. release GL / Surface / VirtualDisplay references

### FTP lifecycle
- Create fresh Apache FTP server per start attempt.
- Assign `runningServer` only after successful `ftpServer.start()`.
- On failed start, best-effort `stop()` outside broader state assumptions.
- Same prepared update must preserve previous `RUNNING` state, not overwrite it with transient `PREPARING`/`ERROR`.

### Foreground service
- Every `onStartCommand` path must call `startForeground()` before returning or doing lengthy work.

### Startup flow
- Keep startup staged: foreground notification first → network preparation → UDP probe → video pipeline start → status/watchdog attachment.
- `UdpStreamService` should remain a façade; substantial startup/recovery changes belong in the matching coordinator/controller.

---

## ANTI-PATTERNS

- Do not add Shizuku/Sui, rootless routing, or `ping` reachability.
- Do not scan OTA files recursively or rename `ICUpdate.zip` / `ICUpdate.zip.sig` contract casually.
- Do not put network startup logic back into `UdpStreamService` if a coordinator already owns it.
- Do not call Apache FTP `stop()` while holding `UpdateServerManager.lock`.
- Do not silence root/security failures; publish actionable warnings.
- Do not move pure root parsing helpers back into `RootNetUtil` once they have dedicated tests.
