# API compatibility and ownership

The November 2026 authoring/container additions extend libinfinicanvas. Host
applications continue to own their UI, document lifecycle, edit history, file
publication and decisions about source preservation. The library has no editor
Activity dependency.

## Retained entry points

- `ImageLayer` and `LayerDocument` retain their original constructors, component
  fields and Kotlin copy signatures. The mixed-media model wraps them instead
  of adding constructor parameters.
- `LayerDocuments.decode(bytes,targetPixels)` and `encodeOra(document)` retain
  the flat/raster subset. Choose `OpenRasterDocuments` explicitly for extended
  ORA groups, arbitrary encoded media and extension-preserving saving.
- Existing `ThumbnailDecoder` viewer/thumbnail overloads keep their sampling
  policies. `decodeEditorFrame`/`decodeEditorImage` are separate source-dimension
  entry points; default authoring targetPixels remains 2048 where documented.
- Existing `BitmapFrame`, `BitmapAnimation`, cycling and drawable ownership
  contracts remain available. FLI/FLC authoring reuses the indexed delta decoder;
  it does not change the old playback method signatures/defaults.
- Existing PNG processors and `ImageEncoding.png` remain intact. `ImageSaving`
  adds discovery-based writers and explicit preservation paths; a source decoder
  does not imply an encoder.
- `ImageTransform.Resize` and its always-filtered wire behavior remain unchanged.
  `Scale(width,height,filtered)` is additive. Source callers with exhaustive
  Kotlin `when` branches over ImageTransform must handle Scale when rebuilding.

The separate models are `OpenRasterPackage`/`OpenRasterDocument`/`OpenRasterAsset`
and `AnimationProject`/`AnimationProjectFrame`. Layer values describe spatial
content; animation frame documents are ordered by their own timeline. ICANIM
version 1 does not encode an application's undo history or UI state.

## Input and output safety

Buffers, documents and bitmaps are host-owned; keep them valid while decoders,
players and writers use them. Call expensive document operations on a worker.
Streams passed to saving methods are borrowed and stay open. Failed writes can
leave partial bytes; stage output before publishing. These functions neither
open an original URI for overwrite nor choose destinations for the host.

Unknown encoded ORA media can be preserved without a renderer. Pixel editing
must reject an opaque layer rather than replacing it with an empty PNG.
PaletteDocument patches original indexed sources; rasterization and RGB writers
cannot claim to retain palette identity. See [authoring](authoring.md),
[extended ORA](openraster-container.md), [saving](saving.md) and
[animation projects](animation-projects.md) for exact behavior and losses.

Editor APIs remove fixed application-oriented side/count/input quotas, while
retaining format/checksum/path/XML validation and actual bitmap/integer limits.
Legacy viewer/thumbnail and individual codec bounds still apply to those routes.
Allocation and backend failures remain possible; this is not unlimited memory.

## Evidence

JVM reflection checks cover the old layer constructors/copy methods and document
entry points. The InfiniCanvas native harness compiles existing Java constructor,
decode/encode/writer and renderer calls and exercises real Android pixels/DOM/ZIP.
Its 2026-11-18 record reports 109 library JVM cases and 27 native groups passing;
see [validation](validation.md) for scope and limitations. This evidence does not
prove every third-party consumer, source format or handset interaction works.
