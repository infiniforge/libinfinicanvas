# Indexed palettes, raster layers, animation documents and pixel selections

`PaletteDocument.read(bytes)` recognizes indexed PNG and ILBM/PBM IFF sources,
retaining the encoded source and palette table. `colors`, `editableColors`,
`format`, and `cycles` expose editing values. `encode(colors, cycles)` replaces
palette values and supported cycling ranges while retaining PNG IDAT/frame
payloads or the IFF BODY and unrelated chunks. Duplicate palette values remain
separate entries: there is no RGB-to-index reconstruction or quantization.
Palette size follows the format (at most 256 entries). PNG CRCs are
checked; encoded input is retained in memory. Non-indexed PNG returns null rather than exposing its suggested palette.
PNG alpha uses tRNS; IFF alpha remains in its original transparent index/mask.
HAM/EHB expose their base palette registers; scanline palette variants are rejected.
Reversed unused CRNG/CCRT records are excluded from editable ranges while their
encoded chunks remain retained; they do not invalidate the color table.

`PaletteDocument.Cycle` describes first/last palette register, millihertz (palette
steps per second × 1000), direction, and enabled state. CRNG and CCRT compatibility
copies are deduplicated. Palette-only changes retain existing range chunks. An
explicit range change writes CRNG records, quantized to the format's rate field,
and removes superseded CRNG/CCRT copies. DRNG chunks are preserved unchanged;
editing their virtual cells is not implemented. Existing `IffColorCycling` owns
playback. Static PBM/ILBM rendering now applies its mask/transparent index even
when cycling is disabled, so stopping an animation does not change pixel alpha.

`LayerDocuments.decode(bytes, targetPixels)` exposes a `LayerDocument` with
`ImageLayer` values, ordered topmost first. Fields retain name, bitmap, x/y offset,
opacity, visibility, and supported blend mode. Host owns bitmap lifetimes. The
working canvas is uniformly scaled only when it exceeds targetPixels (default
2048 for compatibility). Use Int.MAX_VALUE for source dimensions. Fixed side,
input-byte, layer-count and aggregate-pixel quotas have been removed from this
authoring adapter. Android bitmap addressability, actual memory and format fields
remain constraints. Malformed/unsupported content fails
explicitly; the authoring API does not silently substitute a merged preview.

Supported import subsets:

- ORA: one flat stack of PNG layers; normal, multiply, screen, darken, lighten.
  Uses the archive/XML parser with path validation and external entities disabled.
  Extended ORA groups, non-PNG media and unknown XML use OpenRasterDocuments.
- PSD version 1: 8-bit RGB/grayscale raster layers, raw, PackBits RLE, ZIP, or ZIP
  with prediction. Retains Unicode names, hidden layers, alpha, and offsets.
  Supports the same five blend modes. Groups, clipping, masks, adjustment/text/
  smart-object/effect records and unsupported additional records are rejected.
- XCF v0–23: 8-bit RGB/grayscale/indexed raster layers with uncompressed, existing
  XCF RLE, or zlib tiles; 32/64-bit offsets. Retains offsets, alpha, visibility,
  names, and opacity. Normal layers only. Groups, masks, effects, unsupported
  precision, linear/Lab blend spaces and composite modes are rejected. Indexed
  XCF layers expand to RGBA; their original palette identities are not retained.

`LayerDocuments.composite` uses Android raster composition for the supported
blend modes. It is not a color-managed Photoshop/GIMP rendering engine.
`encodeOra` writes the current working layer stack, UTF-8 names, offsets, opacity,
visibility, blend modes, PNG layer assets, and a merged preview. The uncompressed
mimetype entry is first. It does not preserve masks, groups, metadata, animation,
indexed palette identities, or editor history. PSD/XCF raster export is available through ImageSaving; see [saving](saving.md).

Format references: [PNG](https://www.w3.org/TR/png/),
[IFF](https://wiki.amigaos.net/wiki/ILBM_IFF_Interleaved_Bitmap),
[Adobe PSD](https://www.adobe.com/devnet-apps/photoshop/fileformatashtml/),
[GIMP XCF](https://developer.gimp.org/core/standards/xcf/), and
[OpenRaster](https://www.openraster.org/baseline/file-layout-spec.html).

## Animation documents

`AnimationDocuments.decode(bytes, targetPixels)` recognizes APNG and indexed 8-bit FLI/FLC and returns an
`AnimationDocument(width, height, frames, plays)`, or null for other formats.
See [animation projects](animation-projects.md) for layered frame documents and FLIC details.
`AnimationFrame(bitmap, delayMillis)` holds complete displayed RGBA pixels, not a
raw patch. It reuses PngAnimationDecoder's CRC/sequence validation and disposal/
blend compositor. Delays round to milliseconds. Authoring uses the requested dimensions (default 2048 for compatibility,
Int.MAX_VALUE for source pixels), with no fixed frame-count, input-byte or
aggregate-frame-pixel quota. It materializes every complete frame in memory.
Delay input is 1–65535000 ms; output uses APNG's unsigned 16-bit rational fields
and may round when exact milliseconds do not fit. Unsupported content, integer
overflow, allocation or format-field overflow fails explicitly.

`player(document)` returns a worker-owned BitmapAnimation, respecting durations
and `plays` (zero loops indefinitely). `encodeApng(document)` writes complete
RGBA8 PNG frames with SOURCE blending, NONE disposal, loop count, millisecond
rational delays, ordered sequence numbers and chunk CRCs. Android owns PNG
compression. Output is materialized as a ByteArray. Source metadata, original patch layout,
palette identity, color profiles/precision and layer properties are not retained.
Bitmaps remain host-owned; keep them alive while a document/player uses them.
This is an additive API; existing decoder/playback signatures are unchanged.

APNG wire fields follow the [PNG 3 Recommendation](https://www.w3.org/TR/png-3/).
The app owns frame selection, UI, edit history, source retention and export policy.

## Raster selection and scale APIs

`PixelSelection(width, height, coverage)` holds caller-owned row-major alpha
coverage: 0 is unselected and 255 fully selected. Both dimensions must be positive, representable as an Android RGBA bitmap,
and the buffer must contain exactly width × height bytes. Hosts own bitmap/buffer
lifetimes and must keep them stable while worker operations use them.

`PixelSelections` provides these additive APIs:

- `rectangle(w,h,x,y,width,height,ellipse)` returns a hard-edged rectangle or
  pixel-center ellipse. Bounds must fit the working image.
- `polygon(w,h,points)` rasterizes a closed Android Region from 3–4096 finite
  in-bounds PointFs, using hard edges.
- `color(bitmap,x,y,tolerance,contiguous)` uses the tapped pixel as an anchor.
  Contiguous mode floods four-connected matches; otherwise it scans all pixels.
- `background(bitmap,tolerance)` estimates a dominant color from quantized border
  samples, averages that bucket, then floods matching pixels from every edge.
  Matching compares each RGB/alpha channel against the anchor within 0–255
  tolerance; fully transparent RGB is ignored. It is a color heuristic, not
  semantic segmentation. Hosts should let users inspect the result first.
- `combine(old,next,mode)` supports replace/add/subtract/intersect coverage.
  Add uses max, subtract clamps the difference, intersect uses min. Null old
  coverage is empty; replace/add return next, subtract/intersect return empty.
  Existing masks must have matching dimensions. `invert` complements coverage;
  `bounds` returns an exclusive-right/bottom Rect, or null for an empty mask.
- `clear(bitmap,mask)` creates ARGB_8888 pixels with alpha multiplied by the
  complement of coverage. It does not mutate its inputs. `toBitmap`/`fromBitmap`
  encode/decode coverage in bitmap alpha, suitable for lossless PNG persistence.

Selection bitmap operations validate positive dimensions and addressable
pixel counts before allocating buffers; there is no fixed editor-side quota. Hosts should invoke them off the UI thread; flood loops honor thread
interruption. The library does not own selection UI, undo or asset storage.

`ImageTransform.Scale(width,height,filtered=true)` adds explicit filtered or
nearest-neighbor resampling through `ImageTransforms.apply`. The processor's
`SCALE` options are exactly `operation`, `width`, `height`, `filtered`; the boolean
is strict `true`/`false`. Dimensions must be positive; hosts enforce their output
pixel budgets. Existing `ImageTransform.Resize`, its `RESIZE` wire options and
always-filtered behavior remain unchanged. Other existing operations and decoder
signatures retain their behavior. Source callers with exhaustive `when` over
ImageTransform must handle the new Scale variant when rebuilding.

See [format-aware saving](saving.md) for shared raster, layer and animation writers.

For extension-preserving media layers/groups, use the additive [OpenRasterDocuments API](openraster-container.md). For independent layered frame documents, use [AnimationProjects](animation-projects.md).

See [API compatibility and ownership](compatibility.md) and [recorded validation](validation.md) before adopting the new entry points.
