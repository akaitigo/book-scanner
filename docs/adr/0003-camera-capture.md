# ADR-0003: Capture — CameraX ImageCapture; import — Android Photo Picker

## Status
Accepted (2026-08-08)

## Context
The capture loop is the product's core interaction: hundreds of shutter
presses per book, order preserved, minimal taps per page (UX requirement).
Separately, the requirements demand importing existing images.

## Requirements
- In-app continuous capture without leaving the screen.
- Sufficient still-image quality for later OCR of small Japanese text.
- Device-compatibility breadth without per-vendor debugging.
- Import path with no unnecessary storage permissions.

## Alternatives
Full matrix: `docs/technology-candidates.md` §2.
- **System camera intent**: one external-app round-trip plus confirm step
  per page — fails the taps-per-page requirement outright.
- **Camera2 directly**: maximal control, but for still capture of pages it
  adds device-quirk surface and a large implementation cost for no
  identified M1–M3 requirement. CameraX exposes `Camera2Interop` if a
  specific control is ever needed.
- **Import-only (no camera)**: fails the core capture requirement; retained
  as the fallback UI when camera permission is denied.

## Evidence
- CameraX: Apache-2.0, Jetpack-maintained, 1.6.x current; verified working
  in this build environment.
- Photo Picker requires no READ_MEDIA permission — keeps the permission
  surface minimal (privacy posture, §11).

## Decision
CameraX (`ImageCapture` use case, `PreviewView`/Compose interop) for
capture; Android Photo Picker (multi-select) for import. Camera permission
denial degrades to import-only mode, not a dead end.

## Why
Only in-app capture satisfies the capture-loop UX; CameraX is the
platform-blessed, maintained abstraction over the device matrix, and its
escape hatch to Camera2 removes the main argument for going lower-level now.

## Consequences
- JPEG output from `ImageCapture` initially (simplest path); RAW/YUV
  pipelines are available later if M2 CV work justifies them.
- CameraX lifecycle/threading conventions become part of the capture module.

## Revisit When
M2 page-detection needs frame-analysis control CameraX cannot express, or
capture latency measurably bottlenecks pages-per-minute on target devices.
