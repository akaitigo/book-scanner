# Technology Selection Framework

Do not select a stack before producing an evaluation.

## Required Evaluation Dimensions

Score candidates from 1–5 and add notes/evidence.

| Dimension | Weight guidance |
|---|---:|
| Requirement fit | Very high |
| Output quality / accuracy | Very high |
| Performance | High |
| Maintainability | Very high |
| Ecosystem maturity | High |
| Popularity/adoption | Medium–High |
| Maintenance continuity | Very high |
| Documentation/tooling | High |
| Android integration | High |
| License suitability | Gate |
| Binary size | Medium |
| Memory use | High |
| Battery impact | Medium |
| Migration cost | Medium |
| Implementation effort | Medium |

Weights may change by component.

## Candidate Areas to Research

Do not assume these are final choices.

### Android application layer
Investigate native Android approaches and reasonable alternatives.

### Camera
Investigate platform/native capture APIs and abstractions.

### Image Processing
Investigate:
- mature CV libraries
- custom CPU implementation
- native SIMD/vector acceleration
- GPU/compute options where justified

### OCR
Investigate:
- Android/on-device OCR APIs
- Tesseract-class engines
- ONNX/runtime-backed OCR models
- other actively maintained local OCR engines
- Japanese-specific quality
- vertical Japanese text
- mixed Latin/Japanese text

### PDF
Investigate:
- Android/JVM PDF support
- mature open-source PDF libraries
- native libraries
- custom minimal writer for From-Scratch track

## Evidence

For important selections, record:
- current release/activity
- repository activity
- license
- adoption/community evidence
- benchmark result
- integration proof-of-concept result

Do not use popularity alone as proof of technical superiority.
