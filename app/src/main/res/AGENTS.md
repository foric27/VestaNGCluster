# AGENTS: app/src/main/res

**Scope:** Android XML resources  
**UI stack:** XML + ViewBinding + Material Components, no Compose  
**Locales:** default Russian + `values-en/` English.

---

## OVERVIEW

Resources define the operator UI, adaptive layouts for phones/tablets/TV-like screens, localized status text, icons, colors, dimensions, and themes.

---

## STRUCTURE

```
res/
├── layout*/          # activity_main + activity_developer per screen class/orientation
├── values*/          # strings, colors, dimens, styles and screen-size overrides
├── drawable/         # vector icons + panel/background drawables
└── mipmap-anydpi/    # launcher icon XML
```

---

## LAYOUT QUALIFIERS

| Qualifier | Role |
|-----------|------|
| `layout/` | portrait/default phone |
| `layout-land/` | landscape phone |
| `layout-sw600dp/` | 7" tablet |
| `layout-sw600dp-land/` | 7" tablet landscape |
| `layout-sw720dp/` | 10"+ tablet |
| `layout-sw720dp-land/` | 10"+ landscape |
| `layout-sw800dp/` | 12" / Android TV style two-column UI |
| `layout-sw800dp-land/` | large landscape |

When changing `activity_main.xml` or `activity_developer.xml`, check all qualifier variants for matching IDs used by ViewBinding.

---

## WHERE TO LOOK

| Task | Files |
|------|-------|
| Main status/FTP UI | `layout*/activity_main.xml`, `values*/strings.xml`, `values*/dimens.xml` |
| Developer settings UI | `layout*/activity_developer.xml`, runtime strings in `values*/strings.xml` |
| Theme/widgets | `values/styles.xml`, `values/colors.xml` |
| Adaptive spacing/text | `values/dimens.xml`, `values-sw600dp/`, `values-sw720dp/`, `values-sw800dp/` |
| Icons/backgrounds | `drawable/`, `mipmap-anydpi/` |

---

## CONVENTIONS

- Add every user-visible string to both `values/strings.xml` and `values-en/strings.xml`.
- Keep ViewBinding IDs identical across every `layout*` variant.
- Reuse `@dimen/screen_*` tokens instead of hardcoded spacing except tiny local offsets.
- Existing layouts use card-like `LinearLayout` sections inside `NestedScrollView`; preserve this pattern unless redesigning all variants.
- Main screen has warning panel, stream status, mode selection, restart button, FTP status, version/developer link.
- `layout-sw800dp` intentionally uses two columns; do not collapse it to the phone vertical structure.

---

## ANTI-PATTERNS

- Do not introduce Compose resources or Compose-only assumptions.
- Do not add a third locale without product approval.
- Do not remove `ftpStatusText`, `noticePanel`, mode radio IDs, or version/developer IDs from any layout variant.
- Do not use generic resource names like `icon`, `background`, `text`; keep project prefixes (`bg_`, `ic_`, `screen_`).
