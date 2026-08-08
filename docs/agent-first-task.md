# First Task for Codex / Claude Code

Read:
1. `AGENTS.md`
2. `docs/requirements.md`
3. `docs/architecture.md`
4. `docs/technology-selection.md`
5. `docs/benchmark.md`
6. `docs/roadmap.md`

Then do NOT immediately implement the full scanner.

Perform the following first:

## Deliverable A — Repository Assessment
If code already exists:
- inspect it;
- summarize current architecture;
- identify reusable parts;
- identify conflicting assumptions.

If the repository is empty:
- say so and proceed.

## Deliverable B — MVP Technical Proposal
Propose the smallest architecture capable of Milestone 1 while preserving future Production/From-Scratch substitution.

Include:
- build/module structure
- language choices and why
- Android UI/capture approach
- persistence strategy
- PDF strategy
- test strategy
- dependency list
- license notes

Do not select technologies only because they are conventional.

## Deliverable C — Candidate Matrix
For each important dependency or platform technology, compare reasonable alternatives using `docs/technology-selection.md`.

At minimum evaluate:
- Android application layer
- camera
- PDF export

OCR/CV can remain research tasks until later milestones unless required by the proposed architecture.

## Deliverable D — Initial Implementation Plan
Break Milestone 1 into small executable issues with:
- objective
- files/modules likely affected
- acceptance criteria
- test requirements

## Deliverable E — ADRs
Create ADRs for decisions that materially constrain the future architecture.

Only after these deliverables are coherent should implementation begin.
