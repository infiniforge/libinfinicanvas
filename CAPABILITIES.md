# Runtime capabilities — canvas

Updated 2026-11-18.

## Linked dependencies

Stream (`stream-core`); AndroidSVG, JxlCoder and OpenJPEG Android dependencies are declared by `canvas-android`.

Dependencies must be packaged/linked, not assumed to be present. Standalone source
builds resolve exact source revisions under `dependencies/` through includeBuild;
an embedding source-composite build can supply its declared dependency root. Android modules need SDK 36, min API 26.
A library dependency is allowed; an InfiniFolder application/UI dependency is not.

## Public discovery and execution

`CanvasCapabilities.registry`, `ImageCapabilityHandler`, `ByteImageCapabilityHandler`; existing `CanvasStreams` and `ThumbnailDecoder` execute discovered decoders. Bitmap transforms and PNG encoding expose real services.

Use `RuntimeCapabilities.library("canvas")` for the common query, probe, callback and
revision API. The schema and registration contract are documented in the linked
libinfinistream repository's `CAPABILITIES.md`. Providers ship a standard
`META-INF/services/io.github.gretchycat.infinistream.CapabilityProvider` resource.
Constructing a provider must not connect, open input or initialize the application.

Implement/register an image handler and its provider. The image viewer and icon renderer use the same registry; MIME-only handlers can receive MIME hints. Byte handlers work without a resolver; Android Bitmap output still needs Android.

Queries retain overlapping handlers. Availability is separate from metadata-only
identity. Resource probes are bounded/cancellable and can narrow operations; real
API execution still validates input. Use existing Stream sources, positional I/O,
owned outputs and typed processors; do not duplicate decoders in consumers.

## Limits

`canvas.builtin` retains the existing platform/legacy decoder multiplexer. It is CONDITIONAL, not a universal image decoder. Several families remain grouped and their subset/native restrictions are validated on decode. Netpbm, QOI/farbfeld and TGA have separate executable byte handlers. Animation UI still uses the existing Canvas frame APIs; a new presentation can supply its own viewer component.

Discovery is for installed classpath components, not a downloaded code loader.
Android/native display and live remote behavior require device checks in addition
to JVM tests. Descriptive catalog exports do not prove runtime support.

## Standalone build and tests

```sh
python3 scripts/fetch-dependencies.py
./gradlew :canvas-android:testDebugUnitTest :canvas-android:assembleDebug
```

No browser process is required. Keep existing format regression suites; capability
tests invoke actual services/parsers rather than scripted success responses.

## New format modules

New image and graphics handlers must validate bytes with a bounded signature or the real image parser, and expose bounded metadata extraction through `MetadataCapabilityHandler`. Report dimensions, pixel format/depth, and embedded metadata only when the decoder actually reads them. Follow the shared [Stream conformance rule](https://github.com/infiniforge/libinfinistream/blob/main/CAPABILITIES.md#format-module-validation-and-metadata-requirements).

## Host audit record

InfiniFolder's 2026-11-04 runtime-discovery audit exercises this repository through
its standalone Gradle test task, and also builds the main and combined standalone
viewer APKs. The host `docs/runtime-capabilities.md` records exact commands, fresh
counts, failures repaired during the gate and unverified device paths. This is
separate from the historical extraction evidence in SOURCE_PROVENANCE.md.

## Format writers

`canvas.save.raster` registers actual `ImageWriter` ENCODE services. Query
`ImageSaving.formats` for targets and preservation limits; source decode support
does not imply writing. See [saving APIs](docs/saving.md). Existing PNG processor
registration remains compatible. Host apps own publication and overwrite policy.

## Extensible media and animation projects

`canvas.animation-project` advertises `.icanim` /
`application/x-infinicanvas-animation` for IDENTIFY, METADATA, DECODE and THUMBNAIL.
Probes validate its ZIP mimetype through ImageSignatures and keep those executable
operations available; decode validates the timeline and ORA frame content.
Bounded metadata reads report width, height, frame count, duration and loop count
without rendering pixels. Viewers sample frame composites on demand.

ImageSaving's existing writer provider also advertises ICANIM. Its generic
writeAnimation entry point creates single-layer ORA frames; writeAnimationProject
preserves full frame documents. Extended ORA uses writeOpenRaster; old raster
value types and writer calls keep their signatures/subsets.

See [ORA](docs/openraster-container.md), [ICANIM](docs/animation-projects.md),
[compatibility](docs/compatibility.md) and [validation](docs/validation.md).
