# ADR-0001: Repository license — Apache-2.0

## Status
Accepted (2026-08-08)

## Context
Roadmap Milestone 0 requires a license decision before code lands. The
license gates every future dependency and model-weight decision
(AGENTS.md §12).

## Requirements
- Permissive enough for private-use scanning app adoption and forks.
- Compatible with the realistic dependency universe: AndroidX/Jetpack,
  CameraX, kotlinx libraries (all Apache-2.0), future CV/OCR runtimes
  (OpenCV Apache-2.0, ONNX Runtime MIT, ML Kit closed-binary ToS).
- Patent protection is desirable: the project will contain original CV/OCR
  algorithm implementations (From-Scratch track).

## Alternatives
- **MIT/BSD**: equally permissive, no explicit patent grant.
- **GPL-3.0/AGPL**: copyleft would block Play-style distribution of
  derivatives with closed components and complicates mixing with ML Kit-class
  closed binaries; also deters casual OSS contribution to a portfolio repo.
- **MPL-2.0**: file-level copyleft, unusual for Android apps, no benefit here.

## Decision
Apache License 2.0 for the whole repository.

## Why
Ecosystem-native (matches nearly all intended dependencies), explicit patent
grant protects contributors and users of the From-Scratch algorithm code,
and it imposes no redistribution friction. GPL-family exclusion of iText
(AGPL) as a dependency was decided independently on gate grounds.

## Consequences
- iText and other AGPL/GPL libraries cannot be dependencies.
- Model weights need separate license verification per artifact (§12).

## Revisit When
A must-have dependency is copyleft-licensed with no viable alternative.
