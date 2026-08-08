# Book Scanner OSS — Project Brief

An open-source Android book-scanning application for digitizing personally owned paper books into readable, optionally searchable PDFs.

## Why

Typical document scanners are optimized for loose sheets. This project is specifically interested in book scanning:

- repeated multi-page capture
- book/page boundary detection
- perspective correction
- page curvature
- page splitting
- shadows near the gutter
- OCR
- diagrams and figures
- searchable PDF generation

The application is intended primarily for private use by the person scanning their own books.

## Distinguishing Feature

The repository contains two implementation strategies:

### Production

Use the best practical libraries/platform capabilities after evaluating quality, maintenance, ecosystem, popularity, performance, licensing, and Android integration.

### From Scratch

Reimplement important algorithms in-house for learning and benchmarking.

Both should expose compatible interfaces so the same input can be processed and compared.

## Design Philosophy

Technology-neutral by default.

The project must not choose a technology solely because it is conventional for Android or document scanning.

General conventions are valid candidates, but important selections should be justified against measurable requirements.

## Initial MVP

The first genuinely useful release should support:

- capture or import page images
- maintain a book/page session
- crop/rotate pages
- reorder/delete pages
- export one PDF
- reopen/continue a session
- local/offline operation

Automatic page processing and OCR are subsequent layers.

## Long-Term Direction

```text
Physical Book
    ↓
Capture
    ↓
Page Detection
    ↓
Geometry / Enhancement
    ↓
OCR + Document Analysis
    ↓
Document Model
    ↓
Searchable PDF
    ↓
Personal Digital Library
```

Possible later outputs include EPUB, Markdown, figure extraction, table extraction, and AI-friendly structured documents.

See `AGENTS.md` before implementation.
