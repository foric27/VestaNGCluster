# PROJECT KNOWLEDGE BASE

**Generated:** 2026-04-27  
**Commit:** `4f9c2b7`  
**Branch:** `main`  
**Language:** Russian default, English only in `values-en/`  
**Stack:** single-module Android app, Kotlin + XML/ViewBinding, no Compose

---

## OVERVIEW

Android-приложение транслирует cluster/navigation UI на автомобильную комбинацию приборов.
Основной pipeline: `VirtualDisplay` → OpenGL → `MediaCodec` H.264 → UDP video/status, плюс встроенный FTP для OTA `ICUpdate.zip`.

---

## STRUCTURE

```
VestaNGClusterFlowStudio/
├── app/                                  # single Android application module
│   ├── build.gradle                      # AGP 9.1, ViewBinding, release shrink/minify
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml       # app components + privileged permissions
│       │   ├── java/ru/foric27/cluster/  # flat Kotlin package, coordinators/managers
│       │   └── res/                      # adaptive XML layouts + ru/en strings
│       └── test/java/ru/foric27/cluster/ # JVM JUnit 4 tests only
├── .github/workflows/                    # release + opencode workflows
├── docs/                                 # app icon sources
├── scripts/                              # signing-secret helper
└── gradle/                               # wrapper
```

---

## WHERE TO LOOK

| Task | Location | Notes |
|------|----------|-------|
| Service lifecycle | `UdpStreamService.kt` | Foreground-service facade; `startForeground()` on every path |
| Root network | `RootNetUtil.kt`, `UdpNetworkPreparationCoordinator.kt` | `su`, `eth0`, policy routing, iptables marks |
| Video capture/encode | `VideoEncoder.kt`, `GlFrameComposer.kt`, `PersistentVirtualDisplay.kt` | shutdown order is product-critical |
| Navigator launch | `VideoDisplayLauncher.kt`, `YandexLaunchTarget.kt`, `ClusterLaunchProxyActivity.kt` | root-only `am start --display` via proxy-activity |
| FTP/OTA | `UdpUpdateServerCoordinator.kt`, `UpdateServerManager.kt`, `UpdateFileLocator.kt` | non-recursive `ICUpdate.zip` + `.sig` discovery |
| Runtime overrides | `RuntimeConfig.kt`, `RuntimeConfigStore.kt`, `RuntimeConfigFieldSpecs.kt`, `DeveloperActivity.kt` | Developer screen edits config live |
| App recovery | `UdpServiceRestartController.kt`, `UdpServiceRecoveryScheduler.kt`, `UdpWakeRecoveryController.kt`, `ProcessRecoveryManager.kt` | backoff, alarm recovery, crash-loop guard |
| UI/resources | `app/src/main/res/AGENTS.md` | layout qualifiers and ru/en strings |
| Unit tests | `app/src/test/java/ru/foric27/cluster/AGENTS.md` | JVM-only tests and reflection patterns |

---

## CODE MAP

| Symbol/File | Type | Role |
|-------------|------|------|
| `UdpStreamService` | `Service` | owns coordinators, workers, notification, recovery wiring |
| `VideoEncoder` | class | VirtualDisplay + MediaCodec + GL lifecycle |
| `RootNetUtil` | object | privileged route/IP/iptables command formatting and execution |
| `UpdateServerManager` | object | validates update pair, prepares FTP root, owns Apache FtpServer |
| `RuntimeConfig` / `ProductConfig` | objects | runtime overrides over immutable product defaults |
| `MainActivity` | activity | permission preflight, status UI, service start |
| `BootReceiver` | receiver | boot/package/USB events |
| `ClusterLaunchProxyActivity` | activity | transparent Yandex cluster-focus proxy |

LSP is unavailable in this environment (`kotlin-lsp` missing); use AST/direct reads for symbol discovery.

---

## CONVENTIONS

- Flat package is intentional: categorize by filename suffix, not subpackage.
- `*Coordinator` = lifecycle orchestration owned by `UdpStreamService`.
- `*Manager` = singleton `object` utility/state owner.
- `*Controller` = state machine/control logic.
- Product defaults live in `ProductConfig`; user overrides go through `RuntimeConfig`/`RuntimeConfigStore`.
- UI language set is exactly `ru` + `en`; add/update both string files.
- OTA lookup is non-recursive: only storage root of internal/USB.

---

## SLEEP / WAKE BEHAVIOR

- При `ACTION_SCREEN_OFF` стрим останавливается и `PersistentVirtualDisplay` полностью освобождается (`releaseAll()`).
- При `ACTION_SCREEN_ON` / `ACTION_USER_PRESENT` стрим перезапускается через `attemptRestart()`.
- Это экономит заряд: на сне не держим VirtualDisplay, Surface, MediaCodec и wake-lock.

## ANTI-PATTERNS (THIS PROJECT)

| Forbidden | Why |
|-----------|-----|
| Shizuku/Sui or non-root network fallback | Product requires `su` privileged path only |
| Root ping / ICMP reachability | Removed; use UDP probe/route checks |
| Moving startup logic back into service body | Coordinators own orchestration |
| Missing `startForeground()` path | Causes foreground-service crash/ANR |
| Changing encoder shutdown order | GPU crashes; stop → join → release |
| FTP stop while holding manager lock | Can block main/service paths |
| Recursive update ZIP scanning | Product contract: root-only internal/USB |
| Building with system Java 26 | Breaks Android tooling; use local JDK 21 |
| Direct intent launch for navigator | Removed; root-only `am start` for device compatibility |
| Recreating VirtualDisplay on stream restart | Use `PersistentVirtualDisplay.acquire()` / `detachSurface()` / `releaseAll()` |
| Hardcoding navigator or target app names | Use `RuntimeConfig.TargetApp` and generic string resources |

---

## COMMANDS

```powershell
# Use local JDK 21 for reliable Android builds
$env:JAVA_HOME="$PWD/.tools/jdk-21.0.10"; $env:PATH="$env:JAVA_HOME/bin;$env:PATH"

./gradlew.bat assembleDebug
./gradlew.bat :app:testDebugUnitTest
./gradlew.bat lintDebug
./gradlew.bat assembleRelease

# Full local check
./gradlew.bat assembleDebug testDebugUnitTest lintDebug assembleRelease

# Install current release APK
.\.tools\platform-tools\adb.exe install -r .\app\build\outputs\apk\release\app-release.apk
```

---

## BUILD / CI NOTES

- AGP `9.1.0`, Gradle wrapper `9.4.1`, compile/target SDK `37`, min SDK `26`.
- `gradle.properties`: `org.gradle.daemon=false`, `android.builtInKotlin=true`, `android.suppressUnsupportedCompileSdk=37.0`.
- Release uses shrink/minify + `app/proguard-rules.pro`; manifest entry points and Apache MINA NIO processor are kept explicitly.
- Signing reads env vars first, then `keystore.properties`; BOM-prefixed `storeFile` key is normalized.
- CI workflow publishes rolling prerelease tag `main-latest`; if secrets are missing, APK may be unsigned.

## SIGNING

- Единственный release-keystore: `.tools/signing/foric27.jks` (alias `foric27`).
- `keystore.properties` указывает на `foric27.jks`; старый `release-keystore.jks` удалён.
- SHA-256 сертификата `foric27` зафиксирован в `ProductConfig.Security.EXPECTED_SIGNATURE_SHA256`.

## OBFUSCATION

- R8 full mode: `android.enableR8.fullMode=true` в `gradle.properties`.
- ProGuard rules: `-repackageclasses 'a'`, `-allowaccessmodification` для максимальной обфускации.
- Keep rules: точки входа manifest, Apache MINA NIO processor, `StreamConfig`, `SignatureVerifier`, `ProductConfig$Security`.

## OPTIMIZATION FLAGS

- `org.gradle.parallel=true`, `org.gradle.configureondemand=true`, `org.gradle.caching=true`.
- JVM heap: `-Xmx4096m`.

## CLEANUP

- После установки на устройство: `adb shell rm -f /data/local/tmp/app-release*.apk`.
- После тестов/сборки: `./gradlew.bat clean`.

---

## AGENT RULES

- **Язык общения:** агент размышляет и отвечает исключительно на русском языке.
- **Язык комментариев:** все комментарии в коде (KDoc, inline, TODO) пишутся только на русском.
- **Исключения:** названия классов/методов/API, строковые ресурсы `values-en/`, и имена собственных библиотек остаются на английском.

---

## CHILD AGENTS

- `app/src/main/java/ru/foric27/cluster/AGENTS.md` — Kotlin package architecture.
- `app/src/main/res/AGENTS.md` — XML layouts, dimensions, localization.
- `app/src/test/java/ru/foric27/cluster/AGENTS.md` — JVM unit-test conventions.
