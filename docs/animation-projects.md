# InfiniCanvas animation project (.icanim), version 1

An `.icanim` file is an ordinary ZIP archive. It contains a UTF-8 XML timeline and
one independent OpenRaster document per frame. Frames are timeline entries; layers
belong to each frame's ORA document. This is an InfiniForge extension, not part of
the OpenRaster standard. Other tools can unzip it and open the ORA frames separately.

## Wire layout

```text
mimetype                  application/x-infinicanvas-animation
animation.xml
frames/0.ora
frames/1.ora
sources/original.fli       optional retained input / other attachments
```

The writer places `mimetype` first and uncompressed, followed by `animation.xml`.
Frame paths are relative ZIP paths, not external URLs. Frame order follows XML
order; filenames do not define timing or ordering.

```xml
<animation version="1" w="320" h="200" plays="0">
  <frame id="frame-0" name="First frame" src="frames/0.ora" duration-ms="40"/>
  <frame id="frame-1" name="Second frame" src="frames/1.ora" duration-ms="90"/>
</animation>
```

`w`/`h` are positive integer canvas dimensions shared by every frame ORA. IDs must
be unique and nonempty. `duration-ms` is 1–65535000; `plays=0` repeats indefinitely,
positive values count complete plays. At least one frame is required. Unknown
versions fail explicitly. The version-1 writer regenerates known timeline XML;
it preserves extra ZIP attachments, but does not round-trip unknown XML fields.
A later timeline version can define tracks, cels, audio and interpolation without
misrepresenting them as image layers. These are not implemented in version 1.

ZIP paths are validated; duplicate entries, traversal and external XML entities
are rejected. Opening/encoding is in memory, with no fixed editor file/frame quota.
Bitmap representation, memory, ZIP/format fields and integer overflow still apply.
Frames can include the mixed media supported by the extended ORA model. Opaque
assets survive but have no visual renderer. Frame playback/export uses each frame's
static ORA composite; independent animations/cycles inside a frame are retained in
its ORA but are not advanced by the outer timeline player.

## Library API and discovery

- `AnimationProjectFrame(id,name,document,delayMillis)` contains an
  `OpenRasterDocument`; `AnimationProject(width,height,frames,plays,attachments)`
  contains the ordered timeline and additional encoded files.
- `AnimationProjects.decode(bytes)` reads the project; `write(project,output)` and
  `ImageSaving.writeAnimationProject(project,output)` save it, borrowing the stream.
- `fromAnimation(AnimationDocument)` creates independent single-layer frame ORAs.
  `flatten(project)` returns complete RGBA frames for APNG/GIF encoding/playback.
- `canvas.animation-project` registers IDENTIFY, METADATA, DECODE and THUMBNAIL via
  the existing capability provider. `CanvasFormats`/`CanvasCapabilities`, byte
  identity, `ThumbnailDecoder` and `CanvasStreams.decodeViewer` recognize `.icanim`
  and its MIME type. `ImageSaving.formats` also advertises the writer.
- Metadata reads the timeline within a separate bounded metadata budget and reports
  dimensions, frame count, duration and loop count without rendering ORA pixels.
  It may return no metadata when the timeline is beyond that budget.
- Shared viewer playback decodes sampled ORA composites on demand, keeping the
  first poster and current frame instead of retaining every full-size bitmap.
  Editor import retains each frame's complete layer/media structure.

`AnimationDocuments.decode(bytes,targetPixels)` additionally imports indexed
8-bit Autodesk FLI/FLC through the existing palette/delta decoder, capturing full
composited frames and rounding its timing to milliseconds. Playback decoder APIs
and their defaults are unchanged. APNG import (including indexed APNG) uses the
existing disposal/blend compositor. APNG export is lossless RGBA pixels/timing;
source patch layout, palette identities, metadata and frame layers are omitted.
There is no FLI/FLC writer yet. Preserve the source as an attachment, save edits
as `.icanim`, or export APNG/GIF. All bitmaps/buffers are host-owned; use workers.

References: [PNG 3 / APNG](https://www.w3.org/TR/png-3/),
[FLIC format](https://www.compuphase.com/flic.htm),
[OpenRaster](https://www.openraster.org/baseline/file-layout-spec.html).
