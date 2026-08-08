# Architecture Direction

This document specifies boundaries, not a mandatory framework.

## Pipeline

```text
Capture / Import
      ↓
Decode / Pixel Access
      ↓
Page Detection
      ↓
Geometry Correction
      ↓
Image Enhancement
      ↓
OCR
      ↓
Document Analysis
      ↓
Page Model
      ↓
PDF Export
      ↓
Storage
```

## Suggested Domain Objects

Conceptual only:

```text
BookScanSession
ScannedPage
PageImage
PageBoundary
PerspectiveTransform
OcrResult
TextBlock
FigureBlock
TableBlock
DocumentPage
ExportRequest
BenchmarkResult
```

## Interface Candidates

```text
CaptureEngine
PageDetector
PageCorrector
ImageEnhancer
OcrEngine
DocumentAnalyzer
PdfExporter
ScanRepository
BenchmarkRunner
```

Use stable interfaces where implementation substitution is a real requirement.

Do not introduce an interface only because "clean architecture" says so.

## Production vs From-Scratch

Possible arrangement:

```text
core-contracts/
production/
from-scratch/
benchmark/
app/
```

or:

```text
features/
engines/
  production/
  from-scratch/
```

The implementation agent should pick the actual build/module structure after considering:
- Android build performance
- JNI/FFI needs
- testability
- language interoperability
- dependency isolation
- release complexity

## Language Boundary

Language is an implementation detail.

Possible outcome:

```text
Android/UI       Kotlin
CV               C++ / Rust / Kotlin
OCR              runtime/model dependent
PDF              JVM/native/custom
Benchmarks       whichever tool is appropriate
```

But this is NOT a prescribed stack.

Any mixed-language design must justify:
- FFI overhead
- debugging complexity
- build complexity
- portability
- memory ownership model
- crash isolation
