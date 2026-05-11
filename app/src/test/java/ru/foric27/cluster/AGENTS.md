# AGENTS: JVM tests

## Scope

Только JVM unit tests. Здесь нет instrumentation/UI tests.

## Где искать

- Network/root: `RootNetUtilTest.kt`, `RootNetworkAddressingTest.kt`, `NetworkInterfaceSelectorTest.kt`
- Launch/display: `VideoDisplayLauncherTest.kt`, `YandexLaunchTargetTest.kt`
- Config/sync: `RuntimeConfigTest.kt`, `SyncHandlerTest.kt`, `SyncHandlerPayloadPolicyTest.kt`
- Video helpers: `VideoCodecOutputProcessorTest.kt`, `VideoFrameTimingControllerTest.kt`, `H264AnnexBUtilTest.kt`
- OTA: `UpdateFileLocatorTest.kt`, `Sha256VerifierTest.kt`

## Локальные правила

- Предпочитай pure JVM tests без Android runtime.
- Проверяй shell/root commands достаточно точно, чтобы ловить regressions в quoting/routing.
- Не добавляй Robolectric/instrumentation без явного запроса.
- Если меняешь network/launch logic, обычно нужен test update рядом.
