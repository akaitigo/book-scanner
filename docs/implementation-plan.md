# Deliverable D — Initial Implementation Plan (Milestone 1)

> **Status: complete (2026-08-11).** Two items shipped differently from the
> plan below, and the descriptions have been corrected rather than left to
> mislead:
> - **M1-04** planned `PdfDocumentExporter` on the platform PDF API. That API
>   turned out to be unavailable in this project's test environment and was
>   superseded before it could be measured — see
>   [ADR-0007](adr/0007-pdf-export-jpeg-passthrough.md). The exporter is
>   `JpegPdfExporter` over an in-house `:pdf-writer` module.
> - **Instrumented/emulator tests** are not used anywhere. The build hosts have
>   no `/dev/kvm`, so every acceptance gate was designed to be measurable as a
>   JVM test instead.

Small executable issues, in dependency order. Each issue is one reviewable
change. "AC" = acceptance criteria.

Milestone 0 items (license, ADR process, CI) are folded into M1-01/M1-02
since they gate everything else; the remaining M0 items (CONTRIBUTING,
dataset policy) ship with M1-11.

---

### M1-01 — Repository foundation: license, scaffold, CI
- **Objective**: buildable multi-module Gradle project + CI.
- **Files**: `LICENSE`, `settings.gradle.kts`, `build.gradle.kts`,
  `gradle/libs.versions.toml`, `gradle/wrapper/*`, module skeletons
  (`app/`, `core-contracts/`, `core-session/`, `engine-production/`),
  `.github/workflows/ci.yml`.
- **AC**: `./gradlew build` succeeds locally; CI runs ktlint + assemble + JVM
  unit tests on push/PR (free tier, no emulator); LICENSE = Apache-2.0. Java 17
  toolchain declared so local and CI compile identically.
- **Tests**: placeholder unit test per JVM module proves the test toolchain.

### M1-02 — Domain model + engine contracts (`core-contracts`)
- **Objective**: the stable pipeline boundary.
- **Files**: `core-contracts/src/main/kotlin/...` — `ScanSession`,
  `ScannedPage`, `PageGeometry` (rotation + normalized crop), `EngineId`,
  `ScanRepository`, `PageStore`, `PdfExporter`, `PageTransformer`,
  progress/result types.
- **AC**: pure JVM (no Android imports — enforced by the module having no
  Android plugin); geometry operations (rotation composition, crop
  normalization/clamping) fully specified.
- **Tests**: geometry math (rotate 4×90°=identity, crop clamp edge cases),
  model invariants (page order uniqueness).

### M1-03 — File-based session repository (`core-session`)
- **Objective**: sessions survive process death; page files + atomic manifest.
- **Files**: `core-session/src/main/kotlin/...` — `FileScanRepository`,
  `ManifestCodec`, `AtomicFile` write (temp + rename), session/page CRUD,
  reorder, soft-ordering by manifest list.
- **AC**: create/list/open/delete sessions; add/remove/reorder pages;
  manifest never observed half-written (rename-based commit); unknown JSON
  fields tolerated (forward compat); corrupt manifest → session flagged
  recoverable, pages re-indexed from files.
- **Tests**: JVM, temp dirs — round-trip, reorder persistence, delete keeps
  other pages, corrupt/truncated manifest recovery, 500-page manifest perf
  sanity (<100 ms parse).

### M1-04 — Production PDF exporter + size gate (`pdf-writer`, `engine-production`)
- **Objective**: streaming image-PDF export; measured file-size verdict.
- **Shipped as**: `pdf-writer/` (`PdfImageDocumentWriter`, JPEG header parsing)
  + `engine-production` (`JpegPdfExporter`), with baseline JPEG embedded
  verbatim as `/DCTDecode`. Verified against Apache PDFBox, test-only.
- **AC**: valid PDF (header `%PDF-`, correct page count, opens in common
  readers); ≤1 full-res bitmap in memory at a time; progress callback per
  page; **size gate measured**: output ≤1.5× sum of source JPEGs on the
  reference set — result recorded in `docs/benchmark.md` results section.
  **Result**: 1.0008 (writer) / 1.0016 (end-to-end) — the fallback was adopted
  because the platform exporter proved unmeasurable, not because it lost.
- **Tests**: JVM. `:pdf-writer` asserts structure, order, `/Rotate`, colour
  space and size through PDFBox; `:engine-production` asserts passthrough vs
  re-encode routing and that cancellation leaves no complete document; the
  ViewModel asserts the partially written file is deleted on cancel or failure.

### M1-05 — Page transformer: non-destructive crop/rotate (`engine-production`)
- **Objective**: apply `PageGeometry` to pixels for preview/export.
- **Files**: `BitmapPageTransformer` (sampled decode + matrix), sampling for
  preview vs full-res for export.
- **AC**: originals never modified; EXIF orientation honored; downsampled
  decode for previews (no full-res bitmap for thumbnails).
- **Tests**: JVM under Robolectric with native graphics — quadrant bitmaps for
  rotate/crop, EXIF orientation folded into geometry rather than pixels.

### M1-06 — App shell + session list (`app`)
- **Objective**: navigable app; create/open/delete sessions.
- **Files**: `MainActivity`, navigation graph, `SessionListScreen` + VM,
  composition root wiring `FileScanRepository`.
- **AC**: create session → lands in capture; sessions listed with page
  count/date; delete with confirmation; state restored after process death.
- **Tests**: VM unit tests (JVM, repository faked via contract); Compose UI
  test for list/create/delete.

### M1-07 — Capture screen (CameraX) + import (`app`)
- **Objective**: the fast capture loop.
- **Files**: `CaptureScreen` + VM, CameraX setup, permission flow, Photo
  Picker import.
- **AC**: shutter → JPEG in session dir + thumbnail appears; continuous
  captures without leaving screen; capture order preserved; import
  multi-select appends in picker order; camera permission denial →
  functional import-only fallback UI; no INTERNET permission in merged
  manifest (assert in test).
- **Tests**: VM unit tests (capture result handling, ordering); manifest
  merger assertion; UI test with fake `PageStore`.

### M1-08 — Page list: thumbnails, reorder, delete (`app`)
- **Objective**: see and manage page order clearly.
- **Files**: `PageListScreen` + VM, Coil thumbnails, drag-to-reorder,
  multi-select delete.
- **AC**: thumbnails lazy-load (no full-res decode); drag reorder persists
  via repository; delete confirms; empty state guides to capture.
- **Tests**: VM reorder/delete logic (JVM); UI test drag reorder persisted
  after "process death" (recreate VM from repository).

### M1-09 — Page editor: manual crop + rotate (`app`)
- **Objective**: manual correction when needed.
- **Files**: `PageEditorScreen` + VM, crop handles overlay, rotate button.
- **AC**: crop rect draggable with visual feedback; rotate in 90° steps;
  save persists geometry only (original untouched); cancel discards.
- **Tests**: VM geometry state tests (JVM); UI test save/cancel round-trip.

### M1-10 — Export flow (`app`)
- **Objective**: session → single PDF in user-chosen location.
- **Files**: `ExportScreen`/dialog + VM, SAF `CreateDocument`, progress UI,
  share action.
- **AC**: export shows per-page progress; result readable in an external
  reader; export of 0-page session blocked with message; failure surfaces
  error without corrupting session; cancel cleans up partial output.
- **Tests**: VM progress/error tests with a fake exporter and a fake
  `ExportTarget` (JVM). The target records deletion, because SAF creates the
  document the moment a name is chosen: a cancelled export that skipped cleanup
  would leave a truncated PDF in the user's Downloads.

### M1-11 — Book-scale smoke + docs closeout
- **Objective**: prove book-scale usability; close Milestone 0/1 docs.
- **Files**: `BookScaleSmokeTest` (120 pages through the real pipeline),
  `CONTRIBUTING.md` (incl. the benchmark dataset policy and the dependency /
  licence review process), README status update, `docs/privacy.md`,
  `docs/pdf.md`, roadmap checkboxes.
- **AC**: 120 pages survive capture → repository restart → reorder → edit →
  export, with the PDF overhead within budget and results recorded in
  `docs/benchmark.md`. AGENTS.md §13's doc list satisfied for M1 scope —
  `ocr.md` and `image-processing-pipeline.md` stay unwritten until M2–M3
  give them content, per `assessment.md`.
- **Tests**: `BookScaleSmokeTest` and `PrivacyManifestTest`. Device-only
  checks (per-page latency, peak memory, predictive back, 200% text) are
  listed as unmeasured in `docs/benchmark.md` rather than claimed.

---

## Sequencing

```text
M1-01 → M1-02 → M1-03 → M1-06 → M1-07 → M1-08 → M1-09 → M1-10 → M1-11
                └→ M1-04, M1-05 (parallel to app work after M1-02)
```
