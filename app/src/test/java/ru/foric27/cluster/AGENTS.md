# AGENTS: JVM tests

## Scope

Только JVM unit tests. Здесь нет instrumentation/UI tests.

## Где искать

- Network/root: `RootNetUtilTest.kt`, `RootNetworkAddressingTest.kt`, `NetworkInterfaceSelectorTest.kt`
- Launch/display: `VideoDisplayLauncherTest.kt`, `YandexLaunchTargetTest.kt`
- Config/sync: `RuntimeConfigTest.kt`, `SyncHandlerTest.kt`, `SyncHandlerPayloadPolicyTest.kt`
- Video helpers: `VideoCodecOutputProcessorTest.kt`, `VideoFrameTimingControllerTest.kt`, `H264AnnexBUtilTest.kt`
- Video encoder: `VideoEncoderTest.kt`
- OTA: `UpdateFileLocatorTest.kt`, `Sha256VerifierTest.kt`
- App update: `AppUpdateManagerTest.kt`, `AppUpdateReleaseParsingTest.kt`, `AppUpdateVersionPolicyTest.kt`
- Virtual display: `PersistentVirtualDisplayTest.kt`
- App update: при добавлении тестов держать их pure-JVM и изолировать от реального GitHub/PackageInstaller.

## Локальные правила

- Предпочитай pure JVM tests без Android runtime.
- Проверяй shell/root commands достаточно точно, чтобы ловить regressions в quoting/routing.
- Не добавляй Robolectric/instrumentation без явного запроса.
- Если меняешь network/launch logic, обычно нужен test update рядом.
