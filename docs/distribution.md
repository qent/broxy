# Distribution builds

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
- The macOS app bundle name is `Broxy` (capitalized) via the UI native distribution package name.
