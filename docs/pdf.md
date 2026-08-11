# PDF generation

The scope, guarantees and limits of `:pdf-writer` and the Production exporter.
The decision behind it is [ADR-0007](adr/0007-pdf-export-jpeg-passthrough.md).

## What it produces

An image-only PDF 1.4 document: one page per scanned page, each carrying a
single full-bleed image XObject.

```text
%PDF-1.4
2 0 obj  /Type /Catalog  /Pages 1 0 R
1 0 obj  /Type /Pages    /Kids [...] /Count N
per page:
  /Type /XObject /Subtype /Image /Filter /DCTDecode  <raw JPEG bytes>
  content stream:  q  W 0 0 H 0 0 cm  /Im0 Do  Q
  /Type /Page /MediaBox [0 0 W H] /Rotate R /Resources ...
xref + trailer + startxref + %%EOF
```

Object 1 (page tree) and 2 (catalog) are reserved up front and written last, so
each page can reference `/Parent 1 0 R` as it is emitted. PDF object numbers are
independent of file order, so this is legal and it removes the need to buffer.

## The guarantee that matters: no re-encoding

A page whose stored bytes are a baseline JPEG and which has **no crop** is
embedded **verbatim**. The bytes in the PDF are byte-for-byte the bytes the
camera produced — asserted in tests by reading the raw `/DCTDecode` stream back
out through Apache PDFBox and comparing with the source.

Rotation does not break this: it becomes the page's `/Rotate` attribute rather
than rotated pixels.

Two things force a re-encode:

| Condition | Why | Result |
|---|---|---|
| The page is cropped | Cropping is a pixel operation; PDF has no lossless JPEG crop | Rendered by `PageTransformer`, re-embedded. **`/Rotate` is then 0** — rotation is already in the pixels, and setting both would rotate twice. |
| The bytes are not embeddable | Progressive JPEG (SOF2) renders inconsistently under `/DCTDecode`; CMYK/YCCK needs an Adobe transform and a `/Decode` array | Decoded and re-encoded as baseline |

In practice the second case is rare, because `PageImageNormalizer` converts
imports to baseline JPEG on the way in (see the storage invariant below).

`JpegPdfExporter.lastExportStats` reports the passthrough/re-encode split, so
this is observable rather than assumed.

## The storage invariant it relies on

Every committed page file is a **baseline JPEG**, and any EXIF orientation the
source declared has been moved into the page's geometry rather than burned into
its pixels.

Established at the single write boundary (`PageIngestor` →
`PageImageNormalizer`), which means:

- camera captures are stored with **zero re-encode** — orientation becomes page
  state, not a rewritten image;
- PNG, WebP, HEIC and progressive JPEG imports are transcoded exactly once, on
  import, and `NormalizedPage.losslessCopy` reports which happened;
- decoders and exporters **must not** read EXIF again. Doing so would rotate
  the page a second time.

## Page size

Displayed page size only; it never affects image quality, because the embedded
image keeps every pixel it had regardless of the media box.

- Default `PageSizePolicy.FitLongestEdge(842pt)` — each page is scaled so its
  longest edge matches A4's long edge, preserving aspect ratio. Mixed-resolution
  scans then print at a consistent physical size with no letterboxing.
- `PageSizePolicy.FixedDpi(dpi)` — treat the image as scanned at a known
  resolution.

## Memory and streaming

Written for a **forward-only** stream, because SAF's `CreateDocument` output
cannot be rewound: every object's byte offset is recorded as it is emitted, and
the xref table is written from that record at the end. The document is never
buffered — a 120-page export holds one page's bytes at a time.

## Measured

| Property | Result |
|---|---|
| Overhead per page | ~470 B (object dictionaries + one xref row) |
| Output ÷ source, 10 photographic pages | 1.0008 |
| Output ÷ source, end-to-end via the exporter | 1.0016 |

Full conditions in [benchmark.md](benchmark.md).

## Verification

Structural claims are asserted by parsing **and rendering** the output with
Apache PDFBox 3.0.3 — an independent implementation, test-only, never shipped.
A hand-written PDF that only our own reader accepts would say nothing about
whether real readers can open it.

Covered: page count, page order (by rendered pixel colour), `/Rotate`
including negative normalisation, media box geometry, `/DeviceGray` vs
`/DeviceRGB`, raw stream identity, a 120-page document, and correct output on a
stream that cannot seek.

## Limits (deliberate, for Milestone 1)

- **Image-only.** No text layer, so exported PDFs are not searchable. That is
  Milestone 3, and it will need its own ADR — `/DCTDecode` image pages are a
  much smaller problem than fonts and text positioning.
- No compression of the page tree, no object streams, no incremental update.
  Irrelevant when >99.9% of the file is JPEG data.
- No metadata, outline, or bookmarks yet (Milestone 6).
- No encryption, no digital signatures. Not planned.
- Cropped pages are re-encoded once, at quality 92. A lossless JPEG-domain crop
  is possible in principle but only on MCU boundaries, and is not worth the
  complexity at this milestone.

## Related

- [ADR-0004](adr/0004-pdf-export-mvp.md) — the superseded platform-API decision
  and the evidence behind it
- [ADR-0007](adr/0007-pdf-export-jpeg-passthrough.md) — the current decision
- [ADR-0006](adr/0006-module-structure.md) — why `pdf-writer` is its own
  pure-JVM module
