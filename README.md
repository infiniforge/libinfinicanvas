# libinfinicanvas

## Reproducible standalone build

Install JDK 17 or 21, Python 3, Android SDK platform 36/build tools 35.0.0,
and (for JNI modules) NDK 27.2.12479018/CMake 3.22.1. Set `ANDROID_HOME` or
this checkout's untracked `local.properties`. The checked-in Gradle 9.7.1 wrapper
is verified by its distribution SHA-256.

```sh
python3 scripts/fetch-dependencies.py
./gradlew :canvas-android:testDebugUnitTest :canvas-android:assembleDebug
```

The lock includes only this library's required source dependencies, including
transitive dependencies. Fetching checks pin agreement and refuses to overwrite
local changes. `python3 scripts/fetch-dependencies.py --check` checks revisions.
No application checkout is required. CI uses the same commands from a fresh clone.


Runtime discovery: [handler schema, registration, linked dependencies and limits](CAPABILITIES.md).

Structural extraction from gretchycat/infinifolder at 9233495.
Implementation ownership and validation are recorded in SOURCE_PROVENANCE.md.

Build using JDK 17+ and Gradle 9.7.1. Android adapters need SDK 36/min API 26.
Run `gradle :canvas-android:testDebugUnitTest` and the relevant assemble task.

Source dependencies are pinned in `dependencies.lock.json` and fetched inside
this checkout. No sibling source directories are required. On Termux, install
`aapt2` and pass `-Pandroid.aapt2FromMavenOverride=$(command -v aapt2)` to Gradle.

Original code is licensed under MIT; third-party components retain their own terms. The extraction did not rewrite the original implementation.

## Current boundary

Canvas owns ThumbnailDecoder, Android bitmap/vector/platform adapters and the existing custom raster/image decoders. URI/name-based icon policy (ImageFormats, CustomIconStore, DirectoryIconResolver), themes and viewer UI stay in the host. The library depends on Stream plus the existing AndroidSVG, JPEG XL and JPEG2000 adapters; it does not depend on Store, Vault or InfiniFolder. CanvasStreams accepts common source resources through the existing bounded 32 MiB streaming fallback; direct Android source decoding still supports sampled large images. BitmapFrame publishes actual dimensions, pixel format and color space without extra pixel copying; host bitmap ownership remains explicit. Synthetic fixtures and the original three codec regression tests follow the source. Existing Android/native codec AARs need F-Droid review.

## Full image viewer composition

`CanvasStreams.decodeViewer(resolver, resource, uri, targetPixels)` composes common source factories with the existing full-viewer decoder. The original URI retains native decoding when supported; source-based paths borrow independently opened streams. Bounds/sampling fallbacks and codec selection remain in Canvas. The returned borrowed `BitmapFrame` carries actual pixel dimensions/config/color space plus input provenance and warnings; the host owns bitmap lifetime. The consuming image viewer now uses this facade and retains its frame metadata. Its inspector derives current frame attributes after edits rather than reusing old dimensions.

The viewer-source tests include three checks that native URI hints must match the original untransformed resource. The containing app passes its expanded regression gate; actual image-viewer rendering/interaction remains handset acceptance.

## IFF palette animation

The full-viewer decoder attaches an optional `BitmapFrame.colorCycling` renderer
for indexed ILBM/PBM files with CRNG, CCRT or DPaint IV DRNG ranges. It retains
decoded indices from the existing BODY decoder and produces elapsed-time palette
frames without retaining/reopening an input stream. HAM held-color decoding is
shared with the still-image path, and EHB colors follow the cycled base palette.
The host supplies a reusable ARGB buffer and owns scheduling/bitmap publication.
Cycling is limited to four megapixels and 256 active ranges; unsupported ranges
or scanline palettes report warnings and retain the still preview. IFF thumbnails
remain static. Native-size still/palette authoring and source-preserving writes
use the separate editor/PaletteDocument APIs; these playback limits do not define
an editor resolution quota. The owner confirmed basic playback in the host image viewer on
November 4, 2026; individual format variants and lifecycle checks remain pending.

## Legacy OpenRaster viewer layers

The full viewer reads the bounded OpenRaster stack and decodes referenced `data/`
layers through Canvas's normal visual-media routes. Static and animated layers are
composed in stack order with offsets and opacity. Embedded FLI/FLC, GIF, APNG,
WebP and MNG layers keep their normal playback renderer inside a lifecycle-controlled
stack drawable. A single cycling IFF layer uses its native CRNG, CCRT or DRNG ranges
and is composited over the other visible ORA layers, preserving the confirmed
Infinimedia artwork behavior. The host controls playback start/stop. Ordinary
documents and unsupported animation layers retain the merged-image preview. DTD
declarations and external entities are rejected.

## Image animation

`BitmapFrame.animation` optionally exposes a single-worker `BitmapAnimation`
renderer; `animationDrawable` optionally holds an Android native animated
drawable. Existing constructors and `colorCycling` remain compatible. The host
owns lifecycle/scheduling and retains a still bitmap for editing/export.
Standard 8-bit FLI/FLC, APNG composition and PNG-based MNG (including stored PNG
objects and SHOW/grouped frames) are decoded
in Canvas. GIF/WebP use Android's native animation on API 28+, with a legacy GIF
fallback on API 26–27. HEIF/AVIF only animate where the platform exposes an
animated drawable. Ordinary custom playback retains bounded sources and decoded state. The explicit
AnimationDocuments authoring API instead materializes all editable frames; its
memory behavior is documented separately below. `durationNanos` and bounded
catch-up status support the host's timeline/seeking; native GIF/WebP duration is
container timing for a read-only progress display. `InteractiveBitmapAnimation`
adds worker-owned named states and normalized pointer events for dynamic MNG.
Rectangular/object/index masks use the existing Android PNG codec; indexed
samples are recovered with an identity palette rather than a duplicated PNG
decoder. FLIC recovery derives missing geometry from complete BRUN rows,
validates bounded frame data, and reports fallback timing without rewriting the
source. `ThumbnailDecoder.CONTENT_REVISION` participates in host icon cache keys.
The owner
confirmed the host playback/control follow-up on November 4, 2026; individual
format/lifecycle cases remain separately unverified. IFF cycling was already
confirmed. These later recovery/interactivity fixes still need device acceptance.

## FLIC identity and routing

`FlicDecoder.mimeTypes` owns the provider MIME aliases for FLI/FLC/FLIC across
image, video and application categories. The Canvas handler advertises these
exact aliases so an installed image viewer can take precedence over a generic
video handler. Extension matching and AF11/AF12 decoding remain unchanged. Hosts
reuse this metadata rather than maintaining another alias list. The alias repair
passed main/combined-host compilation; playback after this repair still needs a
device check. Earlier animation acceptance and decoder limitations remain above.

## Image metadata

`ImageMetadata.read(ByteSource)` runs the registered `canvas.android.exif` pull
processor using the existing Android ExifInterface backend. It borrows and
consumes the input's current sequential cursor. `read(SourceResource)` instead
opens/closes one independent source, including on errors/cancellation. Neither
path stages input or decodes pixels. `ImageExifMetadata.fields` is an immutable
snapshot of the original inspector's make/model, date, exposure, aperture, ISO,
focal length and optional float GPS coordinates; labels/units/locale formatting
belong to the host. The output is STRUCTURED/image-exif, with actual field
attributes and preserved provenance/warnings. It does not inherit encoded byte
lengths, read capabilities, pixel geometry or verification. Closing the result
never closes a borrowed input, and its fields remain usable after close.

Cancellation is checked before parsing, around reads and after the platform
backend returns; caught input IOExceptions are rethrown. Android EXIF expects
full requested reads, so the adapter fills its supplied buffer across short reads
until the request is satisfied or EOF, without staging or an extra buffer.
The descriptor exposes
accepted READ byte input, structured output and pull-only mode. The facade uses
Stream's registry/pipeline. Backend-specific parser limits and format support
remain Android's; no portable EXIF implementation or metadata writer is claimed.
The public value/source interfaces expose no Android presentation types.

The EXIF checkpoint added 16 cases to the preceding ten Canvas JVM cases. The host adds five inspector
formatting cases; a packaged-APK runtime helper compares the extracted path to
the previous Android calls using synthetic little-/big-endian EXIF JPEGs, ordinary
untagged images, short/unknown-length/archive sources and error/cancellation cases.
Handset UI, live provider behavior and image editing acceptance remain separate.


## Image transformations and PNG encoding

`ImageTransforms.apply(BitmapFrame, ImageTransform, cancellation)` executes the
registered `canvas.android.transform` pull processor for orientation, source-
pixel crop or resize. `ImageTransform.Scale(width, height, filtered)` adds
explicit smoothing control; `Resize` retains its filtered behavior. The Android Matrix/Bitmap operations and crop
truncation/clamping retain the original viewer's algorithms. UI display-coordinate
mapping and edit policy remain in the host. Typed requests are translated into
validated processor options; an edit uses an explicit registry step so a frame-
to-frame target cannot accidentally negotiate an unchanged zero-step route.

The processor borrows input, including input frames whose owner will recycle them.
New Bitmap objects are owned by the output frame; identical-object results are
borrowed aliases. This handles Android's immutable/mutable no-op behavior without
extra pixel copies. Close recycles only owned results. `BitmapFrame.takeBitmap()`
transfers pixels without copying and invalidates the frame; the caller retains
any aliased input's lifetime. Each result publishes actual dimensions/config/
color space and provenance/warnings. Cancelled new allocations are recycled.

`ImageEncoding.png(BitmapFrame, cancellation)` runs `canvas.android.encode.png`.
It retains the original PNG compression settings and returns `EncodedImage`, an
owned sequential/positional ByteSource with exact PNG byte length, MIME/format,
width/height and source provenance/warnings. It clears raw bitmap attributes,
input filename and verification. The `takeBytes()` ownership transfer supports
the existing host's byte-array publication without an extra copy. Both processors
work through Stream's registry; ordinary PNG pull composition is supported.
They do not open storage or choose destination URIs.

PNG output is fully materialized, matching the existing save boundary; streamed
publication is future work. Cancellation is cooperative before/after native pixel
operations and during PNG writes. Original stream exceptions are retained even
when native compression catches them or clears an interruption flag. The host
retains sampled editing, original-file protection, PNG-copy export, preview
controls and current edit/save semantics. The view's legacy crop method delegates
to Canvas while preserving its public signature.

Canvas now has 52 JVM cases (26 new geometry/options/encoded-source checks).
The app's device helper adds twelve pixel/ownership/encoding groups against the
actual APK, including independent synthetic reference images, alpha, actual
metadata, no-op aliases, transfers, cancellation and a PNG pull pipeline.
Android presentation and live provider acceptance remain separate.

## Decoder completion and ANSI glyphs

Custom raster decoders reject unimplemented compression and missing mandatory
pixel data instead of publishing zero-filled successful frames. This includes
EXR RLE, TIFF/XCF compression outside implemented modes, incomplete
scanlines/tiles/channels and strict PackBits output. XCF supports uncompressed,
RLE and zlib tiles in its documented 8-bit layer subset. A genuinely blank valid
image is allowed; rejection is based on structure and decoding, not pixel color.
Known working subsets and real embedded previews remain; this does not add full
PostScript/layer interpretation.

ANSI cells use a real CP437 bitmap table (8x8 glyphs doubled vertically), retaining
8x16 geometry and color/block handling. Public domain font8x8 sources, pinned
provenance and a reproducible generator are included in `third_party/font8x8`
and `scripts/generate-cp437-font.py`; attribution is packaged as a license asset.
The independent decoder tests cover malformed raster data and reference glyphs.

This correction adds 15 independent cases (67 total, all passing). The consuming
APK rejects all 14 malformed/unsupported image probes and matches the real `A`
glyph while all 12 original image controls still decode. Its existing 35 native
viewport/edit/metadata/PDF groups also pass; handset UI acceptance remains separate.

`CachedImageCodec` decodes already bounded artwork bytes with the existing Android
power-of-two sampling and aspect-preserving thumbnail sizing. It exposes original
pixel dimensions separately from the owned `BitmapFrame`; use `takeBitmap()` to
transfer pixels to a host cache. Cache naming, freshness, provenance checks and
publication remain host policy. PNG output uses `ImageEncoding.png`.

`CanvasFonts.cp437(context)` exposes a real fixed-cell typeface for selectable DOS
text, generated from the existing bitmap glyph table and matching shade geometry.
Regenerate with `python3 scripts/generate-cp437-typeface.py` (fonttools). The source
provenance covers public-domain font8x8 plus project-licensed supplemental drawings;
no proprietary ROM/font is imported. DOS `.asc`/`.diz`/`.nfo`/`.ansi` icon previews
use the existing ANSI bitmap renderer; document viewing remains a host/Scribe job.

Text icon previews can use `ThumbnailDecoder.decodeTextPreview(uri, targetPx, dosText)`
without a filename-bearing URI. Ordinary text reads a 16 KiB prefix and shows up
to 25 rows using native monospace rendering. ANSI/CP437 reads up to 1 MiB (including
SAUCE dimensions when the complete file fits) and uses terminal glyphs. Its icon
renders short art in full, limiting tall art to a square terminal surface: 80 columns
at 8x16 pixels gives 40 rows. Empty black margins are trimmed before aspect-preserving
scaling. Right-margin wrapping is deferred until the next printable character, so
full-width CRLF lines occupy one row. `decodeAnsiIcon` exposes this policy directly;
the existing full-viewer `decodeAnsiArt` overloads retain their row limits. This does
not change document routing.

ANSI icon previews also decode UTF-8 before reading escape sequences. Unicode
box/block characters share the CP437 bitmap geometry; other Unicode characters
use Android font fallback within the icon cell. CP437 input remains supported.

`ThumbnailDecoder.decode(uri, targetPx, extensionHint)` lets hosts supply an
authoritative renderer hint for opaque provider URIs or custom associations.
Recognized byte signatures take priority. The original two-argument overload
remains available; MIME/icon policy stays in the host.

## RIPscrip graphics

RipScriptDecoder renders a bounded subset of RIPscrip 1.54 static graphics. EGA
simulation (640×350 indexed colour, aspect-corrected presentation) is the decoder
and thumbnail default; the host viewer defaults to high-resolution geometry
replay at up to 4× resolution. Boundary flood fills use a second indexed reference
raster to avoid leaks that depend on resolution; this adds bounded render work
while preserving each mode's geometry and palette. The shared thumbnail/full-viewer
decoder routes `.rip` sources and exposes rendering
warnings for unsupported commands/font substitutions. It never executes BBS
commands or loads external icon/scene files. See the host's docs/ripscrip-graphics.md
for supported primitives, limits and unverified device acceptance.

RIP stroke-font fallback layout uses per-character advances and nominal heights
measured from the reference RIPterm BGI fonts (reproducible using
`scripts/measure-bgi-metrics.py`). This fixes proportional Triplex labels clipping
at the edge of a scene. Those numeric measurements remain a fallback. The ten original BGI fonts are now
bundled with retained upstream licensing/provenance under `third_party/bgi-fonts`.
BgiStrokeFonts reads their actual CHR outlines, ascenders and proportional advances
for EGA and high-resolution rendering; no runtime font download is needed.
Missing/corrupt fonts use the reported CP437 fallback.

RIP text windows (`w/e/g/H/>`) composite CP437/ANSI TTY cells directly onto the
indexed graphics surface. All five grid sizes, clipping, wrap/truncate, scrolling,
cursor placement, common ANSI colours and erase operations are implemented. The
legacy zero-bound disabled-window form used by RIPdraw is accepted; unsupported
TTY attributes remain explicit warnings. Graphics continuation parsing remains
separate from literal TTY backslashes.

Static RIP button definitions/instances now draw panels, bevel/recess/sunken
styles, labels, selected appearance, shadows and hotkeys; chisel decoration is
best-effort. External ICN references produce a labeled missing-art marker and
warning; they are not fetched or invented. Recoverable malformed arguments skip
only the affected command, preserving an explicitly partial scene. Budget and
cancellation failures still abort. SOH/STX command prefixes are recognized.

## Theme artwork and static SVG

`CanvasStreams.decodeBytes` decodes in-memory assets through the existing image registry and frame APIs, including IFF palette cycling. SVG/SVGZ render as still images through AndroidSVG; custom palette/cycle metadata is ignored. Intrinsic SVG document dimensions are preserved for sprite sheets, with proportional downsampling to the requested limit. Ordinary explicit fills, strokes and gradients remain supported within AndroidSVG's SVG subset.

The experimental SVG palette parser, vector animation and raster palette animation have been removed at the owner's request. Source SVG files and the separate SVG-to-IFF authoring converter are preserved. Use indexed IFF for color-cycling theme artwork. See [SVG support](docs/svg-palette-cycling.md).

The `animate=false` decodeBytes overload returns the exact initial frame without playback state. Other image animation formats keep their existing decoders and lifecycle contracts.

## Authoring and compressed thumbnails

ORA/KRA ZIP mimetype entries identify their image format instead of a generic
archive. Thumbnail requests stream the first supported preview/merged PNG without
materializing the project: 32 MiB encoded input, 128 MiB expanded scan, 4,096
entries and a 16 MiB preview limit. Bitmap decoding samples to the thumbnail size.
Full ORA viewing decodes referenced `data/` layers through Canvas and animates
supported embedded visual media, including IFF palette cycling. It composes flat
`src-over` layers with visibility, opacity and offsets; unsupported blend modes or
grouped stacks retain the standard merged-image still fallback. The parser bounds
input to 32 MiB, expanded ZIP data to 128 MiB, per-entry payloads to 16 MiB and
animated canvases to 4 megapixels.

SVGZ identity reads only a bounded decompressed XML prefix and recognizes large SVG documents without requiring their complete expansion. SVG remains static. Explicit application/postscript and application/x-krita thumbnail MIME aliases keep content providers' MIME values on the image route. EPS support remains the existing EPSI, binary preview and ImageMagick raster subsets; this does not introduce a general PostScript interpreter.

## Authoring documents and format-aware writing

`PaletteDocument` retains indexed PNG/IFF source data for palette and IFF range
editing. `LayerDocuments` exposes the supported flat ORA/PSD/XCF raster subsets;
`OpenRasterDocuments` adds an extension-preserving wrapper for media layers,
groups, unknown XML and encoded payloads. Opaque files can be retained without a
renderer. Pixel edits use a PNG working representation while keeping non-PNG
originals embedded. Existing ImageLayer/LayerDocument constructors and copy
signatures are unchanged.

`AnimationDocuments` imports complete APNG and indexed 8-bit FLI/FLC frames,
provides a player and encodes APNG. `AnimationProjects` adds independent layered
ORA frame documents, timings/loops and attachments. `.icanim` is a ZIP with an
XML timeline and one ORA per frame; it is an InfiniForge extension. Its identity,
metadata, thumbnails and on-demand playback are available through the existing
library capability/decoder routes. This does not supply an editor UI.

`PixelSelections` supplies raster masks, shapes, color/background selection,
combination and transparent clearing. `ImageTransform.Scale` adds an explicit
resampling choice; existing Resize calls keep their filtered behavior.

`ImageSaving` discovers actual ENCODE writers for raster images, PSD/XCF/ORA
layers, GIF/APNG frames and ICANIM. Dedicated writePalette, writeOpenRaster and
writeAnimationProject paths retain their richer models. Legacy raster-document
writers retain their subsets; they do not silently become extension-preserving
editors. FLI/FLC encoding remains future work. Decoders never imply writers.

Editor entry points request original dimensions without changing ordinary viewer
or thumbnail sampling policies. They have no fixed editor side/layer/frame/input
quota; integer/bitmap representation, actual memory, individual codec subsets
and format fields still constrain processing. Work is in memory, not tiled.

See [authoring](docs/authoring.md), [saving profiles](docs/saving.md),
[extended ORA](docs/openraster-container.md), [ICANIM](docs/animation-projects.md),
[compatibility](docs/compatibility.md) and [validation evidence](docs/validation.md).
