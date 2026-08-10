# Benchmark Plan

## Purpose

Benchmarking decides whether an implementation is actually better for this project.

## Dataset Categories

Create representative test samples:

1. Flat page, good lighting
2. Page with strong perspective
3. Curved page near binding
4. Two-page spread
5. Glossy paper
6. Low light
7. Uneven light / shadow
8. Small Japanese text
9. Vertical Japanese text
10. Ruby/furigana
11. Mixed Japanese + English + numerals
12. Diagram-heavy technical book
13. Photo-heavy page
14. Table-heavy page
15. Hand/finger partially visible

Use only content that can legally be included in the repository. For copyrighted books, store private benchmark samples locally and document how to reproduce the benchmark rather than committing scans.

## Page Detection Metrics
- success/failure
- corner positional error
- IoU if ground truth mask exists
- false page detection rate

## OCR Metrics
- Character Error Rate
- Word Error Rate where meaningful
- line segmentation accuracy
- reading order
- vertical text
- numerals/symbols

## Runtime Metrics
- average latency
- p50
- p95
- peak memory
- allocations where measurable
- CPU
- thermal/battery proxy

## Product Metrics
- pages scanned per minute
- manual corrections per 100 pages
- crash/recovery rate
- final PDF size
- export time

## Comparative Rule

Every comparative claim should identify:
- device
- build mode
- image resolution
- implementation version
- dataset
- benchmark method

---

# Measured Results

Every number here comes from an assertion in the test suite, so it is
reproducible with `./gradlew test testDebugUnitTest` and cannot silently rot.
Tests print `MEASURE ...` lines; CI collects them into the job summary.

Per the comparative rule above, each entry states its conditions.

## Environment capability baseline

| Date | Environment | Finding |
|---|---|---|
| 2026-08-11 | Robolectric 4.16.1, `@GraphicsMode(NATIVE)`, `sdk=35`, JDK 17 | `Bitmap`/`Canvas` and JPEG encode/decode are real and measurable. |
| 2026-08-11 | same | `android.graphics.pdf.PdfDocument` is **not** available: `startPage` throws `IllegalStateException: document is closed!`. No emulator alternative — build hosts have no `/dev/kvm`. |

Consequence: PDF behaviour is measured through `:pdf-writer` (pure JVM,
verified against Apache PDFBox 3.0.3) rather than through the platform API.
See [ADR-0007](adr/0007-pdf-export-jpeg-passthrough.md).

Test: `engine-production` → `GraphicsCapabilityTest`.

## PDF export — output size

Method: synthetic paper-like noise pages (deterministic PRNG; flat colour would
compress to nothing and make the ratio meaningless), encoded as baseline JPEG,
exported, output bytes ÷ sum of source JPEG bytes.

| Date | Subject | Dataset | Ratio | Budget |
|---|---|---|---|---:|
| 2026-08-11 | `PdfImageDocumentWriter` | 10 × 800×1100 noise, q90 (5.87 MB) | **1.0008** | 1.05 |
| 2026-08-11 | `JpegPdfExporter` end-to-end | 8 × 600×800 noise (2.41 MB) | **1.0016** | 1.05 |

Overhead is ~490 B/page: object dictionaries plus one xref row. ADR-0004's
original gate was 1.5.

Tests: `pdf-writer` → `PdfImageDocumentWriterTest.output size stays within a
small overhead of the source jpegs`; `engine-production` →
`JpegPdfExporterTest.output size stays close to the sum of source jpegs`.

## PDF export — fidelity

| Date | Claim | How it is asserted |
|---|---|---|
| 2026-08-11 | Uncropped pages are embedded with **zero re-encode** | The raw `/DCTDecode` stream read back via PDFBox is byte-identical to the source JPEG. |
| 2026-08-11 | Camera-format captures are stored with zero re-encode | `AndroidPageImageNormalizer` copies baseline JPEG byte for byte and reports `losslessCopy = true`. |

## PDF export — validity

Asserted by parsing/rendering with Apache PDFBox 3.0.3 (independent
implementation), not with our own reader: page count, page order (rendered
pixel colour per page), `/Rotate`, media box, `/DeviceGray` vs `/DeviceRGB`,
and a 120-page document.

## Session storage

| Date | Measurement | Result | Budget |
|---|---|---|---:|
| 2026-08-11 | 500-page manifest decode (JVM, warm) | passes | < 100 ms |

Test: `core-session` → `FileScanRepositoryTest.book scale manifest round trips
quickly`.

## Not yet measured

Stated explicitly so absence is not mistaken for a result:

- per-page export latency, p50/p95, peak memory on a real device;
- pages scanned per minute (needs the capture UI);
- anything involving page detection, OCR, or a From-Scratch/Production
  comparison — those engines do not exist yet.
