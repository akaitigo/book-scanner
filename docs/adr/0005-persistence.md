# ADR-0005: Session persistence — filesystem + atomic JSON manifest (no database in M1)

## Status
Accepted (2026-08-08)

## Context
Sessions must survive process death and be resumable; page order and edit
geometry must be durable; sessions reach book scale (100s of pages). Page
images are files in app-private storage regardless — the decision is where
the *metadata* lives.

## Requirements
- Crash-safe: an interruption never leaves an unreadable session.
- Resume, reorder, delete, non-destructive edit state.
- Testable on the host JVM (fast CI).
- No premature schema/migration machinery.

## Alternatives
Full matrix: `docs/technology-candidates.md` §4.
- **Room**: transactional and query-capable, but M1 has no cross-session
  queries; costs KSP compile time, migrations, and Android-bound tests.
- **SQLDelight**: same trade-off, better multiplatform story we don't need.
- **DataStore**: no relational needs either, but poor fit for ordered
  collections being partially updated.

## Decision
`filesDir/sessions/<sessionId>/` containing `pages/<pageId>.jpg` (originals,
never mutated) and `manifest.json` — the ordered page list with geometry,
serialized via kotlinx-serialization and committed atomically
(write temp file → fsync → rename). Unknown JSON fields are ignored on read
(forward compatibility); a corrupt manifest triggers recovery by re-indexing
the page files.

## Why
Meets every M1 requirement with the smallest moving-part count, is fully
JVM-testable (`core-session` has no Android dependency), and keeps originals
immutable by construction. The rename-commit gives the atomicity a DB would
provide, for one small file.

## Consequences
- Cross-session search (library features, OCR text index) will need a real
  database later; `ScanRepository` is the seam where it swaps in.
- Manifest is rewritten whole per mutation — trivial at KB scale; measured
  guard in tests (500-page manifest parse <100 ms).

## Revisit When
Milestone 3 OCR text search, multi-book library features, or measured
manifest contention/latency on real sessions.
