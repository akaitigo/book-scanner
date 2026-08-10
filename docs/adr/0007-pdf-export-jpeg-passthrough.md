# ADR-0007: PDF export — in-house JPEG-passthrough writer

## Status
Accepted (2026-08-11). Supersedes the decision in [ADR-0004](0004-pdf-export-mvp.md).

## Context
ADR-0004 selected the platform's `android.graphics.pdf.PdfDocument` as the M1
Production exporter, with a pre-committed fallback: a custom minimal
JPEG-embedding writer, to be adopted if a measured file-size gate failed.

Two things emerged while implementing it.

## Requirements
Unchanged from ADR-0004, with one that turned out to be decisive:

- **Preserve book content faithfully** (`docs/requirements.md`, priority 2).
- Valid PDF, page order, streaming export, size budget, Apache-2.0 gate.
- Benchmarks must be reproducible (AGENTS.md §10: no claims without numbers).

## Evidence

### 1. The platform exporter structurally re-encodes every page
`PdfDocument` accepts drawing commands, not compressed bytes: a page is
composed by `Canvas.drawBitmap`, so a captured JPEG must be decoded and then
re-compressed by Skia. Every exported page is therefore a second-generation
JPEG. ADR-0004 recorded this as a size risk; the more important cost is that it
is a *fidelity* loss, applied to the user's only digital copy of a page they
may have already returned to a shelf.

### 2. The platform exporter cannot be measured in this project's CI
Measured 2026-08-11 (`GraphicsCapabilityTest`, Robolectric 4.16.1,
`@GraphicsMode(NATIVE)`, `sdk=35`):

| Capability | Result |
|---|---|
| `Bitmap` / `Canvas` real pixels | works |
| JPEG encode + decode | works (64×64 noise → 3344 B) |
| `PdfDocument.startPage` | `IllegalStateException: document is closed!` |

`PdfDocument()` never allocates its native handle, so the first call fails.
Robolectric ships no PDF shadow. An emulator is not an alternative: the build
hosts have no `/dev/kvm`.

The honest statement is therefore **not** "the platform exporter failed the
gate" — it is that **the gate became unmeasurable**. The platform exporter was
never measured on this project, in either direction.

### 3. The passthrough writer was measured, and the gate is not close
An image-only PDF that embeds baseline JPEG bytes verbatim as `/DCTDecode`:

| Measurement | Result |
|---|---|
| Output ÷ sum of source JPEGs, 10 noisy pages | **1.0008** (budget: 1.5) |
| Overhead per page | ~490 B (object dictionaries + xref row) |
| Embedded stream vs source bytes | byte-identical |
| End-to-end exporter, 8 pages via `JpegPdfExporter` | **1.0016** |

Verified by parsing and rendering the output with Apache PDFBox 3.0.3, an
independent implementation — not with our own reader.

## Decision
1. Export image-only PDFs with an in-house writer (`:pdf-writer`,
   `PdfImageDocumentWriter`) that embeds baseline JPEG bytes verbatim.
2. `JpegPdfExporter` (`:engine-production`) has two paths per page:
   - **passthrough** — no crop and the stored bytes are baseline JPEG: embed as
     is, express rotation as the page's `/Rotate` attribute;
   - **re-encode** — cropped, or bytes not embeddable: render pixels via
     `PageTransformer`, embed the result, and force `/Rotate 0` because
     rotation is already in the pixels.
3. A storage invariant makes passthrough the normal case
   (`PageImageNormalizer`): every committed page file is a baseline JPEG, and
   EXIF orientation is moved into the page's geometry rather than burned into
   pixels. Camera captures are therefore stored without any re-encode.
4. `PdfDocumentExporter` is deleted rather than kept as a benchmark reference:
   CI cannot execute it, and an unexercised class named "Production exporter"
   is a trap.

## Why
Faithfulness is the independent, product-level reason and it stands on its own:
passthrough is the only option that puts the camera's own bytes in the PDF.
Size and speed follow from the same property rather than being traded against
it.

The measurement gap is a second, weaker reason, and it is one about our
environment rather than about Android: it means we could not have justified the
platform exporter with evidence even if we preferred it, and AGENTS.md §10
does not permit shipping an unmeasured claim.

State plainly what this does to the project's own framing: a component
earmarked for the **From-Scratch** track (Milestone 6, "minimal valid PDF
generation") has been promoted to **Production** duty, because the Production
option is unusable here. That inverts the usual direction of AGENTS.md §1 and
is worth keeping visible rather than smoothing over. It is bounded — the writer
does one narrow, fully specified job (image-only PDF), verified against an
independent implementation.

## Consequences

Positive:
- Exported pages are bit-identical to the captured scans.
- Output size ≈ source size; the ADR-0004 gate passes by a factor of ~500.
- Zero third-party PDF dependency in the shipped app; `:pdf-writer` is pure
  JVM and its tests run in CI without an emulator.
- Milestone 6's From-Scratch PDF work starts from a tested foundation.

Negative / accepted:
- We own PDF-format correctness. Mitigation: every structural claim is asserted
  through PDFBox, and the writer's scope is deliberately small.
- Cropped pages are still re-encoded once — unavoidable without shipping raw
  pixels or a lossless JPEG-domain crop, neither of which is warranted in M1.
- Progressive/CMYK JPEG, PNG, WebP and HEIC imports are transcoded once on
  import. Documented, and reported via `NormalizedPage.losslessCopy`.
- `%PDF-1.4`, no compression on the page tree, no object streams. Fine for
  image-only documents; revisit when a text layer arrives.

## Revisit When
- **Milestone 3 (searchable PDF).** A text layer needs font resources and text
  operators. Re-evaluate then: extend this writer, or reconsider a library.
  ADR-0004's evidence on PdfBox-Android (unmaintained since 2023) still stands
  and should be re-checked at that point.
- **`GraphicsCapabilityTest` starts failing.** Its `PdfDocument` assertion is
  an intentional tripwire: if a future Robolectric gains PDF support, the test
  fails and the platform exporter becomes measurable — at which point it can be
  benchmarked against this writer instead of assumed about.
- A device-level benchmark harness (real hardware, not CI) is stood up, making
  the platform exporter comparable on faithfulness and speed.
