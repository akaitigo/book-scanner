# ADR-0002: Application layer — native Android, Kotlin + Jetpack Compose

## Status
Accepted (2026-08-08)

## Context
The app is Android-only, camera-centric, offline, and will host swappable
CV/OCR engines, possibly including JNI/NDK components later. AGENTS.md §3
forbids choosing "Android means Kotlin" by convention; the choice must be
argued against alternatives.

## Requirements
- Fine-grained camera control for a fast multi-page capture loop.
- Cheap integration with platform APIs (Photo Picker, SAF, PdfDocument)
  and future native/ML engines.
- Long-term maintainability and contributor availability for OSS.
- No cross-platform requirement exists (single-target Android).

## Alternatives
Full scored matrix: `docs/technology-candidates.md` §1.
- Kotlin + Views: no maintainability win, higher UI effort for
  list/reorder/editor screens.
- Flutter / React Native: every capture-loop and engine interaction crosses
  a platform channel/bridge; `docs/architecture.md` demands justification
  for such FFI overhead and none exists — cross-platform reach is not a
  requirement. Binary size and camera-plugin quality are additional costs.
- Kotlin Multiplatform + Compose Multiplatform: pays structure tax for an
  iOS target that is not planned; keeping core modules pure-JVM (ADR-0006)
  preserves most of the option value for free.

## Evidence
- Candidate matrix scores (Deliverable C §1).
- CameraX + Compose (BOM 2026.06) + Kotlin 2.4 verified building in this
  exact environment (sibling project, AGP 9.3.1).
- All selected components Apache-2.0 (license gate pass).

## Decision
Native Android app: Kotlin, single-activity Jetpack Compose, Navigation
Compose. minSdk 26, target/compile SDK 37.

## Why
Highest requirement fit (camera, platform APIs, future JNI) with the
strongest maintenance story, at zero license risk. Every alternative adds an
indirection layer whose cost the requirements cannot justify.

## Consequences
- UI code is not portable to iOS; core logic remains portable via pure-JVM
  modules.
- minSdk 26 excludes pre-2017 devices (accepted; CameraX/Compose baseline
  and >97% device coverage).

## Revisit When
An iOS target becomes real, or Compose introduces a blocking regression for
camera-preview-heavy UIs.
