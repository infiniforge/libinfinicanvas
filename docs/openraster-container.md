# Extensible ORA media containers

`OpenRasterPackage.read(bytes)` reads the full ZIP/XML package without requiring a
visual decoder. Its entries retain every encoded payload; its references describe
individual media layers. `write(output)` round-trips entry contents and the original
stack.xml bytes. ZIP compression metadata/order other than mimetype is not retained.

`OpenRasterDocuments.decode(bytes)` adds visual decoding and editor layer values.
Each referenced media file gets a layer, including non-PNG images, animations, IFF
palette cycling and opaque files. Extra unreferenced payloads become hidden layers.
Control/generated entries (`mimetype`, `stack.xml`, `mergedimage.png`, `Thumbnails/`)
are excluded. Unknown audio/video/file assets are retained layers with no visual
renderer, not converted to blank PNGs. Original bytes remain available in assets.

The additive `OpenRasterDocument` wraps the existing `LayerDocument`, a package,
and an asset map; `ImageLayer`/`LayerDocument` constructors and copy signatures have
not changed. `ImageSaving.writeOpenRaster` saves this model. Legacy
`LayerDocuments.decode`/`encodeOra` and `ImageSaving.writeDocument(LayerDocument,...)`
retain their previous flat/raster subsets and signatures.

The new writer preserves groups, custom XML attributes/elements, unrelated entries
and each untouched encoded media file. Supported group opacity, isolation, offsets
and five blend modes participate in composition. Unknown leaf blends are retained,
with a warning and normal preview fallback. Unknown group blends also preview as
normal. This is not a color-managed general ORA effects engine. Reorder within a
group is supported; cross-group reparenting is not yet an editor operation.

Palette/cycle edits to embedded IFF use PaletteDocument, preserving index identity,
BODY bytes and existing DRNG records. Pixel edits create a PNG working layer; a
non-PNG original stays embedded and `infini:original-src` in namespace
`urn:infiniforge:ora:1` links it. This includes newly imported media. Opaque assets
reject pixel edits; name, visibility/order and explicit deletion still work.
Explicitly deleting the last reference removes its payload so it does not reappear
as an extra hidden layer on reopen. Duplication uses copy-on-write encoded paths.

`OpenRasterDocuments.playback` exposes a Drawable/Animatable for embedded library
animations and IFF cycling while preserving the composition. Hosts own lifecycle,
callbacks, bitmaps and worker invocation. It does not provide audio/video editing.

This extends ORA beyond the standard PNG-only interchange subset. Readers without
these extensions may only show mergedimage.png or ignore unsupported media. Exports
keep the original source file unchanged. ZIP path/duplicate/XML entity checks remain;
there is no editor byte/layer-count quota. In-memory decode and actual format/backend
constraints still determine feasibility.
