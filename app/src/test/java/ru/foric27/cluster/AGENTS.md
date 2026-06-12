# AGENTS: JVM tests

## Scope

Только JVM unit tests. Здесь нет instrumentation/UI tests.

## Структура

Тесты зеркально отражают пакеты main source:
- `network/` — `RootNetUtilTest`, `RootNetworkAddressingTest`, `NetworkInterfaceSelectorTest`, `NetworkRootShellTest`, `RoutePreparationResultTest`
- `video/` — `VideoEncoderTest`, `VideoDisplayLauncherTest`, `VideoCodecOutputProcessorTest`, `VideoFrameTimingControllerTest`, `H264AnnexBUtilTest`, `PersistentVirtualDisplayTest`, `VideoCaptureLifecycleStateMachineTest`
- `config/` — `RuntimeConfigTest`, `AppSettingsUiStreamModeTest`, `AppSettingsUpdateChannelTest`
- `service/` — `SyncHandlerTest`, `SyncHandlerPayloadPolicyTest`, `UdpPipelineStartCoordinatorTest`, `UdpStartupFlowCoordinatorTest`
- `update/` — `AppUpdateManagerTest`, `AppUpdateReleaseParsingTest`, `AppUpdateVersionPolicyTest`, `EmbeddedFtpServerFactoryTest`, `Sha256VerifierTest`, `UpdateFileLocatorTest`
- `ui/` — `YandexLaunchTargetTest`
- `util/` — `AppWarningCenterTest`, `ClusterModeTest`, `ConnectivityHealthTest`, `LogSanitizerTest`

## Локальные правила

- Предпочитай pure JVM tests без Android runtime.
- Проверяй shell/root commands достаточно точно, чтобы ловить regressions в quoting/routing.
- Не добавляй Robolectric/instrumentation без явного запроса.
- Если меняешь network/launch logic, обычно нужен test update рядом.
