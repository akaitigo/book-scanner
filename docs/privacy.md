# Privacy

The claim: **scanned book pages never leave the device.** This document is
about how that is enforced, because a privacy claim that depends on nobody
adding a network call later is not a claim, it is a hope.

## How it is enforced

The app declares **no networking permission at all**. Not `INTERNET`, not
`ACCESS_NETWORK_STATE`. Without `INTERNET`, the platform refuses socket
creation regardless of what any library attempts — so this is enforced by
Android, not by code review.

`PrivacyManifestTest` (`app`) asserts this against the **merged** manifest, not
against the one in this repository. That distinction is the whole point: a
dependency can contribute a permission the app's own manifest never mentions.

It has already caught one. `androidx.media3`, pulled in transitively by CameraX
for video capture that this app does not use, declares
`ACCESS_NETWORK_STATE`. It is now removed explicitly:

```xml
<uses-permission
    android:name="android.permission.ACCESS_NETWORK_STATE"
    tools:node="remove" />
```

## Permissions the app does request

| Permission | Why | Degradation if denied |
|---|---|---|
| `CAMERA` | Photographing pages | The capture screen explains the situation and offers importing images instead — denial is a designed state, not a dead end. |

That is the complete list.

Notably absent: **no storage permission**. Imports go through the Android Photo
Picker, which grants access to exactly the images the user selected without any
`READ_MEDIA_*` grant. `PrivacyManifestTest` asserts these stay absent too.

## Where scans live

```text
<app private storage>/sessions/<sessionId>/
    manifest.json          session metadata and page order
    pages/<pageId>.jpg     the captured images
```

App-private storage: not visible to other apps, not indexed by the media
scanner, not backed up (`android:allowBackup="false"`, so pages are never
copied into a cloud backup), and removed entirely when the app is uninstalled.

The only time a scan leaves this directory is when **the user explicitly
exports a PDF** through the system file picker, choosing the destination
themselves.

## What the app does not do

- No analytics, telemetry, crash reporting, or advertising SDK.
- No account, no sign-in, no identifiers.
- No cloud sync or backup of scans.
- No remote processing of any kind. All image work, and later all OCR, is
  on-device.

## If a network feature is ever proposed

`AGENTS.md` §11 sets the terms, and they are strict:

1. It must be **opt-in**, off by default.
2. The UI must state plainly, at the point of use, that pages leave the device.
3. The **local pipeline must remain fully available** — a network engine may be
   an alternative, never a replacement.
4. The privacy implications must be documented here before it ships.

Adding `INTERNET` would fail `PrivacyManifestTest`. That failure is the design
working: it forces the decision to be made deliberately, in a pull request,
with this document updated — rather than arriving as a transitive dependency.

## Related

- [ADR-0002](adr/0002-android-app-layer.md) — native Android, keeping the
  pipeline on-device
- [ADR-0005](adr/0005-persistence.md) — app-private storage layout
- [docs/benchmark.md](benchmark.md) — the assertions above, as measurements
