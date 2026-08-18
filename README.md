# Book Scanner

[![CI](https://github.com/akaitigo/book-scanner/actions/workflows/ci.yml/badge.svg)](https://github.com/akaitigo/book-scanner/actions/workflows/ci.yml)

An open-source Android app for scanning your own paper books into readable —
and later searchable — PDFs. Everything runs on the device; the app has no
network permission at all.

**Status: Milestone 1 complete; Milestone 2 under way.** Capture or import
pages, manage a scanning session, detect page edges automatically, correct
perspective, crop and rotate, reorder and delete, export one PDF, and resume
after an interruption. OCR is Milestone 3 — see
[docs/roadmap.md](docs/roadmap.md).

## What makes it different

Two things, both about engineering rather than features.

**Exported pages are the captured bytes.** A camera JPEG is stored verbatim and
embedded verbatim in the PDF (`/DCTDecode` passthrough) — no decode, no
re-encode, no generational loss. Measured overhead: **470 bytes per page**;
exported PDFs come out at ~1.00× the size of their source images. See
[docs/pdf.md](docs/pdf.md) and
[ADR-0007](docs/adr/0007-pdf-export-jpeg-passthrough.md).

**Page detection you can measure.** Edge detection, Hough lines and
quadrilateral fitting are hand-written Kotlin, so detection accuracy is a
number in CI (mean corner error **0.002–0.003** of the frame on synthetic
pages) rather than a claim. OpenCV was evaluated and measured out: its Android
binaries link against bionic and cannot load in a host JVM, so a detector built
on it would have been untestable here —
[ADR-0008](docs/adr/0008-page-detection.md).

**Two interchangeable engine families.** Production implementations use the best
practical library or platform API; From-Scratch implementations reimplement the
same algorithms behind the same contracts, so both can be benchmarked on
identical inputs. Milestone 1 ships Production engines; the contracts they
implement (`core-contracts`) are Android-free so a From-Scratch engine can be
benchmarked off-device.

Nothing here is chosen because it is conventional. Every material decision is
argued in an ADR against alternatives — including the one where the platform's
own PDF API was measured out of the design.

## Build and run

Requirements: JDK 17 (the build declares a Java 17 toolchain), Android SDK with
API 37. No Android Studio needed.

```bash
git clone <this repo> && cd book-scanner
echo "sdk.dir=$ANDROID_HOME" > local.properties

./scripts/verify         # formatting + debug APK + all unit tests
./gradlew :app:installDebug
```

If your JDK 17 lives somewhere Gradle cannot find (mise, asdf, sdkman), point
it there from `~/.gradle/gradle.properties`:

```properties
org.gradle.java.installations.paths=/path/to/jdk-17
```

There is no emulator job: the acceptance gates are all measurable as JVM tests,
which is deliberate — see [CONTRIBUTING.md](CONTRIBUTING.md#tests).

CI runs ktlint, assemble and the full unit-test suite on every push, and publishes an
installable debug APK as a build artifact. What it cannot check — camera
capture, predictive back, text scaling, real memory — is listed in
[docs/device-test-plan.md](docs/device-test-plan.md).

## Modules

```text
core-contracts/     pure JVM — domain model + engine contracts. No Android.
core-session/       pure JVM — file-backed sessions, atomic manifests, ingest.
pdf-writer/         pure JVM — minimal image-only PDF writer, JPEG headers.
vision/             pure JVM — image primitives and the page detector.
engine-production/  Android — normalizer, transformer, PDF exporter.
app/                Android — Compose UI, CameraX, composition root.
```

Rationale: [ADR-0006](docs/adr/0006-module-structure.md).

## Documentation

| Document | What it answers |
|---|---|
| [AGENTS.md](AGENTS.md) | The project's rules for contributors and agents |
| [docs/requirements.md](docs/requirements.md) | What the product must do |
| [docs/mvp-proposal.md](docs/mvp-proposal.md) | The Milestone 1 architecture |
| [docs/technology-candidates.md](docs/technology-candidates.md) | Scored comparison of every dependency considered |
| [docs/benchmark.md](docs/benchmark.md) | Method, **measured results**, and what is *not* measured |
| [docs/pdf.md](docs/pdf.md) | The PDF writer's format scope and limits |
| [docs/privacy.md](docs/privacy.md) | How the offline claim is enforced, not just stated |
| [docs/security-boundary.md](docs/security-boundary.md) | Trust boundaries and changes requiring explicit review |
| [docs/operations.md](docs/operations.md) | Verification, APK handling, rollback, and incident procedures |
| [SECURITY.md](SECURITY.md) | Private vulnerability reporting policy |
| [docs/ux-review.md](docs/ux-review.md) | The UX rule review of the M1 screens, violations and fixes |
| [docs/device-test-plan.md](docs/device-test-plan.md) | What only a real device can verify, and how |
| [docs/adr/](docs/adr/) | Decisions, alternatives, evidence, and revisit triggers |
| [docs/implementation-plan.md](docs/implementation-plan.md) | Milestone 1 broken into issues |

## Licence

Apache-2.0 ([ADR-0001](docs/adr/0001-repository-license.md)). Scan only books
you own, for your own use.
