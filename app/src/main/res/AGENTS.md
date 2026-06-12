# AGENTS: Android resources

## Scope

XML resources, drawables, themes, strings. Локали: **RU по умолчанию + `values-en/`**.

## Где искать

- Strings: `values/strings.xml`, `values-en/strings.xml`
- Theme/colors/dimens: `values/themes.xml`, `values/colors.xml`, `values/dimens.xml`
- XML config: `res/xml/`
- Drawables: `drawable/`

## UI

Все Activity используют Compose. XML layout-файлы удалены — UI описан в Kotlin (`ui/` package).

## Локальные правила

- Любую пользовательскую строку добавляй и в `values/strings.xml`, и в `values-en/strings.xml`.
- Любую новую пользовательскую настройку одновременно отражай и в `DeveloperActivity`, и в backend-логике.
- Предпочитай существующие `@dimen/screen_*` и `@color/*` вместо hardcode.
- MED/NAVI экраны правь осторожно: важны `visibleArea`, чёрный фон, отсутствие overlay-регрессий.
- Комментарии в XML — только на русском языке.
