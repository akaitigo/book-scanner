# Deliverable C — Technology Candidate Matrix (Milestone 1 scope)

Evaluated per `docs/technology-selection.md`. Scores are 1–5 (higher is
better). License is a gate, not a score. Evidence collected 2026-08-08;
GitHub metadata was queried live (`gh api`) where noted.

Areas evaluated now: application layer, camera, PDF export, persistence,
image loading. CV/OCR remain research tasks for Milestones 2–3 as allowed by
`docs/agent-first-task.md`.

---

## 1. Android application layer

Requirement drivers: camera control quality, future JNI/FFI for CV engines,
on-device OCR integration, long-term maintainability, contributor
availability for an OSS Android project.

| Dimension (weight) | Kotlin + Jetpack Compose | Kotlin + Views | Flutter | React Native | Kotlin Multiplatform + CMP |
|---|---|---|---|---|---|
| Requirement fit (VH) | 5 | 5 | 3 | 2 | 4 |
| Output quality (VH) | 5 | 5 | 4 | 3 | 4 |
| Performance (H) | 4 | 5 | 4 | 3 | 4 |
| Maintainability (VH) | 5 | 3 | 3 | 3 | 4 |
| Ecosystem maturity (H) | 5 | 5 | 4 | 4 | 3 |
| Popularity/adoption (M–H) | 5 | 4 | 4 | 3 | 3 |
| Maintenance continuity (VH) | 5 | 4 | 4 | 3 | 4 |
| Docs/tooling (H) | 5 | 5 | 4 | 3 | 3 |
| Android integration (H) | 5 | 5 | 2 | 2 | 4 |
| License (gate) | Apache-2.0 ✔ | Apache-2.0 ✔ | BSD-3 ✔ | MIT ✔ | Apache-2.0 ✔ |
| Binary size (M) | 4 | 5 | 2 | 2 | 4 |
| Memory (H) | 4 | 5 | 3 | 3 | 4 |
| Migration cost (M) | 4 | 3 | 2 | 2 | 4 |
| Implementation effort (M) | 5 | 3 | 4 | 3 | 3 |

Notes:
- Flutter/RN lose primarily on **camera + native pipeline integration**: the
  capture loop, `ImageProxy`-level control, and future JNI CV engines all sit
  behind a platform channel, adding an FFI hop and debugging complexity that
  `docs/architecture.md` requires us to justify — and nothing here justifies it.
  Cross-platform reach is not a requirement (Android-only project).
- KMP adds structure we may want *if* iOS ever becomes a target; it is not,
  and pure-JVM core modules keep that door open at near-zero cost.
- Views beat Compose only on raw perf/size margins that don't bind for this
  app; Compose wins heavily on maintainability and implementation effort for
  list/reorder/editor UIs.

**Selected: Kotlin + Jetpack Compose, single-activity.** → [ADR-0002](adr/0002-android-app-layer.md)

---

## 2. Camera / capture

Requirement drivers: repeated multi-page capture with minimal taps, capture
order preservation, exposure/focus adequacy for paper pages, effort.

| Dimension (weight) | CameraX (`ImageCapture`) | Camera2 direct | System camera intent | Import-only (Photo Picker) |
|---|---|---|---|---|
| Requirement fit (VH) | 5 | 5 | 2 | 2 |
| Output quality (VH) | 4 | 5 | 4 | n/a |
| Performance (H) | 4 | 5 | 2 | n/a |
| Maintainability (VH) | 5 | 2 | 5 | 5 |
| Ecosystem maturity (H) | 5 | 5 | 5 | 5 |
| Maintenance continuity (VH) | 5 | 5 | 5 | 5 |
| Docs/tooling (H) | 5 | 3 | 4 | 4 |
| Android integration (H) | 5 | 5 | 5 | 5 |
| License (gate) | Apache-2.0 ✔ | platform ✔ | platform ✔ | platform ✔ |
| Implementation effort (M) | 4 | 1 | 5 | 5 |

Notes:
- The system-camera-intent route fails the core UX requirement ("minimize
  taps per page"): each shot round-trips through the external camera app with
  its own confirm step. Rejected for capture; the **Photo Picker is still used
  for the separate image-import requirement** (no storage permission needed).
- Camera2 offers nothing CameraX lacks for still capture of pages, at much
  higher implementation and device-compatibility cost. CameraX wraps Camera2
  and is the escape hatch if device-specific control is ever needed
  (`Camera2Interop`).
- CameraX 1.6.1 verified working in this build environment on AGP 9 / Kotlin 2.4.

**Selected: CameraX `ImageCapture` for capture; Android Photo Picker for import.** → [ADR-0003](adr/0003-camera-capture.md)

---

## 3. PDF export (Milestone 1: image-only PDF)

Requirement drivers: valid PDF readable by common readers, page order,
book-scale memory behavior (stream pages, never all bitmaps in RAM),
reasonable file size for JPEG page images, path toward a searchable text
layer (Milestone 3), license gate.

| Dimension (weight) | Platform `android.graphics.pdf.PdfDocument` | PdfBox-Android | OpenPDF | iText 7+ | Custom minimal writer |
|---|---|---|---|---|---|
| Requirement fit (VH) | 4 | 5 | 1 | 5 | 4 |
| Output quality (VH) | 3–4 (size risk, see note) | 5 | n/a | 5 | 4 |
| Performance (H) | 4 | 3 | n/a | 4 | 5 |
| Maintainability (VH) | 5 | 2 | n/a | 4 | 3 |
| Ecosystem maturity (H) | 5 | 4 | 4 | 5 | 1 |
| Maintenance continuity (VH) | 5 (platform) | **1** | 4 | 5 | 3 (ours) |
| Docs/tooling (H) | 4 | 3 | 3 | 5 | n/a |
| Android integration (H) | 5 | 4 | **1** (java.awt) | 3 | 5 |
| License (gate) | platform ✔ | Apache-2.0 ✔ | LGPL/MPL ✔* | **AGPL ✘** | ours ✔ |
| Binary size (M) | 5 (0 bytes) | 3 | n/a | 2 | 5 |
| Memory (H) | 4 | 3 | n/a | 4 | 5 |
| Implementation effort (M) | 5 | 4 | n/a | 4 | 2 |

Evidence (queried live, 2026-08-08):
- **PdfBox-Android** (TomRoush/PdfBox-Android): Apache-2.0, ~1.2k stars,
  **last release v2.0.27.0 on 2023-01-02, last push 2024-03-18**. Effectively
  unmaintained for over two years → severe continuity risk. Disqualifying as
  a *primary* dependency; acceptable only as a known fallback.
- **OpenPDF** (LibrePDF/OpenPDF): actively maintained (pushed 2026-08), but
  depends on `java.awt` — not Android-compatible without a fork. Rejected.
- **iText**: AGPL-3.0. Gate failure for an Apache-2.0 repository.
- **Platform `PdfDocument`**: maintained by Google as part of AOSP, zero
  dependency, streams one page at a time (`startPage`/`finishPage` +
  `writeTo`). Known risk: Skia's PDF backend may re-encode drawn bitmaps
  rather than passing JPEG bytes through, which can inflate output size.
  This is measurable, so it is treated as a **spike with an acceptance
  gate** (M1-04), not a guess.
- **Custom minimal writer** (image-only PDF with DCTDecode passthrough of
  JPEG bytes): small, pure-Kotlin, JVM-testable, and converges with the
  Milestone 6 From-Scratch track. Held as the designated fallback if the
  platform exporter fails the size gate — preferable to adopting an
  unmaintained library.

**Selected: platform `PdfDocument` behind the `PdfExporter` contract, with a
measured file-size acceptance gate; fallback = custom minimal JPEG-embedding
writer.** → [ADR-0004](adr/0004-pdf-export-mvp.md)

---

## 4. Session persistence

Requirement drivers: resume interrupted sessions, page order integrity,
book-scale page counts, crash safety, testability.

| Dimension (weight) | Files + JSON manifest (kotlinx.serialization) | Room (SQLite) | SQLDelight | DataStore (proto) |
|---|---|---|---|---|
| Requirement fit (VH) | 5 | 5 | 5 | 3 |
| Maintainability (VH) | 5 | 4 | 4 | 4 |
| Maintenance continuity (VH) | 5 | 5 | 4 | 5 |
| Testability (H) | 5 (pure JVM) | 3 (Robolectric/instrumented) | 4 | 3 |
| Migration cost (M) | 4 | 3 | 3 | 3 |
| Implementation effort (M) | 5 | 3 | 3 | 4 |
| License (gate) | Apache-2.0 ✔ | Apache-2.0 ✔ | Apache-2.0 ✔ | Apache-2.0 ✔ |

Notes:
- Page images must live as files regardless (never all in memory). The only
  question is how the *metadata* (session, ordered page list, edit state) is
  stored. A per-session `manifest.json` written atomically (temp file +
  rename) is crash-safe, human-debuggable, pure-JVM testable, and has no
  schema-migration machinery.
- A database earns its complexity when we need cross-session queries
  (library search, OCR text indexing) — that is Milestone 3+, and the
  `ScanRepository` contract isolates the change.

**Selected: filesystem layout + atomic JSON manifest per session.** → [ADR-0005](adr/0005-persistence.md)

---

## 5. Thumbnail/image loading (app UI)

Small decision, recorded here rather than as an ADR.

Candidates: Coil 3 (Apache-2.0, active — v3.5.0 released 2026-06, verified via
`gh api`), Glide (active, BSD-style), hand-rolled decode + LruCache.

**Selected: Coil 3** — Compose-first API, active maintenance, already proven
in this build environment. Glide is equally healthy but its Compose
integration is secondary to its View heritage. Hand-rolling subsampled decode
and caching for a scrolling grid of hundreds of thumbnails is exactly the
kind of undifferentiated work a mature library should own (AGENTS.md §1
Production track).

---

## Not selected now (deferred with reason)

| Area | Status |
|---|---|
| CV library (page detection, warp) | Milestone 2 research task. Candidates: OpenCV, custom Kotlin/NDK, platform APIs. Requires benchmark per `docs/benchmark.md`. |
| OCR engine | Milestone 3 research task. Candidates: ML Kit (unbundled/bundled, Japanese), Tesseract-class, ONNX-runtime models. Japanese + vertical text quality must be benchmarked, not assumed. |
| DI framework | Not adopted. Manual constructor wiring in a ~4-module app; revisit if the object graph grows past what a single composition root handles clearly. |
| Navigation library | Jetpack Navigation Compose (Apache-2.0) — thin usage, standard. |
