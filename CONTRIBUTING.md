# Contributing

Thanks for looking. This project cares more about *why* a change is right than
about how much it adds, so the process below is mostly about evidence.

Read [AGENTS.md](AGENTS.md) first — it is the project's constitution, and it
applies to human and AI contributors alike.

## Before you build anything

- **Do not pick a technology because it is conventional.** Score it against
  [docs/technology-selection.md](docs/technology-selection.md) and record the
  comparison. "Android means Kotlin" is not an argument; it may be the right
  answer *after* the argument.
- **Non-trivial decisions get an ADR** in `docs/adr/`, using
  [the template](docs/adr-template.md). An ADR must name the alternatives, the
  evidence, and what would make us revisit it. ADR-0007 is the worked example —
  it supersedes an earlier decision and says plainly why.
- **Do not make a performance or quality claim without a measurement.** If it
  cannot be measured here, say so in `docs/benchmark.md` rather than asserting
  it.

## Tests

```bash
./gradlew build          # compile + every test
./gradlew ktlintCheck    # formatting (must be clean)
./gradlew ktlintFormat   # apply formatting
```

Every acceptance gate this project relies on runs as a **JVM test**. That is a
constraint, not a preference: the build hosts have no `/dev/kvm`, so no
emulator is available (see
[ADR-0007](docs/adr/0007-pdf-export-jpeg-passthrough.md)). Design gates to be
measurable without a device where you can.

Conventions:

- New core algorithms come with tests. Not "later".
- Verify against something independent where possible. The PDF writer's output
  is parsed and rendered by Apache PDFBox in tests, because a hand-written PDF
  that only our own reader accepts proves nothing.
- Test names are sentences describing the property, and failure messages carry
  the actual values. A failing test should explain itself.
- Print measurements as `MEASURE <name> key=value …`. CI collects those lines
  into the job summary, and `docs/benchmark.md` cites them.
- What you could not verify goes in the "Not measured" section of
  `docs/benchmark.md`. Silence reads as a pass.

## Dependency and licence review

Every new dependency needs, in the PR description:

1. **Licence**, verified from the artifact — and for models, the *weights'*
   licence separately from the runtime's.
2. **Maintenance evidence** — latest release date and last commit. This is not
   ceremony: it is what disqualified PdfBox-Android (last release 2023-01) in
   [ADR-0004](docs/adr/0004-pdf-export-mvp.md).
3. **What it replaces**, and why writing it ourselves is worse.

Apache-2.0-compatible only (see [ADR-0001](docs/adr/0001-repository-license.md)).
AGPL and GPL are out; so is anything that would drag obligations onto users of
the repository. Test-only dependencies are held to the licence gate too, and
must be marked `testImplementation` so they never ship.

**The app must never gain network access.** `PrivacyManifestTest` asserts the
merged manifest declares no networking permission — a dependency that
introduces one will fail the build. Do not "fix" that test; either drop the
dependency or strip the permission and explain why in
[docs/privacy.md](docs/privacy.md).

## Benchmark dataset policy

Benchmarks need real book pages, and real book pages are usually copyrighted.

- **Do not commit scans of copyrighted books.** Not as fixtures, not as
  screenshots, not in issues.
- **Committed fixtures must be synthetic or clearly licensed.** Prefer
  generated images (see `TestImages` / `JpegFixtures`) — they are deterministic,
  which makes the numbers reproducible.
- **Private samples stay local.** Keep them outside the repository and commit
  the *recipe* instead: which of the categories in
  [docs/benchmark.md](docs/benchmark.md) each sample covers, capture conditions,
  and how someone else could produce an equivalent set from their own books.
- **Every comparative claim states its conditions** — device, build type,
  resolution, dataset, implementation version. A number without them is not a
  result.

## Checking CI

```bash
scripts/wait-for-ci.sh          # waits for HEAD's run, exits non-zero if it fails
```

Use it rather than `gh run watch` on whatever run happens to be newest.
Immediately after a push the new run may not exist yet, so `gh run list
--limit 1` returns the *previous* one — which is how a red commit got reported
as green here on 2026-08-11. The script matches on the commit SHA.

Two failure modes worth recognising:

- **Every Robolectric class fails at once with a bare `classMethod` error.**
  That is Robolectric failing to fetch its Android runtime jars, not your code.
  CI caches them; a first-run fetch can still fail transiently.
- **Formatting fails but the code compiles.** Run `./gradlew ktlintFormat`; the
  pinned CLI is the authority, not your editor.

## Pull requests

- One reviewable change per PR. Splitting for reviewability is good; splitting
  to defer the hard half is not.
- Say what you measured and what you did not.
- Update the docs the change invalidates. A stale `implementation-plan.md` that
  still names a deleted class is a bug in the same way code is.
- No TODO without a stated reason and an intended follow-up.

## Code style

- ktlint, pinned to one version and enforced in CI via the CLI. If
  `ktlintCheck` and your editor disagree, the pinned version wins.
- Comments explain *why*, especially where the code looks unusual. `%PDF`
  object ordering, EXIF-into-geometry, and the synchronous capture guard all
  exist because something specific went wrong; the comments say what.
- No `!!`. Use `?.let`, `?: error(...)`, or `requireNotNull`.
- Keep Android types out of `core-contracts`, `core-session` and `pdf-writer`.
  The toolchain enforces it; do not work around it.
