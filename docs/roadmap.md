# Roadmap

> Status as of 2026-08-11: Milestones 0 and 1 are complete. Every checked box
> below is backed by a test or a document in `docs/`; see
> [benchmark.md](benchmark.md) for the measured acceptance gates and for what
> deliberately remains unmeasured.

## Milestone 0 — Repository Foundation
- [x] Decide repository license
- [x] Create contribution guidelines
- [x] Establish project/module boundaries
- [x] Establish dependency/license review process
- [x] Define benchmark dataset policy
- [x] Add CI for build + tests (JVM only — no emulator is available, ADR-0007)
- [x] Create initial ADR template

## Milestone 1 — Usable Scanner MVP
- [x] Android capture/import
- [x] Scan session
- [x] Page thumbnails
- [x] Reorder/delete
- [x] Manual crop
- [x] Rotate
- [x] PDF export
- [x] Local persistence
- [x] Recover interrupted session
- [x] Smoke test with a 100+ page scan (120 pages, `BookScaleSmokeTest`)

## Milestone 2 — Production Image Pipeline
- [x] Evaluate page-detection technologies
- [x] Implement automatic page detection (in-house, `:vision`; measured in CI)
- [x] Perspective correction (platform `Matrix.setPolyToPoly`, no CV dependency)
- [ ] image enhancement
- [ ] quality presets
- [ ] benchmark pipeline

## Milestone 3 — OCR + Searchable PDF
- [ ] Evaluate OCR candidates
- [ ] Japanese benchmark
- [ ] integrate selected Production OCR
- [ ] searchable PDF layer
- [ ] OCR fallback/error UX

## Milestone 4 — Book Features
- [ ] spread detection
- [ ] split left/right pages
- [ ] gutter handling
- [ ] curvature correction experiment
- [ ] shadow correction experiment
- [ ] finger/occlusion experiment

## Milestone 5 — From-Scratch Image Core

> Partly delivered early: ADR-0008 made the in-house image core the Production
> detector, because a CV dependency could not be executed in this project's CI.
> The same inversion ADR-0007 recorded for the PDF writer.
- [x] image buffer abstraction
- [x] grayscale
- [x] convolution
- [x] blur
- [x] Sobel
- [x] Canny-class detector
- [ ] morphology
- [ ] connected components
- [ ] line/contour detection
- [x] homography
- [x] perspective warp
- [x] custom page detector
- [ ] side-by-side benchmark vs Production

## Milestone 6 — From-Scratch PDF
- [ ] minimal PDF
- [ ] image pages
- [ ] xref
- [ ] page tree
- [ ] metadata
- [ ] text-layer experiment

## Milestone 7 — From-Scratch OCR
- [ ] scope OCR experiment
- [ ] text-region detection
- [ ] segmentation
- [ ] baseline recognizer
- [ ] evaluate whether custom model/runtime is rational
- [ ] publish results even if inferior to Production

## Milestone 8 — Document Understanding
- [ ] text blocks
- [ ] figures
- [ ] captions
- [ ] tables
- [ ] reading order
- [ ] structured export experiment
