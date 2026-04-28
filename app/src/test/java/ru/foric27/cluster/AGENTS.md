# AGENTS: app/src/test/java/ru/foric27/cluster

**Scope:** JVM unit tests  
**Framework:** JUnit 4  
**No instrumentation/UI tests here.**

---

## OVERVIEW

Tests cover pure logic and Android-adjacent code through fakes/reflection where possible: config parsing, route command formatting, OTA file discovery, H.264 framing, timing, mode logic, and launcher command construction.

---

## WHERE TO LOOK

| Behavior | Tests |
|----------|-------|
| Runtime config parsing | `RuntimeConfigTest.kt` |
| Sync payload/mode policy | `SyncHandlerTest.kt`, `SyncHandlerPayloadPolicyTest.kt` |
| Network/root command formatting | `RootNetUtilTest.kt`, `RootNetworkAddressingTest.kt`, `NetworkRootShellTest.kt`, `NetworkInterfaceSelectorTest.kt`, `ConnectivityHealthTest.kt` |
| OTA discovery | `UpdateFileLocatorTest.kt` |
| OTA verification | `Sha256VerifierTest.kt` |
| Video codec/timing | `H264AnnexBUtilTest.kt`, `VideoCodecOutputProcessorTest.kt`, `VideoFrameTimingControllerTest.kt` |
| Navigator launch/proxy shell command | `VideoDisplayLauncherTest.kt`, `YandexLaunchTargetTest.kt` |
| Cluster mode | `ClusterModeTest.kt` |
| Warning queue | `AppWarningCenterTest.kt` |

---

## CONVENTIONS

- Test names use Kotlin backtick sentences for scenario clarity.
- Prefer fakes over Android runtime dependencies; `ContextWrapper(null)` is used only when code does not touch real context services.
- Reflection is acceptable for private helpers when the tested contract is product-critical (`UpdateFileLocator` root parsing, launch internals).
- Use `TemporaryFolder` for file-root OTA tests; assert non-recursive behavior explicitly.
- Keep expected shell/root commands exact enough to catch quoting and policy-routing regressions.
- Keep pure helper tests (`RootNetworkAddressingTest`, `H264AnnexBUtilTest`, `Sha256VerifierTest`) Android-free where possible.
- When adding FTP/OTA tests, cover both valid pair and rejection path with debug paths.

---

## COMMANDS

```powershell
$env:JAVA_HOME="$PWD/.tools/jdk-21.0.10"; $env:PATH="$env:JAVA_HOME/bin;$env:PATH"
./gradlew.bat :app:testDebugUnitTest
./gradlew.bat :app:testDebugUnitTest --tests "ru.foric27.cluster.UpdateFileLocatorTest"
```

---

## GOTCHAS

- Plain JVM tests do not provide real `android.util.Log`; production code paths newly exercised by JVM tests may need safe logging wrappers or better isolation.
- Do not add Robolectric/instrumentation dependencies unless the task explicitly requires Android framework execution.
- Avoid tests that depend on local machine interfaces, root, ADB devices, or actual `/storage` contents.
