# Deliverable D — Initial Implementation Plan (Milestone 1)

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
- **AC**: `./gradlew build` succeeds locally; CI runs assemble + JVM unit
  tests on push/PR (free tier, no emulator); LICENSE = Apache-2.0.
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

### M1-04 — Production PDF exporter + size gate (`engine-production`)
- **Objective**: streaming image-PDF export; measured file-size verdict.
- **Files**: `engine-production/src/main/kotlin/...` — `PdfDocumentExporter`
  implementing `PdfExporter`; test-only minimal PDF reader.
- **AC**: valid PDF (header `%PDF-`, correct page count, opens in common
  readers); ≤1 full-res bitmap in memory at a time; progress callback per
  page; **size gate measured**: output ≤1.5× sum of source JPEGs on the
  reference set — result recorded in `docs/benchmark.md` results section.
  If the gate fails → open issue to implement the fallback minimal
  JPEG-passthrough writer (ADR-0004) before M1-09 merges.
- **Tests**: instrumented — export 3 known JPEGs, parse structure, assert
  page count/order and size ratio; cancellation mid-export leaves no
  partial file at the destination.

### M1-05 — Page transformer: non-destructive crop/rotate (`engine-production`)
- **Objective**: apply `PageGeometry` to pixels for preview/export.
- **Files**: `BitmapPageTransformer` (region decode + matrix), sampling for
  preview vs full-res for export.
- **AC**: originals never modified; EXIF orientation honored; downsampled
  decode for previews (no full-res bitmap for thumbnails).
- **Tests**: instrumented — synthetic bitmap with colored quadrants: rotate
  90° and crop each quadrant, assert pixels; EXIF-rotated JPEG handled.

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
- **Tests**: VM progress/error tests with fake exporter (JVM); instrumented
  happy path.

### M1-11 — Book-scale smoke + docs closeout
- **Objective**: prove book-scale usability; close Milestone 0/1 docs.
- **Files**: instrumented smoke test (generate 120 pages → session →
  reorder → export), `CONTRIBUTING.md`, benchmark dataset policy note,
  README status update.
- **AC**: 120-page export completes without OOM on a 2 GB-heap-class
  emulator; peak memory recorded in `docs/benchmark.md` results; docs list
  in AGENTS.md §13 satisfied for M1 scope.
- **Tests**: the smoke test itself; CI emulator job wired (on-demand).

---

## Sequencing

```text
M1-01 → M1-02 → M1-03 → M1-06 → M1-07 → M1-08 → M1-09 → M1-10 → M1-11
                └→ M1-04, M1-05 (parallel to app work after M1-02)
```
