# Vendored: Light UI (from `lightphone/light-sdk`)

The Kotlin sources under `src/main/kotlin/com/thelightphone/sdk/ui/` are vendored,
under the MIT License, from the Light Phone III SDK.

- **Upstream:** https://github.com/lightphone/light-sdk (module `:sdk:ui`)
- **Commit:** `d2323e33b78bc1fadf201a8b3214b3619ec23d73` (2026-07-24)
- **License:** MIT — see [./LICENSE](./LICENSE) (Copyright (c) 2026 The Light Phone)

## Why vendored (not a dependency)

Diffuse is a standalone, sideloaded APK — **not** a Light SDK tool. The upstream
`:sdk:ui` artifact is published only through GitHub Packages (requires a personal
access token) and transitively pulls in CameraX, ML Kit barcode scanning, and a
token-gated keyboard artifact that Diffuse does not need. We therefore copy only
the small, dependency-free **theme foundation**.

## Files vendored verbatim

| File | Purpose |
|------|---------|
| `LightFont.kt` | Akkurat system-font resolution via `android.graphics.fonts.SystemFonts` |
| `LightTheme.kt` | `LightColors` / `LightTypography` / `LightTheme` composable + M3 interop |
| `LightThemeController.kt` | App-wide dark/light theme state (a `StateFlow`) |
| `LightText.kt` | `LightText` composable + typographic variants |
| `LightGrid.kt` | LP3 grid constants + design-px → sp/dp screen scaling |
| `LightClickable.kt` | Indication-free `Modifier.lightClickable` |

## Modifications

- **`LightSurfaceScheme.kt`** — the `LightSurfaceScheme` enum was extracted verbatim
  from upstream `LightIcon.kt` into its own file, so the theme compiles without the
  icon/resource machinery. No logic changed.
- No other changes. Package names are unchanged (`com.thelightphone.sdk.ui`).

## Deliberately **not** vendored (add per phase as needed)

Icons (`LightIcon`/`LightIcons`), QR scanner, embedded keyboard, modals, top/bottom
bars, scroll view, and the text-input editor — several of these depend on CameraX,
ML Kit, or the token-gated keyboard artifact.
