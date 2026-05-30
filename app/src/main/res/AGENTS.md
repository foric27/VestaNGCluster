# AGENTS: Android resources

## Scope

XML resources, layouts, drawables, themes, strings. Локали: **RU по умолчанию + `values-en/`**.

## Где искать

- Main / developer UI: `layout*/activity_main.xml`, `layout*/activity_developer.xml`
- MED / cluster UI: `layout/activity_media_cover.xml`, `layout/activity_stream_placeholder.xml`
- Strings: `values/strings.xml`, `values-en/strings.xml`
- Theme/colors/dimens: `values/themes.xml`, `values/colors.xml`, `values/dimens.xml`
- XML config: `res/xml/`

## Локальные правила

- Любую пользовательскую строку добавляй и в `values/strings.xml`, и в `values-en/strings.xml`.
- Любую новую пользовательскую настройку одновременно отражай и в `DeveloperActivity`, и в его ресурсах/layout.
- Не ломай одинаковые `id` между layout-вариантами.
- Предпочитай существующие `@dimen/screen_*` и `@color/*` вместо hardcode.
- MED/NAVI экраны правь осторожно: важны `visibleArea`, чёрный фон, отсутствие overlay-регрессий.
- Если меняешь layout под один экран, проверь, нет ли квалифицированных вариантов с теми же `id`.
- Для `activity_main.xml` изменения почти всегда нужно зеркалить во все `layout-*` варианты, иначе сломается ViewBinding/runtime.
