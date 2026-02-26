# macos vibrancy

## entry point

`ui/src/desktopMain/kotlin/io/qent/broxy/ui/liquidglass/macos/MacVibrancyInstaller.kt`

`DesktopApp` installs vibrancy via:

- `installMacosVibrancyBackground(window)` inside `DisposableEffect(window, useMacVibrancy)`
- toggle condition: `isMacOs && glassEnabled && vibrancyEnabled && !reduceTransparency`

## implementation summary

- uses AppKit runtime (`libobjc` via JNA) and installs a glass host as window `contentView`.
- preferred path (macOS 26+): `NSGlassEffectView` with `setContentView(existingContentView)`.
- fallback path: `NSVisualEffectView` (`behindWindow` + `underWindowBackground` + `active`) wrapping the
  existing AWT/Compose content as a subview.
- this follows Apple guidance to keep app content *inside* the glass host, not as a sibling layer behind content.
- keeps AWT content/root pane background almost transparent (`alpha=1`) instead of fully transparent (`alpha=0`)
  to avoid body rendering glitches on some macOS/AWT combinations.
- sets `apple.awt.draggableWindowBackground=false` so dragging is limited to explicit drag areas.

## accessibility and fallback

- `systemReduceTransparencyEnabled()` checks `NSWorkspace.accessibilityDisplayShouldReduceTransparency`.
- this value seeds default `GlassConfig` and disables glass/vibrancy by default when enabled.
- Settings allows explicit runtime override (`macOS vibrancy background`).

## limitations

- window matching uses `NSApplication` windows and AWT title matching; single-window flow is the primary target.
- no private API is used for blur radius/intensity.
- non-macOS platforms return a no-op handle.
