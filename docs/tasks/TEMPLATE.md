# Implementation Contract: Issue #NN

## Objective

One observable outcome.

## Current state

- Base branch and HEAD:
- Worktree owner and dirty files:
- Related issue/PR:
- Passing verification:
- Unverified device checks:

## Decision and invariants

- Selected design and rejected alternatives:
- Module/data ownership:
- Privacy and offline boundary:
- Failure and rollback behaviour:
- Compatibility requirements:

## Scope

- Modules/files:
- Tests:
- Documentation:

## Non-goals

- Explicit exclusions:

## Acceptance criteria

- [ ] User-visible success is observable.
- [ ] Boundary and failure cases are tested.
- [ ] `./scripts/verify` passes.
- [ ] Required physical-device checks are recorded separately.
- [ ] Privacy, licence, storage, and rollback impacts are documented.
- [ ] Matching-head CI is green before completion.

## Stop conditions

Stop rather than guess if a new dependency, permission, network path, schema
change, destructive data operation, test weakening, or scope expansion is
required but not decided above.
