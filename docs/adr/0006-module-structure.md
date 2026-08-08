# ADR-0006: Module structure — pure-JVM contracts core, engine modules at the boundary

## Status
Accepted (2026-08-08)

## Context
AGENTS.md §6–7 mandates stable interfaces between pipeline stages and
engine-family substitution (Production / From-Scratch, per-stage). The
architecture doc offers two arrangements and delegates the concrete layout,
weighing build performance, testability, dependency isolation, and future
JNI needs.

## Requirements
- Engine substitution enforced structurally, not by convention.
- Contracts must not accrete Android types (From-Scratch CV/PDF must be
  benchmarkable off-device where possible).
- Fast host-JVM tests for core logic; minimal module count (build speed,
  cognitive load).
- No empty placeholder modules (AGENTS.md §14: avoid premature abstraction).

## Alternatives
- **Single `app` module**: fastest to start; engine boundaries erode
  silently — nothing stops a `Bitmap` from reaching the session layer.
- **Full layout now** (`core/`, `production/`, `from-scratch/`,
  `benchmark/`, `app/`): creates empty modules for Milestones 5–7 work;
  premature.
- **`features/` + `engines/` split**: reasonable at M2+ scale; more modules
  than M1 content justifies.

## Decision
Four modules for M1:

```text
core-contracts/     pure JVM (kotlin-jvm plugin): domain model + engine
                    interfaces. Zero Android dependencies — enforced by
                    the toolchain, not review.
core-session/       pure JVM: FileScanRepository (ADR-0005).
engine-production/  Android library: PdfDocumentExporter, BitmapPageTransformer.
app/                Compose UI + composition root (manual DI).
```

Geometry (`PageGeometry`: rotation + normalized crop rect) is plain data in
contracts; applying it to pixels is an engine concern (`PageTransformer`).
`engine-fromscratch/` and `benchmark/` are created when their milestones
start — the contracts they must implement already exist, which is what
prevents drift.

Manual constructor injection at a single composition root; no DI framework
until the graph demonstrably outgrows it.

## Why
The dependency graph itself enforces the two properties that matter most
(Android-free contracts, engine isolation) at the minimum module count.
Pure-JVM cores give sub-second test cycles and CI without emulators.

## Consequences
- Adding an engine = new module implementing existing contracts; no app
  refactor.
- Some M1 interfaces (e.g. `PageTransformer`) have a single implementation
  for now — accepted, because substitution there is an explicit product
  requirement (§7), not speculative abstraction.

## Revisit When
M2 introduces CV (possible NDK module boundary questions) or module count /
build times start hurting.
