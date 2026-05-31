# Contributing to Vesta NG Cluster

Благодарим за интерес к проекту. Этот документ описывает правила и ожидания для изменений в коде, ресурсах, runtime-поведении и release-процессе.

## Как внести вклад

1. Сделайте **fork** репозитория.
2. Создайте отдельную ветку (`git checkout -b feature/amazing-feature`).
3. Внесите минимальные, но законченные изменения.
4. Перед push убедитесь, что локальные проверки проходят.
5. Сделайте понятные атомарные commit'ы в semantic style.
6. Откройте **Pull Request** в `main`.

## Локальные проверки перед push

В репозитории используется версионируемый `.githooks/pre-push`, который запускает:

```bash
./gradlew :app:testDebugUnitTest
./gradlew lintDebug
```

Для локальной полной проверки перед PR рекомендуется также прогнать:

```bash
./gradlew assembleRelease
```

Если hook не подключён, настройте:

```bash
git config core.hooksPath .githooks
```

## CI и release

- `CI` запускается на каждый `push` в любую ветку и на `pull_request` в `main`.
- `CI` должен проходить зелёным для каждого отправленного commit.
- Rolling release собирается отдельным workflow только после успешного `CI` на `main`.
- `main-latest` всегда содержит только одну актуальную пару assets (`.apk` + `.sha256`).

## Архитектурные правила

Перед изменениями полезно прочитать `README.md`: в нём описаны реальные runtime-потоки NAVI, MED, root-network, status sync, recovery и update-подсистемы.

Ключевые принципы:

- `UdpStreamService` - центральный runtime-оркестратор.
- NAVI и MED используют один и тот же video pipeline (`VirtualDisplay -> GL -> MediaCodec -> UDP`), различается только target activity.
- MED не отправляется как отдельные metadata packets на cluster; это отрисованный экран `MediaCoverActivity`.
- FTP OTA и app self-update APK - разные подсистемы, их не нужно смешивать.
- Root нужен для streaming/root-network сценария; app self-update сам по себе root не требует.

## Стиль изменений

- Язык кода: Kotlin и XML.
- Комментарии в коде: русский язык.
- User-facing строки: обязательно выносить в `values/strings.xml` и `values-en/strings.xml`.
- Новые пользовательские настройки: одновременно добавлять backend + UI в developer screen, если это runtime setting.
- Для `activity_main.xml` и его вариантов следите за совпадением ID во всех `layout-*`, иначе сломается ViewBinding/runtime.
- Делайте минимальные изменения без лишнего расширения scope.
- Сохраняйте совместимость текущего runtime-поведения, если задача явно не требует его менять.

## Коммиты

- Используйте semantic style: `fix:`, `feat:`, `docs:`, `build:`, `ci:`, `refactor:`.
- Предпочтительны атомарные commit'ы, которые можно понять и откатить независимо.
- Не смешивайте в одном commit unrelated concerns, если это не требуется для целостности изменения.

## Тестирование

- JVM unit tests находятся в `app/src/test/java/ru/foric27/cluster/`.
- Для app update и другой pure-логики предпочтительны именно pure JVM tests.
- Перед PR убедитесь, что `:app:testDebugUnitTest` и `lintDebug` проходят без ошибок.
- Если изменение затрагивает release/runtime-критичные части, дополнительно проверьте `assembleRelease`.

## Сообщения о проблемах

- Используйте GitHub Issues.
- Описывайте сценарий воспроизведения максимально конкретно.
- Указывайте устройство, Android-магнитолу, наличие root, режим (`NAVI` / `MED` / `ABS`) и при необходимости сеть/iface.
- Если проблема связана со streaming/runtime-поведением, прикладывайте `logcat`.

## Безопасность

- **Никогда** не коммитьте secrets, keystore, пароли и локальные signing-файлы.
- Не добавляйте в репозиторий реальные release keys.
- Для CI/CD используйте GitHub Secrets.
- О security issues сообщайте приватно, см. [`SECURITY.md`](SECURITY.md).
