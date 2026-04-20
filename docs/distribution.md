# Distribution builds

## Purpose

Document packaging and release build steps, primarily for desktop distribution artifacts.

## When to read

- Before changing packaging/release workflows.
- Before producing local DMG/release artifacts.

## Source-of-truth files

- `ui/build.gradle.kts`
- `.github/workflows/release.yml`
- `.github/workflows/macos-arm64-dmg.yml`

## Behavior contract

Release packaging should preserve runtime functionality while minimizing artifact size.

This document covers packaging the smallest possible macOS DMG while keeping the app functional.

## Optimized macOS DMG (local)

Build the DMG with a minimized runtime image (jlink strips debug info, man pages, and headers and
compresses the JDK image) and a release build (ProGuard shrinks unused bytecode), then recompress
the DMG with max zlib level:

```bash
./gradlew --no-daemon :ui:packageReleaseDistributionForCurrentOS
cd ui/build/compose/binaries/main-release/dmg
for dmg in *.dmg; do
  [ -f "$dmg" ] || continue
  base="${dmg%.dmg}"
  hdiutil convert "$dmg" -format UDZO -imagekey zlib-level=9 -o "${base}-compressed.dmg"
  rm -f "$dmg"
  mv "${base}-compressed.dmg" "$dmg"
done
```

The resulting DMG is located in `ui/build/compose/binaries/main-release/dmg`.

Notes:

- ProGuard rules live in `ui/proguard-release.pro` to suppress optional dependency warnings.
- Release ProGuard keeps Kotlin serialization metadata and MCP SDK types to avoid runtime decode failures.
- Release builds disable ProGuard optimization to avoid incomplete class hierarchy errors while still shrinking.
- Bro-cloud remote auth uses Ktor `ServiceLoader` providers; the ProGuard config keeps
  `io.ktor.client.engine.cio.*` and `io.ktor.serialization.kotlinx.*` so provider classes are not stripped.
- The macOS app bundle name is `Broxy` (capitalized) via the UI native distribution package name.
- Headless STDIO proxy support is bundled from `headless-runtime/` for packaged app STDIO mode.

## CI releases

- The cross-platform release workflow `.github/workflows/release.yml` publishes the official
  installers for all OSes (including macOS) using `:ui:packageDistributionForCurrentOS`.
- It can also be dispatched manually with a `ref` input to build a specific tag or commit.
- The release workflow also publishes the CLI jar (`broxy-cli*.jar`) and generates a unified
  `CHECKSUMS.txt` covering DMG, DEB, MSI, and CLI artifacts.
- The macOS-only workflow `.github/workflows/macos-arm64-dmg.yml` also builds the DMG and uploads
  it as a workflow artifact for verification, but does not publish to GitHub Releases to avoid
  asset collisions.
