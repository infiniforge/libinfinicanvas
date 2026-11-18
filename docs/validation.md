# Validation evidence

## Authoring, writers, extended ORA and ICANIM — 2026-11-18

The InfiniCanvas source-composite build ran the library's
`:canvas-android:testDebugUnitTest` suite: **109 cases, zero failures, errors or
skips**. The corresponding app run had 11 passing JVM cases. Its generated native
fixtures and actual packaged library classes passed **27 Android runtime groups**
using real Bitmap/Canvas/DOM/ZIP implementations. Those groups include the 15
existing indexed PNG/IFF/PSD/XCF/ORA authoring groups and 12 container/animation
groups. Host logs were `build/container-final-build.log` and
`build/container-runtime-final.log`; those build artifacts are not library source.

Checks cover old Java value constructors and document/writer calls, reflection of
Kotlin copy signatures, original index/BODY identity, CRNG/CCRT/DRNG behavior,
PSD/XCF layer subsets, encoded media and unknown ORA XML, group opacity, retained
originals after raster editing/import, opaque pixel-edit rejection, explicit media
deletion, APNG timing/loops, FLI/FLC palette-delta reconstruction, ICANIM frame-layer
and attachment round trips, identity/capability/metadata routes, extensionless
viewer decode, on-demand playback and borrowed output streams.

The native checks found and repaired Android's unsupported whole-Document clone
operation and absent optional-codec linkage on opaque assets. Metadata tests do
not decode frame pixels; they validate the real XML timeline, bounds and entity
rejection. Existing ordinary decoder regressions remain in the JVM suite.

These are recorded results from that host integration. Publishing documentation
or a library commit does not rerun tests or establish device UI acceptance.
The later host timeline controls and bulk-frame operation UI are application
features and require their own validation; they are not library API guarantees.

## Standalone verification commands

```sh
python3 scripts/fetch-dependencies.py
./gradlew :canvas-android:testDebugUnitTest :canvas-android:assembleDebug
```

Use the pinned wrapper and SDK/Termux settings in the README. The host's optional
native harness requires an Android app_process64 runtime and Android build tools;
it is not a desktop JVM test and is not a dependency of this library.

## Outstanding acceptance

- External application round trips for the newer raster, GIF, PSD and XCF writers.
- Broader real-world media/extension combinations, large allocations and codec
  variants outside the fixture subsets.
- Host-owned mobile layout, touch, accessibility, playback lifecycle and SAF paths.
- FLI/FLC writing and GIF/WebP/MNG authoring extraction remain unimplemented.
- ICANIM outer playback uses static frame composites; nested independent media
  animations/cycles are preserved but are not advanced by its outer timeline.
