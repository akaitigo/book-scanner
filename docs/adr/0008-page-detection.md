# ADR-0008: Page detection — an in-house Kotlin detector; platform matrix for the warp

## Status
Accepted (2026-08-11)

## Context
Milestone 2 turns the app from "camera plus PDF" into a scanner: find the
page's quadrilateral in a photo, correct its perspective, and even out the
lighting. `PageDetector` and `PageCorrector` already exist as contracts
(AGENTS.md §6); this decides what implements them.

AGENTS.md §3 forbids picking a computer-vision library by convention, and §4
forbids adopting one because "computer vision means OpenCV".

## Requirements
- Detect a page boundary reliably enough that manual correction is the
  exception (product priority 1: a genuinely usable scanner).
- Perspective-correct without visible distortion.
- Stay offline; no Google Play services dependency (AGENTS.md §11 and
  [privacy.md](../privacy.md)).
- Apache-2.0-compatible ([ADR-0001](0001-repository-license.md)).
- **Be verifiable.** This project has no emulator: the build hosts have no
  `/dev/kvm`, and [ADR-0007](0007-pdf-export-jpeg-passthrough.md) already had
  to discard a selection that turned out to be unmeasurable here.
- Keep APK size proportionate. The whole app is currently 25 MB.
- Leave room for a From-Scratch implementation behind the same contract
  (AGENTS.md §7), so the two can be benchmarked on identical inputs.

## Alternatives

### A. OpenCV (`org.opencv:opencv:4.14.0`)
The obvious answer, and a genuinely excellent library: Apache-2.0, 90k stars,
pushed the day this was written, `Canny` → `findContours` → `approxPolyDP` →
`getPerspectiveTransform` → `warpPerspective` is a well-trodden path.

Measured, 2026-08-11, by resolving it from Maven Central:

| Measurement | Result |
|---|---|
| AAR | 117 MB |
| Native libs, arm64-v8a | **24.7 MB** |
| Native libs, armeabi-v7a | 16.1 MB |
| Java classes | 0.4 MB |

So shipping it roughly **doubles the app** for one feature, even with ABI
splits.

The disqualifying finding is different, though. The `.so` files are Android
binaries: `readelf -d` on the x86_64 library shows it linked against bionic
(`libc.so`, `libm.so`, `libdl.so`), not glibc. **It cannot load in a host JVM**,
so no Robolectric test can exercise a single line of an OpenCV-based detector,
and there is no emulator to fall back on. An OpenCV detector would be
untestable in this project's CI — exactly the situation ADR-0007 was written
about.

### B. ML Kit Document Scanner (`play-services-mlkit-document-scanner`)
Google's turnkey scanner: detection, cropping and enhancement, tuned by people
with far more data than this project will ever have. The AAR resolves at
**1.2 MB**, most of which is a licence file, because the implementation ships
inside Google Play services rather than the app.

Three problems, in increasing order of seriousness:

1. It is a **closed binary delivered by Play services**, so it cannot run on a
   device without GMS and its behaviour is not inspectable. For a project whose
   privacy claim is enforced rather than asserted, an opaque dependency in the
   scanning path is a poor fit.
2. It is **not a detector** — it is a complete full-screen activity that owns
   capture, cropping and review. It cannot implement `PageDetector`; adopting
   it means deleting the capture screen and the pipeline behind it, and there
   would be nothing left for a From-Scratch engine to be compared against.
3. Its licence is Google's proprietary terms, not Apache-2.0.

Rejected on architecture fit, not on quality. Worth stating plainly, because
for most apps it would be the right answer.

### C. An in-house detector in Kotlin
Grayscale → blur → gradient → edge thresholding → contour/line extraction →
quadrilateral selection. Perspective correction via the platform's
`Matrix.setPolyToPoly`, which maps four points to four points and is applied
directly to a `Bitmap` — no library needed for that step at all.

- **0 bytes** of APK.
- Pure JVM, so it runs in CI on synthetic pages with known ground truth.
- It is, by construction, Milestone 5's "From-Scratch image core" — the same
  code the roadmap already committed to writing.

Risk, stated honestly: a hand-written detector may simply be worse than
OpenCV's on real photographs, and product priority 1 says the app must be
usable. That risk is mitigated by the contract, not by hope: `PageDetector` is
a seam, manual crop already exists and stays, and the benchmark harness exists
to say which is better rather than to assume.

## Decision
1. **Detection**: an in-house detector in a new pure-JVM module,
   implementing `PageDetector`. No CV dependency.
2. **Perspective correction**: the platform's `Matrix.setPolyToPoly` applied to
   the `Bitmap`, in `engine-production`. This needs no library, avoids the
   `Bitmap` ↔ `Mat` conversions an OpenCV path would add, and confines any
   future CV dependency to detection alone.
3. **Manual crop stays.** Automatic detection proposes a quadrilateral; the
   user can always override it. Detection failing must never block a scan.
4. **OpenCV remains a candidate comparator.** If on-device benchmarking shows
   the in-house detector is materially worse, OpenCV can be added as a second
   `PageDetector` and measured against it — at which point the 24.7 MB is a
   decision with evidence behind it rather than a default.

## Why
Two of the three candidates cannot be verified in this project's CI, and one of
those also cannot implement the contract at all. The third is free, testable,
and is work the roadmap already required. Choosing it is not a preference for
writing things ourselves — it is the only option that can be *shown* to work
here.

The size figure matters too: an app whose central claim is that exported pages
are the camera's own bytes, at ~1.0× their size, should not double its download
for a feature whose quality it cannot yet measure.

## Consequences

Positive:
- Detection quality becomes measurable in CI against synthetic pages with known
  corners, before any device is involved.
- No new dependency, no licence question, no APK growth.
- Milestones 2 and 5 stop being separate efforts; the From-Scratch image core
  is the Production detector until something beats it.

Negative / accepted:
- We own edge detection and quadrilateral fitting, including their failure
  modes on glossy paper, low light and busy backgrounds. Manual crop is the
  fallback, and the benchmark categories in [benchmark.md](../benchmark.md)
  exist to expose where it fails.
- The first version will very likely be worse than OpenCV on hard photographs.
  Publishing that comparison honestly is the point of the benchmark track
  (AGENTS.md §10), not something to avoid.
- Milestone 5's framing changes: the From-Scratch image core is being written
  first and promoted to Production duty, the same inversion ADR-0007 recorded
  for the PDF writer. Twice is a pattern worth naming: **this project's
  environment systematically favours implementations it can test.**

## Revisit When
- The benchmark shows detection success materially below what OpenCV achieves
  on the same inputs, on a real device.
- An emulator or device-lab CI becomes available, which would make an
  OpenCV-based detector testable and reopen alternative A on equal terms.
- A per-ABI OpenCV distribution small enough to change the size calculus
  appears, or a minimal-module build becomes available as a published artifact.
