# Format-aware saving and native authoring input

## Writer discovery and ownership

`ImageSaving.formats` discovers `ImageWriter` services from the Canvas ENCODE
registry. `format(extension)` accepts aliases or a canonical ID and returns null
when no writer is installed. `ImageSaveFormat` reports MIME, extensions, alpha,
layer/animation/indexed/palette-cycling support and subset limitations. Decoders do not imply encoders.

`ImageSaving.write(bitmap, format, output, quality=95, background=WHITE)` borrows
both arguments, writes buffered bytes, flushes its buffer and leaves the caller's
stream open. Quality is 0–100. Non-alpha targets composite against an opaque
background; JPEG uses that background even for partial alpha. Writers expose
format-specific losses; they do not preserve arbitrary source metadata/precision.

`writeDocument(document, format, output)` and `writeAnimation(document, format,
output)` preserve only the target writer's supported structure. Register new
`ImageWriter` services with ENCODE descriptors to extend the list and override
document/animation writing when supported. Unknown targets throw IOException;
they are never replaced with PNG under another filename.

`ImageSaving.writePalette(document, output, colors=document.colors,
cycles=document.cycles)` is the preservation path for indexed PNG and IFF. It
patches the original color table and supported cycle ranges through PaletteDocument,
retains encoded PNG IDAT/IFF BODY pixels and unrelated chunks, and preserves
unmodified cycling records. Changed IFF ranges write CRNG; existing DRNG bytes
remain untouched. This method does not quantize a rendered RGB bitmap. App policy
must reject this path after raster/layer edits that it would omit. ORA document
writing and this palette/cycle path are primary targets; additional writers can
be added through the service contract over time.

Call on a worker. Row writers check thread interruption; native compression
checks output errors/interruption and preserves exceptions caught by Bitmap.compress.
Errors can leave partial destination bytes. Hosts must stage output and discard it
on failure before publishing. This API does not own paths, providers, overwrite
policy, user confirmations or document history. Streams are not atomic by themselves.
The existing `ImageEncoding.png`/PNG processor signatures and metadata stay intact.

## Built-in targets

| Targets | Written subset / losses |
| --- | --- |
| PNG | Still bitmap pixels and alpha; Android PNG compression. |
| JPEG | RGB; quality setting, lossy, white/default chosen background, no alpha. |
| WebP | Still lossless on API 30+; legacy Android uses lossy compression. |
| BMP | Top-down 32-bit BGRA BITMAPV4 with channel/alpha masks. |
| TGA | Top-origin uncompressed RGBA8; unsigned 16-bit dimensions. |
| QOI | RGBA8 with valid explicit RGBA chunks; favors simple streaming over compression. |
| farbfeld | RGBA16 samples expanded exactly from RGBA8. |
| PPM/PNM | Binary RGB P6, matted alpha. |
| PGM/PBM | Grayscale P5 / thresholded monochrome P4, matted alpha. |
| PAM | RGB_ALPHA P7, four 8-bit channels. |
| TIFF | Single-page uncompressed RGBA8, unassociated alpha, one chunky strip. |
| IFF/ILBM/LBM | RGB24 ILBM with binary mask; partial alpha thresholded at 128. Original indexed identity and cycles use PaletteDocument.encode instead. |
| ORA | Legacy raster writer; extended media preservation uses writeOpenRaster(OpenRasterDocument,output). |
| ICANIM | ZIP/XML timeline with independent ORA frames; writeAnimationProject preserves layered frame documents. |
| PSD | Version 1 RGB8 raw layer channels, Unicode names, opacity/visibility/offsets, five supported blend modes, merged RGBA. PSD field limits apply. |
| XCF | Version 0 RGBA8 raw 64×64 tiles, names, opacity/visibility/offsets; normal blend only. Other blends fail. |
| APNG | Complete RGBA8 frames; existing AnimationDocuments encoder. |
| GIF | Still/frame sequence, RGB332 quantization with one transparent index, binary alpha, centisecond delays, full frames with background disposal, loop extension. Constant-space LZW with frequent clear codes favors simplicity over compression. GIF's dimension/delay/repeat field sizes apply. |
| SVG/SVGZ | Embedded PNG pixels in SVG, optionally gzip; source vector objects are not reconstructed. |

Generic LayerDocument raster exports omit source groups, masks, text/effects,
profiles, precision, application-specific data and edit history. The explicit
writeOpenRaster/writeAnimationProject APIs preserve their extended container
models as described in the ORA/ICANIM contracts; they do not save host undo/UI state. Exporting a decoded RAW, Krita or
legacy format to one of these targets does not make its source format writable.
Additional codec writers remain future work. PSD/XCF/GIF and raster writers need
external application round-trip acceptance; compilation alone does not prove it.

Format references: [Adobe PSD specification](https://www.adobe.com/devnet-apps/photoshop/fileformatashtml/),
[GIMP XCF specification](https://developer.gimp.org/core/standards/xcf/),
[QOI specification](https://qoiformat.org/qoi-specification.pdf),
[GIF89a specification](https://www.w3.org/Graphics/GIF/spec-gif89a.txt).
The Graphics Interchange Format and GIF service mark belong to CompuServe Incorporated.

## Native-size input and practical constraints

`ThumbnailDecoder.decodeEditorFrame(uri, extensionHint, mimeTypeHint)` uses a
separate decoder instance requesting source dimensions and returns animation state
when supported. `decodeEditorImage` offers the generic still-decoder fallback.
Existing viewer/thumbnail overloads keep their sampling policies. SVG retains
intrinsic dimensions rather than being upscaled to an artificial target; missing
SVG dimensions use a 300×150 raster canvas. JPEG XL uses its native dimensions.
Platform image and custom-source byte limits are not imposed by the editor entry
point; individual legacy codecs still have documented subsets/safety restrictions.

`PixelGeometry.validate` rejects nonpositive dimensions and RGBA pixels exceeding
Android's signed 32-bit byte-count representation. Crop checks also prevent
coordinate overflow. These are representation checks, not a 2048-side policy.
The bitmap backend can impose smaller real dimension/allocation constraints.
Authoring adapters no longer enforce fixed 64-layer/frame or 32 Mi-pixel quotas;
compatibility defaults can still request a sampled target. They retain structural
validation, checksums, ZIP path/XML protections and genuine format-field bounds.

Processing is in-memory, not tiled or disk-backed. Large allocations may fail;
hosts should keep their previous committed document and report memory/storage
errors. This is not an unlimited-size guarantee. Native code, optional codecs,
Android version and the supplied file determine actual format support.

`ImageSaving.writeOpenRaster(document,output)` writes an OpenRasterDocument with
its encoded media and extension XML. `writeDocument(LayerDocument,"ora",output)`
retains its older raster-only subset and call signature.

`ImageSaving.writeAnimationProject(project,output)` borrows the stream and preserves independent ORA frame documents; `writeAnimation(animation,"icanim",output)` creates single-layer ORA frames. See [animation project format](animation-projects.md).
