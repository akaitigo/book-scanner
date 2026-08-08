# ADR-0004: MVP PDF export — platform PdfDocument behind PdfExporter, with measured size gate

## Status
Accepted (2026-08-08) — contains an explicit measurement gate (M1-04)

## Context
Milestone 1 needs image-only PDF export: valid, ordered, readable
everywhere, streaming (never all pages in memory). Milestone 3 adds a
searchable text layer; Milestone 6 builds a From-Scratch writer. The
Production choice must not be an unmaintained dependency and must not leak
past the `PdfExporter` contract.

## Requirements
- Valid PDF for common Android/iOS/desktop readers; page order preserved.
- ≤1 full-resolution bitmap in memory during export (book-scale sessions).
- Output size reasonable relative to source JPEGs.
- License gate: Apache-2.0-compatible.
- Maintenance continuity.

## Alternatives
Full matrix + live evidence: `docs/technology-candidates.md` §3.
- **PdfBox-Android**: functionally richest (would also cover the M3 text
  layer), Apache-2.0 — but last release 2023-01, last push 2024-03
  (queried 2026-08-08). Adopting a two-years-dormant library as the core
  exporter is a continuity risk AGENTS.md §3 explicitly weighs against.
- **OpenPDF**: actively maintained but `java.awt`-dependent; not
  Android-compatible. Rejected.
- **iText 7+**: AGPL — license gate failure (ADR-0001).
- **Custom minimal writer** (JPEG DCTDecode passthrough): small, pure
  Kotlin, JVM-testable, converges with Milestone 6 — but writing our own
  before proving the zero-cost platform option inadequate would invert the
  Production principle (mature/platform first).

## Evidence
- GitHub metadata queried live 2026-08-08 (see Deliverable C §3).
- Known risk of `PdfDocument`: Skia's PDF backend may re-encode drawn
  bitmaps instead of passing JPEG bytes through, inflating size. This is
  a measurable claim → benchmark, not folklore (AGENTS.md §10: no
  performance claims without measurements).

## Decision
1. Contract: `PdfExporter` consumes a lazy sequence of rendered pages and an
   output stream — streaming is structural, per-engine.
2. M1 Production implementation: `android.graphics.pdf.PdfDocument`
   (platform, zero dependency, Google-maintained).
3. **Gate (blocks M1-10 merge)**: on the reference page set, exported PDF
   size ≤ 1.5× the sum of source JPEG bytes, and export of 120 pages stays
   within memory bounds. Results recorded in `docs/benchmark.md`.
4. If the gate fails: implement the custom minimal JPEG-passthrough writer
   as the Production M1 exporter (it later seeds Milestone 6), and demote
   `PdfDocumentExporter` to a benchmark reference. PdfBox-Android remains a
   last-resort fallback only if the text-layer milestone finds no better
   option.

## Why
The platform API is the only zero-dependency, actively-maintained candidate;
its single known weakness is cheap to measure. Committing to a fallback
*in advance*, with a numeric threshold, prevents both sunk-cost adoption and
speculative custom code.

## Consequences
- M3 searchable-PDF layer cannot use `PdfDocument` (no text-layer control);
  M3 will need its own ADR — candidates: custom writer extension,
  PdfBox-Android (re-evaluated then), or post-processing.
- The test suite includes a minimal structural PDF parser (test-only).

## Revisit When
The M1-04 gate result lands (either way, record it here); or M3 text-layer
work begins.
