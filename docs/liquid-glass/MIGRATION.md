# liquid glass migration status

## completed

- [x] Stage 1: UI inventory and migration map (`MIGRATION_PLAN.md`).
- [x] Stage 2: design tokens/config flags (`GlassTokens`, `GlassConfig`, `LocalGlassConfig`).
- [x] Stage 3: macOS vibrancy installer with NSVisualEffectView and runtime toggle.
- [x] Stage 4: base primitives (`GlassSurface`, `GlassPanel`, `GlassCard`, `GlassDivider`, `GlassScrim`).
- [x] Stage 5: dimming policy (`Auto/Always/Never`) and debug background scenarios.
- [x] Stage 6: glass controls (`GlassButton`, `GlassIconButton`, `GlassTextField`, `GlassSwitch`).
- [x] Stage 7: navigation migration (top bar + sidebar + dialog surfaces).
- [x] Stage 9: windows/linux default solid fallback via `glassEnabled=false` defaults.
- [x] Stage 10 (partial): showcase component (`GlassShowcaseScreen`) and docs.

## partially completed

- [~] Stage 8: screen migration
  - shared cards/forms/search/dialogs are migrated via common components.
  - no separate dedicated route for a full-screen showcase; preview is embedded in Settings.

## known compromises

- Compose Desktop cannot reproduce full system-level Liquid Glass behavior per-control; only window-level behind-window blur is native.
- control-level effects are Compose emulation (alpha/highlight/stroke + interaction states).
- dimming `Auto` currently uses selected debug background scenario rather than live pixel sampling.
