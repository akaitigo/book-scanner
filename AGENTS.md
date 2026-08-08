# AGENTS.md — Book Scanner OSS

## 1. Mission

Build an open-source Android application for scanning personally owned paper books into portable, readable PDFs for private use.

The project is not merely a "camera-to-PDF" utility. It should become a technically rigorous book digitization system with two interchangeable implementation tracks:

1. **Production implementation**
   - Uses mature third-party libraries or platform APIs when they are the most rational choice.
   - Prioritizes scan quality, OCR accuracy, performance, maintainability, and usability.

2. **From-scratch implementation**
   - Reimplements important image-processing, OCR, and PDF-generation algorithms where practical.
   - Exists for education, benchmarking, algorithmic understanding, and architectural verification.
   - Must not compromise the Production implementation.

Both implementations should conform to common interfaces and be benchmarkable on the same datasets.

---

## 2. Core Product Goal

A user should be able to:

1. Open a physical book.
2. Capture pages quickly using an Android device.
3. Automatically or manually correct page geometry.
4. Preserve text, illustrations, diagrams, photographs, and page layout.
5. Produce a PDF suitable for reading outside the home.
6. Optionally produce a searchable PDF with an OCR text layer.
7. Keep processing local/offline by default.
8. Compare Production and From-Scratch processing engines.

The primary user is the owner of the device and the books being scanned.

---

## 3. Critical Design Principle: Technology-Neutral by Default

Do **not** select Kotlin, C++, Rust, OpenCV, ML Kit, Tesseract, ONNX Runtime, a PDF library, or any other technology merely because it is conventional.

Technology selection must be justified against the project requirements.

Evaluate at least:

- functional suitability
- scan/OCR quality
- runtime performance
- memory consumption
- battery impact
- binary size
- Android integration cost
- maintainability
- ecosystem maturity
- popularity/adoption
- community size
- maintenance activity and continuity risk
- quality of documentation
- debugging/tooling support
- licensing compatibility with the OSS project
- portability and migration cost
- implementation complexity
- long-term talent/knowledge availability

Popularity is not sufficient reason to choose a technology, but it is a valid proxy for ecosystem health, information availability, maintenance continuity, and future maintainability.

A less popular technology should only be preferred when its advantages materially outweigh those risks.

Document non-trivial decisions as ADRs.

---

## 4. Do Not Overfit to General Best Practices

General best practices are hypotheses, not requirements.

Before adopting a conventional architecture or technology:
1. identify the actual requirement it solves;
2. compare reasonable alternatives;
3. determine whether the convention is optimal under this project's constraints;
4. record important trade-offs.

Avoid statements such as:
- "Android means Kotlin."
- "Computer vision means OpenCV."
- "OCR means ML Kit."
- "PDF means library X."

They may become the correct answer after evaluation, but must not be assumed.

---

## 5. Product Priorities

Priority order:

1. Produce a genuinely usable book-scanning application.
2. Preserve book content faithfully.
3. Make repeated multi-page capture efficient.
4. Keep architecture modular and engine-swappable.
5. Enable searchable PDFs.
6. Provide reproducible benchmarks.
7. Expand the From-Scratch implementation.
8. Explore higher-level document understanding.

The Production implementation MUST remain usable even if From-Scratch work is incomplete.

---

## 6. Mandatory Architectural Separation

At minimum, keep these concepts behind stable interfaces:

- capture
- page detection
- image preprocessing
- geometric correction
- page splitting
- image enhancement
- OCR
- layout/document analysis
- PDF export
- storage
- benchmarking

Conceptual example only:

```text
BookScannerApp
    |
    +-- CaptureEngine
    +-- PageDetector
    +-- PageCorrector
    +-- OcrEngine
    +-- DocumentAnalyzer
    +-- PdfExporter
```

The exact module layout is for the implementation agent to propose and justify.

---

## 7. Engine Model

Support at least these engine families:

```text
Production
FromScratch
```

The user should eventually be able to select the processing engine from a developer/advanced setting.

Where useful, allow per-stage substitution rather than requiring the entire pipeline to use one engine.

Example:

```text
Page detection     -> FromScratch
Perspective warp   -> Production
OCR                -> Production
PDF                -> FromScratch
```

This enables isolated benchmarking.

---

## 8. Work Order

Do not begin with the hardest research features.

Recommended development order:

### Stage 0 — Architecture and evaluation
- requirements confirmation
- representative test dataset definition
- technology candidate matrix
- license review
- module/interface design
- benchmark methodology

### Stage 1 — Usable MVP
- camera capture/import
- page list
- manual crop/rotation
- page reorder/delete
- PDF export
- local storage
- basic error handling

### Stage 2 — Production scan quality
- automatic page boundary detection
- perspective correction
- contrast/illumination adjustment
- batch/continuous capture
- book-oriented UX

### Stage 3 — OCR
- on-device OCR
- Japanese support
- searchable PDF text layer
- OCR result inspection
- benchmark accuracy

### Stage 4 — Book-specific processing
- spread detection
- left/right page splitting
- page curvature correction
- shadow reduction
- finger/object removal where feasible

### Stage 5 — From-Scratch CV
- image primitives
- convolution
- grayscale
- blur
- thresholding
- Sobel/Canny-class edge detection
- morphology
- connected components
- contours/line detection
- homography
- perspective warp
- page detector

### Stage 6 — From-Scratch PDF
- minimal valid PDF generation
- page tree
- image embedding
- content streams
- xref handling
- text-layer support

### Stage 7 — From-Scratch OCR research
Start simple. Do not attempt to reproduce a modern full Japanese OCR stack immediately.

Possible progression:
- segmentation
- line/text-region detection
- connected components
- simple classifier experiments
- learned model experiments
- inference runtime experiments only if justified

### Stage 8 — Document Understanding
Optional:
- text blocks
- figures
- captions
- tables
- page numbers
- reading order
- Markdown/EPUB export
- downstream AI-friendly structure

---

## 9. Scope Boundary for "From Scratch"

"From scratch" does not automatically mean reimplementing the operating system.

A sensible initial boundary is:

```text
Android camera / OS image decoder
        |
        v
Raw or decoded pixel buffer
==============================
From-scratch algorithms may begin here
==============================
        |
        v
Image processing / OCR / PDF
```

JPEG/PNG codec implementations may be added later as experiments, but should not block product development.

Every From-Scratch component must clearly document what lower-level facilities it still depends on.

---

## 10. Benchmarking Requirement

Benchmarks are a first-class project feature.

Compare implementations on identical inputs.

Track where applicable:

- page detection success rate
- corner error / geometric error
- OCR CER/WER
- Japanese OCR quality
- vertical text handling
- ruby/furigana handling
- diagram preservation
- processing latency per page
- throughput
- peak memory
- CPU utilization
- battery/energy proxy
- binary/APK size contribution
- crash/failure rate

Store benchmark methodology and reproducible inputs or input-generation instructions in the repository.

Do not make performance claims without measurements.

---

## 11. Privacy and Network Policy

Default target: processing should work locally/on-device.

The app should not require uploading scanned book pages to a remote service for core functionality.

If an optional network-backed engine is ever introduced:
- it must be opt-in;
- the UI must state that pages leave the device;
- the local pipeline must remain available;
- privacy implications must be documented.

---

## 12. OSS and Licensing

Before adding a dependency:
- verify its current license;
- verify redistribution compatibility;
- verify model weights separately from runtime/library licenses;
- document material dependencies.

Avoid introducing dependencies whose license would create unexpected obligations for the repository.

Do not copy code from incompatible sources.

---

## 13. Portfolio Quality

This repository should demonstrate engineering judgement, not merely feature count.

Maintain:

```text
README.md
docs/
  requirements.md
  architecture.md
  image-processing-pipeline.md
  ocr.md
  pdf.md
  benchmark.md
  privacy.md
  technology-selection.md
  adr/
```

Important choices should answer:
- What problem were we solving?
- What alternatives were considered?
- What evidence was collected?
- Why was this choice made?
- What would cause us to revisit it?

---

## 14. Agent Behaviour

When implementing:

- inspect existing repository state before changing architecture;
- do not perform large rewrites without evidence;
- add tests with new core algorithms;
- benchmark before "optimizing";
- keep Production and From-Scratch code separable;
- avoid premature abstraction, but protect engine boundaries;
- prefer incremental working commits;
- update docs when design decisions change;
- state assumptions explicitly;
- leave TODOs only when their reason and intended follow-up are clear.

When uncertain about a technical selection, prepare a short comparison and choose based on the evaluation criteria rather than asking the user to pick among arbitrary technologies.
