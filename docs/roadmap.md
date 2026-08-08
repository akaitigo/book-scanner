# Roadmap

## Milestone 0 — Repository Foundation
- [ ] Decide repository license
- [ ] Create contribution guidelines
- [ ] Establish project/module boundaries
- [ ] Establish dependency/license review process
- [ ] Define benchmark dataset policy
- [ ] Add CI for build + tests
- [ ] Create initial ADR template

## Milestone 1 — Usable Scanner MVP
- [ ] Android capture/import
- [ ] Scan session
- [ ] Page thumbnails
- [ ] Reorder/delete
- [ ] Manual crop
- [ ] Rotate
- [ ] PDF export
- [ ] Local persistence
- [ ] Recover interrupted session
- [ ] Smoke test with a 100+ page scan

## Milestone 2 — Production Image Pipeline
- [ ] Evaluate page-detection technologies
- [ ] Implement automatic page detection
- [ ] Perspective correction
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
- [ ] image buffer abstraction
- [ ] grayscale
- [ ] convolution
- [ ] blur
- [ ] Sobel
- [ ] Canny-class detector
- [ ] morphology
- [ ] connected components
- [ ] line/contour detection
- [ ] homography
- [ ] perspective warp
- [ ] custom page detector
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
