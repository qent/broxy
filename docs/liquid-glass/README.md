# liquid glass

This folder tracks Broxy's Liquid Glass migration for Compose Desktop.

## files

- `MIGRATION_PLAN.md` - screen inventory and rollout map.
- `MIGRATION.md` - completed steps, pending work, and compromises.
- `MACOS_VIBRANCY.md` - macOS NSVisualEffectView integration details.

## runtime controls

Use **Settings -> Glass style** section:

- `Glass style` - enables/disables glass primitives globally.
- `Reduce transparency` - increases opacity for accessibility/readability.
- `Reduce motion` - removes interaction animations.
- `macOS vibrancy background` - enables/disables NSVisualEffectView installation.
- `Dimming policy` - `Auto`/`Always`/`Never` for clear glass contrast layer.
- `Glass background` - debug backdrop scenario (`App`, `Bright`, `Dark`, `Noisy`).

## primitive sizing

- `GlassSurface` preserves intrinsic content size by default.
- Use explicit layout modifiers (`fillMaxWidth`, `fillMaxHeight`, `size`, etc.) on the component call site
  when a surface must expand to available space.

## platform behavior

- macOS: glass and vibrancy enabled by default (unless Reduce Transparency is active).
- Windows/Linux: glass disabled by default, shared components render as solid surfaces.
