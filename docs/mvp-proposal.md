# Deliverable B — MVP Technical Proposal (Milestone 1)

Goal: the smallest architecture that delivers a genuinely usable scanner
(capture/import → session → crop/rotate/reorder/delete → PDF export → resume)
while keeping every future Production/From-Scratch substitution point behind
a stable contract.

Selections below are justified in `docs/technology-candidates.md` (Deliverable
C) and the ADRs (Deliverable E). This document describes the resulting shape.

## 1. Build / module structure

Gradle (Kotlin DSL, version catalog), five modules:

```text
book-scanner/
  app/                  Android application: Compose UI, navigation,
                        composition root (manual DI), CameraX glue
  core-contracts/       PURE JVM Kotlin: domain model + engine contracts
                        (ScannedPage, ScanSession, PdfExporter, ScanRepository,
                        PageStore, EngineId, …). No Android types.
  core-session/         PURE JVM Kotlin: FileScanRepository — session dirs,
                        atomic manifest.json, page-file management.
                        Depends only on core-contracts + kotlinx-serialization.
  pdf-writer/           PURE JVM Kotlin: minimal image-only PDF writer
                        (DCTDecode passthrough) + JPEG header parsing.
                        Added by ADR-0007; see ADR-0006's amendment.
  engine-production/    Android library: Production engine implementations for
                        M1 — JpegPdfExporter, BitmapPageTransformer (crop/
                        rotate), AndroidPageImageNormalizer. Depends on
                        core-contracts + pdf-writer.
```

Why this shape (full reasoning in ADR-0006):
- **Contracts isolated in a pure-JVM module** means neither Production nor
  future From-Scratch engines can leak Android types into the pipeline
  boundary — the substitution requirement is enforced by the dependency
  graph, not by convention.
- **Pure-JVM core modules run their tests on the host JVM** — fast, no
  emulator, CI-friendly.
- `engine-fromscratch/` and `benchmark/` are *not* created yet (Milestones
  5–6): empty modules are premature abstraction. The contracts they will
  implement already exist, which is the part that must not drift.

Image geometry note: `core-contracts` defines geometry as plain data
(`RotationDegrees`, normalized `CropRect`). Applying it to pixels is an
engine concern (`PageTransformer`), so the contract stays Android-free.

## 2. Language

Kotlin only for M1 (app + engines + core). No JNI/FFI yet — nothing in M1
needs native performance, and `docs/architecture.md` requires mixed-language
designs to justify their debugging/build cost. The CV milestone (M2) will
re-open that question with benchmarks.

## 3. UI / capture approach

- Single-activity Jetpack Compose app, Navigation Compose.
- Screens: **SessionList → Capture → PageList → PageEditor → Export**.
- Capture: CameraX `ImageCapture`, shutter → JPEG written directly into the
  session directory via `PageStore`, thumbnail row confirms order; the
  capture loop never leaves the screen (minimize taps per page).
- Import: Android Photo Picker (multi-select, no storage permission).
- Crop/rotate are **non-destructive**: the original capture file is kept;
  edits are stored as geometry in the manifest and applied at
  thumbnail/preview/export time. A failed or abandoned edit can never
  destroy a page (requirements: "failed OCR/processing must not destroy the
  scanned page" — same principle applied earlier in the pipeline).

## 4. Persistence

- App-private storage: `filesDir/sessions/<sessionId>/`
  - `manifest.json` — session metadata + ordered page entries (id, source
    file, rotation, crop, created-at). Written atomically (temp + rename).
  - `pages/<pageId>.jpg` — original captures/imports, untouched.
- Resume = list session dirs, read manifests. Crash mid-capture loses at
  most the not-yet-committed page, never the manifest.
- No database in M1 (ADR-0005); `ScanRepository` isolates the future swap.

## 5. PDF strategy

- Contract (as implemented): `PdfExporter.export(pages: List<ExportPage>, output: OutputStream, onProgress)`.
  Streaming is a documented obligation of implementations — at most one page's
  pixel data resident at a time — rather than a lazy sequence in the signature;
  the page *list* is metadata (file + geometry) and is small even for a book.
- M1 Production implementation: **in-house `PdfImageDocumentWriter`**, which
  embeds baseline JPEG bytes verbatim as `/DCTDecode`. The platform
  `android.graphics.pdf.PdfDocument` was the original selection; it was
  superseded before it could be measured — see
  [ADR-0007](adr/0007-pdf-export-jpeg-passthrough.md).
- **Acceptance gate (M1-04)**: exported size ≤ 1.5× the sum of source JPEGs.
  Measured: **1.0008** (writer) and **1.0016** (end-to-end). Recorded in
  `docs/benchmark.md`.
- Storage invariant (`PageImageNormalizer`): committed page files are baseline
  JPEG with orientation held in geometry, which is what makes passthrough the
  normal path rather than a special case.
- Output via Storage Access Framework (`CreateDocument`) so the PDF lands in
  user-chosen storage; also shareable via `FileProvider`.

## 6. Test strategy

| Layer | How | Runs in CI |
|---|---|---|
| core-contracts (model invariants, geometry math) | JVM unit tests | yes |
| core-session (manifest round-trip, atomicity, reorder/delete, resume, corrupt-manifest recovery) | JVM unit tests, temp dirs | yes |
| pdf-writer (PDF validity, page order, /Rotate, colour space, size gate, 120-page document) | JVM unit tests, asserted **through Apache PDFBox** | yes |
| engine-production (passthrough vs re-encode routing, normalizer invariant, EXIF→geometry, progress, cancellation) | Robolectric JVM tests, `@GraphicsMode(NATIVE)` | yes |
| app UI flows (capture→export happy path) | Compose UI tests | Robolectric where possible; on-device otherwise |
| 100+ page smoke (roadmap M1) | generated pages through the real pipeline | yes for export; on-device for capture |

Two deliberate choices here:
- PDF validity is asserted by an **independent implementation** (PDFBox,
  test-only). A hand-written PDF that only our own reader accepts would prove
  nothing about real readers.
- There is **no emulator job**. The build hosts have no `/dev/kvm`, so an
  emulator would be unavailable or unusably slow; every M1 acceptance gate was
  therefore designed to be measurable as a JVM test. Capture (CameraX) is the
  one thing that genuinely needs hardware, and it is verified on a device.
  `docs/benchmark.md` states plainly what remains unmeasured.

## 7. Dependencies (M1, complete list)

| Dependency | License | Role |
|---|---|---|
| Kotlin stdlib, kotlinx-coroutines | Apache-2.0 | language/async |
| kotlinx-serialization-json | Apache-2.0 | manifest |
| AndroidX core/lifecycle/activity/navigation | Apache-2.0 | app plumbing |
| Jetpack Compose (BOM) + Material 3 | Apache-2.0 | UI |
| CameraX (core/camera2/lifecycle/view) | Apache-2.0 | capture |
| Coil 3 | Apache-2.0 | thumbnails |
| JUnit4, kotlin-test, androidx-test, Robolectric | EPL/Apache/MIT | tests |
| Apache PDFBox (**test only**, never shipped) | Apache-2.0 | independent verification of our PDF output |

All Apache-2.0-compatible; no license gate issues (repo license: Apache-2.0,
ADR-0001). No network-touching dependency is granted the INTERNET permission
path — the app manifest declares no INTERNET permission at all, which makes
the offline/privacy requirement verifiable rather than aspirational.

## 8. What this deliberately does not build yet

- Automatic page detection / perspective correction (M2)
- OCR, searchable PDF layer (M3)
- Spread split, curvature, shadow (M4)
- From-Scratch engines and benchmark harness (M5–7) — but their contracts
  and the per-stage `EngineId` selection point exist from day one.
