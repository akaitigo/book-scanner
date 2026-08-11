# UX Review — Milestone 1 screens

- Target: SessionList, Capture, PageList, PageEditor (Compose, Android)
- Tier: T1 (new app, multiple screens)
- Reviewed: 2026-08-11, against the project's mobile UX rule set
- Method: rule checklist → falsification pass on every Yes → fix → re-check

This review was run **against the implementation**, not against a design doc,
so "the design does not say" was never an available answer — each check was
resolved by reading the actual composable.

## Violations found and fixed

| Rule | Severity | Where it was | Fix |
|---|---|---|---|
| NAV-001 | critical | `CaptureScreen` had no navigation icon — only a "Done" check in the actions. System back worked, but nothing on screen said so. | Added a back arrow as the navigation icon; "Done" became a labelled text button. |
| STATE-005 | critical | Session and page load failures were shown as a snackbar. It flashes past and leaves an *empty list* behind — indistinguishable from "you have no scans". | Split `loadError` from `errorMessage`. A failure that leaves the screen contentless renders `ErrorState` with a retry button (and, on the page screen, a way back). |
| PERM-001 | high | Camera permission was requested in `LaunchedEffect(Unit)` — the instant the screen opened, with no reason shown first. | The permission is requested from an "Allow camera" button, under an explanation that also states pages never leave the device. |
| A11Y-002 | high | Reordering was possible **only** by long-press drag. | Reorder is now an explicit mode; in it, every page carries earlier/later arrow buttons. Dragging is a shortcut, not the only path. |
| — (functional) | high | `detectDragGesturesAfterLongPress` on the grid and `combinedClickable(onLongClick)` on each cell both claimed long-press. Neither behaved reliably. | Long-press = select (the Android convention). Drag reorder is live only inside reorder mode, entered from the overflow menu. |
| NAV-010 / AND-004 | high | The editor confirmed discard on its Close button, but **system back bypassed it** and silently dropped unsaved crop/rotation. | `BackHandler` routes system back into the same confirmation. Also added `android:enableOnBackInvokedCallback="true"` for predictive back. |
| TOUCH-001 | high | Crop handles used raw pixel constants (`radius = 18f`, slop `80f`) — roughly 6 dp visible, 27 dp grabbable, both under the 48 dp minimum. | Handles are dp-based: 10 dp drawn, 24 dp grab radius (half of a 48 dp target). |
| DESTR-003 / TOUCH-002 | high | Each session row had `[Add pages][Rename][Delete]` as three adjacent icon buttons — delete one mis-tap from the most-used control. | Rename and delete moved into an overflow menu; only "Add pages" stays inline. |
| SPACE-001 | high | The page grid's bottom padding was 12 dp, so the FAB covered the last row. | Bottom content padding raised to 96 dp (the session list already had this). |
| AND-001 | high | Predictive back was not enabled in the manifest. | `android:enableOnBackInvokedCallback="true"`; all back handling goes through `BackHandler` / Navigation Compose, never `onBackPressed()`. |
| STATE-002 | medium | Bare `CircularProgressIndicator` on both list screens. | `LoadingState` names what is loading ("Loading your scans…", "Loading pages…"). |
| COLOR-003 / A11Y-006 | medium | Page selection was a border colour plus a **Close** icon — semantically wrong, and the recovered-session warning was red text only. | Selection uses a filled check circle; the recovery warning carries a warning icon alongside its colour. |
| WRITE-001 | medium | The shutter button used a check-mark icon, the same icon as "Done". | Shutter is a lens icon with the accessible name "Capture page"; "Done" is a text button. |
| A11Y-001 / A11Y-003 | medium | Session rows announced title, count and date as three fragments; page cells had no accessible name. | Rows announce one composed label; cells announce "Page N of M, selected/not selected". |
| A11Y-007 | medium | Capture count and export progress changed silently for screen-reader users. | Both are polite live regions. |

## Deliberate decisions worth recording

- **Offline is not a UI state here.** The app has no network permission at all,
  so there is no degraded-connectivity state to design (STATE-006 N/A, with
  reason). `PrivacyManifestTest` asserts the permission's absence.
- **Permission-denied is a designed state, not an error** (STATE-009). Denying
  the camera leaves a screen that explains the situation and offers import as a
  first-class action, plus a way to retry the request.
- **Delete is confirmed, not undoable.** Page and session deletion are
  irreversible and confirm with the specific loss named ("Delete \"X\"? This
  permanently deletes N scanned page(s)"). An undo would need a trash tier that
  Milestone 1 does not have; the confirmation names the scope instead
  (DESTR-002 satisfied; DESTR-001's preference for undo is not).

## Verified on device (Pixel 7, Android 17, 2026-08-11)

Three of the fixes above were exercised on real hardware and behaved as
designed:

- **PERM-001** — the rationale is shown first; the system dialog only appears
  after "Allow camera" is pressed. Import is offered alongside it.
- **NAV-001** — the capture screen's back arrow is present and works.
- **SPACE-002** (keyboard) — with the Japanese IME open, the New scan dialog
  moves above the keyboard and both actions stay reachable. This was not in the
  original review; it was found by driving the real screen.

## Not verified

Stated explicitly rather than claimed. The 2026-08-11 device session did not
reach these — see [benchmark.md](benchmark.md#still-not-measured-on-device):

- **Contrast ratios (COLOR-001/002)** — the UI uses Material 3 semantic colour
  roles throughout, which are designed to meet the ratios, but no measurement
  was taken.
- **Layout at 200% text scale (A11Y-004, TYPE-004)** — typography is all `sp`
  via `MaterialTheme.typography`, but the page-number chip inside a fixed
  aspect-ratio cell is the most likely place to overflow, and it was not
  measured.
- **Predictive back animation and real inset behaviour (AND-001/002)** — the
  code paths are correct by construction; the visible result needs a device.
  No emulator is available in this environment (`/dev/kvm` is absent, see
  ADR-0007), so these are device checks, not CI checks.

## Result

**0 violations found · 3 checks still unverified (contrast, 200% text,
predictive-back animation).**

Updated 2026-08-11: a device session confirmed PERM-001, NAV-001 and the
keyboard-inset behaviour, but did not reach the three above.

Stated that way on purpose: COLOR-001 is a critical rule and A11Y-004 a high
one, and neither was measured. The process this review follows records
unmeasured checks as *unverified*, never as passes — so "critical: 0" alone
would be a quotable overstatement.

Self-review caveat: a passing self-review means the obvious violations were
found and fixed. The three "not verified" items above need a device before this
can be called measured.
