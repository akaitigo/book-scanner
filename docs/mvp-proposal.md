# Deliverable B — MVP Technical Proposal (Milestone 1)

Goal: the smallest architecture that delivers a genuinely usable scanner
(capture/import → session → crop/rotate/reorder/delete → PDF export → resume)
while keeping every future Production/From-Scratch substitution point behind
a stable contract.

Selections below are justified in `docs/technology-candidates.md` (Deliverable
C) and the ADRs (Deliverable E). This document describes the resulting shape.

## 1. Build / module structure

Gradle (Kotlin DSL, version catalog), four modules:

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
  engine-production/    Android library: Production engine implementations
                        for M1 (PdfDocumentExporter, BitmapPageTransformer
                        for crop/rotate). Depends on core-contracts.
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

- Contract: `PdfExporter.export(pages: Sequence<RenderedPage>, out: OutputStream, onProgress)` —
  streaming by design; at most one full-resolution bitmap in memory.
- M1 Production implementation: platform `android.graphics.pdf.PdfDocument`.
- **Acceptance gate (M1-04)**: exported size ≤ 1.5× the sum of source JPEGs
  on the reference set. If the platform re-encoding fails this gate, the
  designated fallback is a custom minimal JPEG-passthrough writer (which
  then seeds the Milestone 6 From-Scratch track) — not the unmaintained
  PdfBox-Android (evidence in Deliverable C).
- Output via Storage Access Framework (`CreateDocument`) so the PDF lands in
  user-chosen storage; also shareable via `FileProvider`.

## 6. Test strategy

| Layer | How | Runs in CI |
|---|---|---|
| core-contracts (model invariants, geometry math) | JVM unit tests | yes |
| core-session (manifest round-trip, atomicity, reorder/delete, resume, corrupt-manifest recovery) | JVM unit tests, temp dirs | yes |
| engine-production (PDF validity: header/xref/page count; size gate; crop/rotate pixel checks) | instrumented tests | emulator job (nightly / on-demand initially) |
| app UI flows (capture→export happy path) | Compose UI tests, instrumented | emulator job |
| 100+ page smoke (roadmap M1) | scripted instrumented test with generated pages | on-demand |

PDF validity checks parse the output structurally (header, page count via a
minimal reader in test code) rather than eyeballing.

## 7. Dependencies (M1, complete list)

| Dependency | License | Role |
|---|---|---|
| Kotlin stdlib, kotlinx-coroutines | Apache-2.0 | language/async |
| kotlinx-serialization-json | Apache-2.0 | manifest |
| AndroidX core/lifecycle/activity/navigation | Apache-2.0 | app plumbing |
| Jetpack Compose (BOM) + Material 3 | Apache-2.0 | UI |
| CameraX (core/camera2/lifecycle/view) | Apache-2.0 | capture |
| Coil 3 | Apache-2.0 | thumbnails |
| JUnit4, kotlin-test, androidx-test, Robolectric (if needed) | EPL/Apache/MIT | tests |

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
