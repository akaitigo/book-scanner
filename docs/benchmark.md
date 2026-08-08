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
