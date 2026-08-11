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

## Book-scale pipeline (Milestone 1 acceptance)

Method: 120 synthetic pages through the real pipeline — normalize, ingest,
restart the repository, reorder, edit one page's geometry, export. No fakes.

| Date | Measurement | Result | Budget |
|---|---|---|---:|
| 2026-08-11 | Pages surviving capture + repository restart | 120 / 120, order preserved | 120 |
| 2026-08-11 | PDF overhead per page | **470 B** | ≤ 1024 B |
| 2026-08-11 | Output ÷ source on this dataset | 1.175 | — (see note) |

Note on the ratio: these synthetic pages are ~2.7 KB each (mostly flat white),
so a fixed ~470 B/page cost is 17% of them. Real scans are hundreds of KB per
page, where the same constant is well under 1%. The test asserts the constant,
because that is the invariant that actually holds; asserting a ratio would
only measure how compressible the test's own images happen to be.

Test: `app` → `BookScaleSmokeTest`.

## Privacy (asserted, not intended)

| Date | Claim | How it is asserted |
|---|---|---|
| 2026-08-11 | The app cannot reach the network | The **merged** manifest declares no `INTERNET` and no `ACCESS_NETWORK_STATE`. |
| 2026-08-11 | Imports need no storage permission | No `READ_MEDIA_*` / `READ_EXTERNAL_STORAGE` in the merged manifest; the Photo Picker is used instead. |

The merged manifest is the right target: this found `ACCESS_NETWORK_STATE`
arriving transitively via `androidx.media3` (a CameraX dependency for video
capture, which this app does not use). It is now removed explicitly with
`tools:node="remove"`, and the test prevents a future dependency from
reintroducing it unnoticed.

Test: `app` → `PrivacyManifestTest`.

## Session storage

| Date | Measurement | Result | Budget |
|---|---|---|---:|
| 2026-08-11 | 500-page manifest decode (JVM, warm) | passes | < 100 ms |

Test: `core-session` → `FileScanRepositoryTest.book scale manifest round trips
quickly`.

## Verification status of the harness itself

| Item | Status |
|---|---|
| `./gradlew build ktlintCheck` from a **clean clone** | Passing — 152 tests, 2026-08-11. Run against a fresh `git clone` to prove nothing depends on untracked local files. |
| `.github/workflows/ci.yml` | Green. First run 2026-08-11 (`31456148852`), 152 tests; runs ktlint + assemble + tests on every push and publishes a debug APK. |
| APK-level privacy check | Passing — `aapt2 dump badging` on the built APK shows `CAMERA` and nothing else. Stronger than the merged-manifest test, since it inspects the shipped artifact. |

## On-device results — Pixel 7, Android 17 (API 37)

Measured 2026-08-11 by driving the installed debug APK over adb: real camera,
real Storage Access Framework, real PDF reader. Conditions: Pixel 7 (panther),
Android 17, 1080×2400 @ 420 dpi, debug build, 5 pages captured hand-held
indoors.

### Capture and storage

| Measurement | Result |
|---|---|
| Capture succeeds end to end | Yes — 5 pages, CameraX bound, autofocus and capture sequences confirmed in logcat |
| Page resolution | 4080×3072 (12.5 MP), full sensor |
| Page file size | ~5.3 MB per page |
| Stored format | **Baseline JPEG (SOF0)** — satisfies the `/DCTDecode` passthrough requirement |
| EXIF segment retained in the stored file | **Yes** — proof the file did not go through `Bitmap.compress`; a re-encode would have dropped it |
| Orientation handling | `"rotation": 90` in the manifest, pixels untouched — the storage invariant holds on real camera output |
| Page order after 5 captures (two of them 731 ms apart) | Correct; manifest order equals capture time order, no duplicate ids |

Storage implication worth planning for: at ~5.3 MB/page, a 300-page book is
**~1.6 GB** of app-private storage before export. Not a defect, but it makes a
quality/resolution setting a real Milestone 2 topic.

### Export

| Measurement | Result | CI equivalent |
|---|---|---|
| PDF ÷ sum of source JPEGs | **1.000096** (26,959,820 B from 26,957,229 B) | 1.0008 / 1.0016 |
| Overhead per page | **518 B** | ~470 B |
| Structure | `%PDF-`, `startxref`, `%%EOF`, `/Count 5`, 5 page objects, 5 `/DCTDecode` streams, `/DeviceRGB` | same |
| Rotation | `/Rotate 90` on all 5 pages — no pixel re-encode | same |
| **Camera JPEG bytes present verbatim inside the PDF** | **Yes** — the exact 5,318,999-byte capture is a contiguous substring of the exported PDF | same |
| Opens in an independent reader | Yes — Google Drive PDF Viewer, rendered portrait, confirming `/Rotate` is honoured | PDFBox, in CI |
| SAF default filename | `Device test.pdf`, derived from the session title | n/a |
| PSS after exporting 27 MB of pages | 146 MB total (native heap 8 MB, Dalvik 15 MB, graphics 14 MB) | n/a |

The memory figure is a **post-export** reading, not a peak-during-export
sample; it shows the document was not accumulated in memory (buffering 27 MB
would have been visible), but it is not a peak measurement. Instrumenting the
peak remains open.

### UX behaviour confirmed on hardware

| Check | Result |
|---|---|
| Camera rationale appears **before** the system permission dialog | Yes, and it states pages never leave the device |
| Permission screen offers Import as an equal alternative | Yes |
| Dialog stays above the IME with both actions reachable (SPACE-002) | Yes — verified with the Japanese keyboard open |
| Back affordance on the capture screen (NAV-001) | Yes |

## Defects found on device, and fixed (2026-08-11)

Driving the real screen found **five** defects that 152 JVM tests did not,
because every one of them lived in the Compose layer — the ViewModels' geometry
was correct throughout.

| # | Defect | Why no test caught it |
|---|---|---|
| 1 | The crop overlay measured the **composable's** bounds and treated them as the image. `ContentScale.Fit` letterboxes, so the crop covered the empty margins and the handles sat where the picture was not. | The geometry existed only inside a `@Composable`. It is now the pure `fittedImageRect`, with 9 tests. |
| 2 | **Double rotation.** Coil applies EXIF orientation by default; our storage invariant keeps EXIF in the file and puts the rotation in the page's geometry, so previews rotated twice. An upright page displayed sideways and the crop rect had the wrong extent. | The export path uses `BitmapFactory`, which ignores EXIF, so tests of *export* were correct. Only the display path disagreed. Fixed globally with `ExifOrientationStrategy.IGNORE`. |
| 3 | Corner handles fell inside the **system back-gesture strip**: dragging one closed the screen instead of cropping. | No emulator, and gesture navigation is not modelled in unit tests. Fixed with `systemGestureExclusion()` plus an inset. |
| 4 | The canvas was padded, so each handle sat on its edge and the outer half of its 48 dp touch region was **outside the canvas** and received no touch. The handle simply could not be grabbed. | Hit-testing against a real finger is not something a JVM test does. The canvas now fills the area and the *image* is inset instead. |
| 5 | `pointerInput` was keyed on the crop, so the first drag event changed the crop, restarted the gesture block and **cancelled the drag**. A full-screen drag moved the corner by one pixel. | Requires an actual multi-event drag. Fixed with `rememberUpdatedState`. |

Verified after the fixes, on the device: the overlay renders, a quarter-turned
page displays portrait, a corner drag of 275 x 360 px produced
`crop = {left 0.276, top 0.272, right 1.0, bottom 1.0}` — matching the gesture —
and it persisted to the manifest.

The lesson recorded rather than glossed: the ViewModel tests were thorough and
all passed, and the layer they did not cover is exactly where every defect was.

## Second device session (2026-08-11, later)

| Check | Result |
|---|---|
| Discard-on-back (NAV-010 / AND-004) | **Pass.** With unsaved crop/rotation, system back shows "Discard changes?" / "Keep editing" / "Discard". With no changes it closes directly, which is correct. |
| Crop handle grab and drag | **Pass** after the fixes: a 275 × 360 px corner drag produced `crop = {left 0.276, top 0.272}` and persisted. |
| Reorder by arrow buttons (the non-gesture path, A11Y-002) | **Pass.** Moving page 1 later reordered the manifest. First/last arrows are correctly disabled. |
| Reorder by long-press drag | **Not verifiable this way.** Logging showed `onDragStart` firing and then `onDragCancel` with no drag events: injecting `input motionevent` from separate processes splits the pointer stream, so the first MOVE cancels the gesture. This is a limitation of adb injection, **not evidence the feature is broken** — and not evidence it works either. Needs a finger. |
| Layout at 200 % font scale (A11Y-004, TYPE-004) | **Pass** on the session list and the page grid. The page-number chip — the predicted failure point — grew but stayed inside its cell. Restored to 1.0 afterwards. |
| Accessible names in the real a11y tree (A11Y-001) | **Pass.** `uiautomator` shows `Page 1 of 5` … `Page 5 of 5`, `Back to scans`, `Add pages`, `More options`. |
| App state after ~4 h backgrounded | **Pass.** Returned to the page grid with all 5 pages. |

One fix came out of this session even though the test was inconclusive: the
grid's own scrolling competed with the drag gesture, so `userScrollEnabled` is
now off while reordering. Edge auto-scroll is driven programmatically, and the
arrow buttons work regardless.

## Page detection (Milestone 2, in-house detector)

Measured in CI on synthetic pages with **known** corners — the reason
[ADR-0008](adr/0008-page-detection.md) chose a hand-written pipeline over a CV
dependency this project cannot execute. Error is the mean distance between
detected and true corners, in fractions of the frame.

| Case | Corner error | Confidence |
|---|---:|---:|
| Flat page, plain background | **0.002** | 0.88 |
| Strong perspective | **0.002** | 0.74 |
| Sensor noise (σ ≈ 12 levels) | **0.003** | 0.88 |
| Low contrast (page 200 vs desk 165) | **0.002** | 0.88 |
| Straight-edged clutter behind the page | **0.003** | 0.87 |
| Featureless frame | not found, confidence 0 — correct | — |

0.002 of the frame is roughly 8 px on a 4080 px capture. Confidence tracks
difficulty: the perspective case scores lowest, which is what the corner-angle
term in the score is for.

Latency: **~100 ms** for a 2040×1536 input on a desktop JVM, after the pipeline
downscales to a 600 px working edge. (The figure varies with build parallelism;
the assertion budget is 4 s, far above it.)

Two findings worth keeping, both from measurement rather than reasoning:

1. Detection failed on the *cleanest* images while succeeding on noisy ones.
   Otsu's threshold was computed over the whole suppressed gradient field, where
   >99 % of pixels are exactly zero; the zero bin dominated so completely that
   the split collapsed to zero and every pixel became an "edge". Excluding zeros
   fixed it — and the guard that turned this into a failure rather than a
   degradation (`if (threshold <= 0) return NOT_FOUND`) was guarding the wrong
   quantity.
2. A single threshold could not keep both the horizontal and the slanted edges
   of a perspective-distorted page: a slanted edge rasterizes as a staircase
   whose corners spike *above* a straight edge's response, so Otsu kept the
   sides and discarded the top and bottom, leaving nothing to build a
   quadrilateral from. Canny-style hysteresis (low = 0.4 × high, grown along
   connected pixels) recovered it: 454 → 2538 edge points, 2 → 4 lines.

Tests: `vision` → `ContourPageDetectorTest`, `ImageOpsTest`,
`PipelineDiagnosticTest` (the last prints per-stage counts, because "detection
failed" is not a diagnosis in a six-stage pipeline).

## Not measured for detection

- **Real photographs.** Every number above is synthetic. Glossy paper, curved
  pages near the binding, gutter shadows, fingers and genuinely busy desks are
  in `docs/benchmark.md`'s dataset categories and none of them is covered yet.
  Synthetic pages establish that the pipeline is correct, not that it is good.
- Any comparison against OpenCV, which would need a device (ADR-0008).
- On-device latency; the figure above is a desktop JVM.

## Still not measured on device

These were **not** exercised in the 2026-08-11 session and remain open; the
procedure for each is in [device-test-plan.md](device-test-plan.md):

- 100+ page capture session (only 5 pages were captured)
- precise per-page export latency, and peak memory *during* export
- cancelling an export mid-flight, and whether the partial document is really
  removed by a live SAF provider
- long-press drag reorder — needs a human finger (see above for why adb cannot
  test it); the arrow-button path is verified
- the predictive-back *animation* (the dialog it triggers is verified), TalkBack
  focus order with the screen reader actually running, and gesture insets
- a cropped 12 MP page through the re-encode path (`maxReencodedDimension`
  defaults to unlimited)

## Not yet measured

Stated explicitly so absence is not mistaken for a result. Every item below has
a procedure in [device-test-plan.md](device-test-plan.md); none has been run,
because no device has been available to this session:

- per-page export latency, p50/p95, and peak memory on a real device — the
  120-page smoke test proves correctness at scale, not timing;
- pages scanned per minute with a real camera and a real book;
- capture quality (focus, exposure) on real hardware;
- **peak memory when exporting a cropped high-resolution page.** Now concrete:
  device captures are 4080×3072, so this path decodes a 12.5 MP bitmap
  (~50 MB as ARGB_8888).
  `JpegPdfExporter.maxReencodedDimension` defaults to null (keep full size), so
  a cropped 12 MP capture is decoded at full resolution during export. Only the
  cropped pages take this path — passthrough pages are never decoded — but the
  smoke test uses 480×640 images and does not exercise it. Needs a device;
- anything involving page detection, OCR, or a From-Scratch/Production
  comparison — those engines do not exist yet.
