# Device test plan — Milestone 1

CI verifies 152 things. This is the list it *cannot*, and why each one needs
real hardware. Everything here is currently listed as unmeasured in
[benchmark.md](benchmark.md) and [ux-review.md](ux-review.md); running this
plan is what converts those entries into results.

Build under test: `app-debug.apk`, versionName 0.1.0, debug-signed.

## Install

```bash
adb install -r book-scanner-0.1.0-debug.apk
```

Or copy the APK to the phone and open it (allow install from this source once).

## 1. Capture — the one thing no test can fake

CameraX cannot run without a camera. Everything below is unverified today.

| # | Check | Expected | Why it matters |
|---|---|---|---|
| 1.1 | Grant camera access when asked | The rationale appears **before** the system dialog, and mentions that pages stay on the device | The permission is requested from a button, not on screen entry — verify that is what actually happens |
| 1.2 | Capture 10 pages without leaving the screen | Each shutter press adds a thumbnail to the strip; no navigation between shots | The core UX requirement is minimal taps per page |
| 1.3 | Tap the shutter twice rapidly | Exactly one page is added | The guard was moved out of the coroutine for this; a device is where the real timing is |
| 1.4 | Read the captured page at 100% zoom | Small text is legible | Capture quality drives OCR feasibility in M3. If it is not legible here, `CAPTURE_MODE_MAXIMIZE_QUALITY` is not enough |
| 1.5 | Hold the phone in portrait, then landscape, capture in each | Both appear upright in the page list | EXIF orientation is folded into geometry rather than pixels — this is where that gets proven |
| 1.6 | Deny camera access (revoke in Settings, reopen) | The screen explains the situation and offers Import; it is not a dead end | Permission-denied is a designed state |

## 2. Import

| # | Check | Expected |
|---|---|---|
| 2.1 | Import 5 images via the picker | They append in the order the picker returned, after any captured pages |
| 2.2 | Import a screenshot (PNG) | It appears and later exports correctly — the normalizer transcodes it |
| 2.3 | Import a HEIC photo, if the phone shoots HEIC | Same |
| 2.4 | Confirm no storage permission was requested | The Photo Picker should never ask |

## 3. Editing and ordering

| # | Check | Expected |
|---|---|---|
| 3.1 | Rotate a page, save, reopen | Rotation persists |
| 3.2 | Drag the crop corners | Corners are grabbable **on the first try** — this is the 48 dp touch target, previously ~27 dp |
| 3.3 | Edit a page, then press **system back** | A "Discard changes?" dialog appears — back must not silently discard |
| 3.4 | Same, with the predictive-back gesture (swipe and hold) | The peek animation runs, then the dialog |
| 3.5 | Long-press a page in the grid | Selection mode, not a drag |
| 3.6 | Overflow → Reorder pages, then drag one | It moves; the list follows the finger; auto-scroll works near the edges |
| 3.7 | In reorder mode, use the ◀ ▶ buttons instead | Same result without any gesture |
| 3.8 | Reorder, leave the screen, come back | The order persisted |

## 4. Export — the payoff

| # | Check | Expected |
|---|---|---|
| 4.1 | Export a 20-page scan | Progress counts up; the PDF lands where you chose |
| 4.2 | Open it in Google Drive / Adobe Reader / a desktop viewer | Opens; pages in order; no sideways pages |
| 4.3 | Compare a page in the PDF against the original on screen | Identical — uncropped pages are the camera's own bytes |
| 4.4 | Check the file size against the session | Roughly the sum of the page images |
| 4.5 | Cancel an export midway | **No leftover PDF at the destination** — this was a real defect; verify the fix on a real SAF provider |
| 4.6 | Export with one page cropped | That page is cropped and upright; the rest are untouched |

## 5. Scale and endurance

| # | Check | Expected | Currently unmeasured |
|---|---|---|---|
| 5.1 | Capture 100+ pages in one session | No slowdown, no crash | Yes |
| 5.2 | Scroll the full grid | Thumbnails load smoothly; no jank | Yes |
| 5.3 | Export that session, timing it | Note seconds per page | **Per-page latency — record it** |
| 5.4 | Watch memory during the export (Android Studio profiler, or `adb shell dumpsys meminfo dev.bookscanner.app`) | Flat, not growing per page | **Peak memory — record it** |
| 5.5 | Export a session containing a **cropped 12 MP** page | No OOM | `maxReencodedDimension` defaults to unlimited; this is the known gap |
| 5.6 | Kill the app mid-capture (swipe from recents), reopen | The session is intact; at most the in-flight page is lost | Yes |

## 6. Accessibility and layout

| # | Check | Expected | Rule |
|---|---|---|---|
| 6.1 | Settings → Display → Font size at maximum | Nothing clipped or overlapping; the page-number chip is the likely failure point | A11Y-004, TYPE-004 |
| 6.2 | Turn on TalkBack, walk each screen | Reading order matches visual order; every control has a name; the capture count is announced | A11Y-001, A11Y-003, A11Y-007 |
| 6.3 | Gesture navigation on, check every screen bottom | The FAB and last row are not under the system bar | SPACE-001, AND-002 |
| 6.4 | Dark mode | Readable; nothing invisible | COLOR-001 |
| 6.5 | An accessibility scanner pass, if available | No contrast or target-size findings | COLOR-001/002, TOUCH-001 |

## Recording results

Add what you measure to [benchmark.md](benchmark.md) with the conditions the
comparative rule requires — device, Android version, build type, page
resolution, dataset. Move anything verified out of the "Not measured" section.

Anything that fails here is a real bug: these paths have never run on hardware.
