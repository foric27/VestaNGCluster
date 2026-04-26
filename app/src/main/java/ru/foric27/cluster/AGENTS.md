# AGENTS: app/src/main/java/ru/foric27/cluster

**Scope:** Kotlin source package  
**Shape:** flat package, coordinator architecture  
**Do not split into subpackages casually:** filename suffixes are the local architecture map.

---

## OVERVIEW

This package owns the whole runtime: launcher UI, foreground stream service, root network setup, video encoder, UDP/status transport, FTP OTA, recovery, and config overrides.

---

## WHERE TO LOOK

| Task | Files |
|------|-------|
| App launch / permissions | `MainActivity.kt`, `MainAccessPreflight.kt`, `StorageAccessManager.kt`, `BatteryOptimizationManager.kt` |
| Service orchestration | `UdpStreamService.kt`, `Udp*Coordinator.kt`, `Udp*Controller.kt` |
| Root networking | `RootNetUtil.kt`, `NetworkRootShell.kt`, `RootCommandRunner.kt`, `NetworkInterfaceSelector.kt` |
| Video pipeline | `VideoEncoder.kt`, `GlFrameComposer.kt`, `VideoCodecOutputProcessor.kt`, `VideoFrameTimingController.kt`, `PersistentVirtualDisplay.kt` |
| Yandex cluster launch | `VideoDisplayLauncher.kt`, `YandexLaunchTarget.kt`, `ClusterLaunchProxyActivity.kt`, `ClusterFocusRequestReceiver.kt` |
| FTP update | `UdpUpdateServerCoordinator.kt`, `UpdateServerManager.kt`, `UpdateFileLocator.kt`, `PreparedUpdateRepository.kt`, `EmbeddedFtpServerFactory.kt`, `FtpServerConfig.kt`, `Sha256Verifier.kt` |
| Runtime config | `ProductConfig.kt`, `RuntimeConfig.kt`, `RuntimeConfigStore.kt`, `RuntimeConfigFieldSpecs.kt`, `DeveloperActivity.kt` |
| Recovery | `ProcessRecoveryManager.kt`, `AppRecoveryReceiver.kt`, `UdpServiceRecoveryScheduler.kt`, `UdpWakeRecoveryController.kt`, `UdpServiceRestartController.kt` |
| Warnings/status UI | `AppWarningCenter.kt`, `MainNoticeLog.kt`, `VdspState.kt`, `UdpServiceAlerts.kt` |

---

## LARGE FILES / HOTSPOTS

| File | Risk |
|------|------|
| `UdpStreamService.kt` (~1000 lines) | façade only; keep new behavior in coordinators/managers |
| `VideoEncoder.kt` (~780 lines) | MediaCodec/GL/VirtualDisplay ordering; codec thread assumptions |
| `RootNetUtil.kt` (~580 lines) | root command formatting and policy routing priorities |
| `UpdateServerManager.kt` (~470 lines) | lock/state/server lifecycle; never block with FTP stop under lock |
| `UpdateFileLocator.kt` (~360 lines) | non-recursive direct file roots + SAF fallback |
| `MainActivity.kt` / `DeveloperActivity.kt` | UI state and runtime override side effects |

---

## CONVENTIONS

- Coordinators receive callbacks from `UdpStreamService`; they should not own Android service lifecycle directly.
- Worker threads created for long-running loops must be daemon or managed through service helpers.
- Cross-thread service state is mostly `@Volatile`, `Atomic*`, or callback-mediated.
- `RuntimeConfig` mirrors `ProductConfig`; add new defaults to both config specs and UI strings.
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

---

## ANTI-PATTERNS

- Do not add Shizuku/Sui, rootless routing, or `ping` reachability.
- Do not scan OTA files recursively or rename `ICUpdate.zip` / `ICUpdate.zip.sig` contract casually.
- Do not put network startup logic back into `UdpStreamService` if a coordinator already owns it.
- Do not call Apache FTP `stop()` while holding `UpdateServerManager.lock`.
- Do not silence root/security failures; publish actionable warnings.
