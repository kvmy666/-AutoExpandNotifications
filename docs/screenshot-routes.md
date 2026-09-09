# System screenshot capture — routes investigated, with evidence

Target build, on which everything below was observed directly:

| | |
|---|---|
| Device | `CPH2747` (OnePlus 15), `OP611FL1` |
| ROM | OxygenOS `CPH2747_16.0.9.400(EX01)`, Android 16 (SDK 36), patch 2026-07-01 |
| SystemUI | `16.99.12`, `/system_ext/priv-app/SystemUI` |
| Screenshot app | `com.oplus.screenshot` `16.15.10`, `/product/priv-app/Screenshot` |

The rule for this task was: no hook against a class, method or field not confirmed to exist
on the target build. Nothing below is from memory or from AOSP source — it is what the device
printed when a screenshot was actually taken.

---

## The finding that decided the design

A screenshot was triggered with `input keyevent 120` and the whole flow captured. Trimmed to
the lines that identify the pipeline:

```
D STKeyLog_StrategyBlackscreenshot( 4674): notifyKeyBeforeQueueing, action=0
D LongshotDump/OplusScreenShotEuclidManager( 4674): takeScreenshot : PASS
I [Longshot][OplusScreenshotManagerService]( 5983): takeScreenshot : start to invoke, source=KeyPress
I [Longshot][OplusScreenshotManagerService]( 5983): bindService : service=ComponentInfo{com.oplus.screenshot/com.oplus.screenshot.service.ScreenshotService}
I ActivityManager( 4674): Start proc 25498:com.oplus.screenshot/u0a169 for service {com.oplus.screenshot/com.oplus.screenshot.service.ScreenshotService}
I [Longshot][ScreenshotRequest](25498): start screenshot: Source=KeyPress, PowerAction=false
I [Longshot][SurfaceControlCompat::screenshot](25498): spend:22ms.
I [Longshot][SaveExecutor(SCREENSHOT)](25498): writeBitmapToFile: end compress, result=true
I [Longshot][TaskSaveScreen](25498): onInsertToMediaStore: uri=content://media/external/images/media/63275
I [Longshot][SaveExecutor(SCREENSHOT)](25498): endSave: success, savedUri=content://media/external/images/media/63275, savedSize=21960
```

**`com.android.systemui.screenshot` does not appear anywhere in that flow.** OxygenOS replaced
the AOSP pipeline wholesale. The chain is:

1. `com.oplus.exsystemservice` (pid 5983, **uid 1000**) — `OplusScreenshotManagerService` takes
   the request from the key handler.
2. It binds `com.oplus.screenshot/.service.ScreenshotService` (pid 25498, **uid 10169**,
   SELinux `priv_app`).
3. `com.oplus.screenshot` captures via `SurfaceControlCompat::screenshot`, compresses, and
   inserts into MediaStore. The bitmap never leaves that process.

### The uid wall

```
$ adb shell ps -A -Z | grep -i systemui
u:r:platform_app:s0:c512,c768  u0_a266  6234  com.android.systemui

$ adb shell dumpsys package com.android.systemui | grep sharedUser
    sharedUser=SharedUserSetting{android.uid.systemui/10266}
```

SystemUI here is **not** uid 1000 — it runs under its own `android.uid.systemui` (10266) in the
`platform_app` domain. The vault lives in Gboard's private data directory
(`/data/data/com.google.android.inputmethod.latin/`), owned by `u0_a192`.

So Gboard, SystemUI and the screenshot app are three separate uids with three separate private
data directories. No pipeline hook can write the bytes anywhere the vault can read them. Every
in-memory route therefore costs **a new LSPosed scope *and* a new IPC channel carrying
full-resolution images** — which is exactly the cost the task said to refuse without asking.

---

## Routes, verdicts, evidence

### 1. SystemUI screenshot pipeline — **rejected, does not exist**

The ideal route, and the first one checked. Rejected on the log above: not one line of the
screenshot flow comes from `com.android.systemui`. Hooking `ScreenshotController`,
`ImageExporter` or `ScreenshotData` here would have been a hook against a class that is never
reached on this ROM — the precise failure the task warned about.

### 2. The save / preview-and-share hand-off — **rejected, out of scope and out of reach**

This exists, and it is where the bitmap is most accessible:

```
I [Longshot][SaveExecutor(SCREENSHOT)](25498): writeBitmapToFile: end compress, result=true
I [Longshot][TaskSaveScreen](25498): onInsertToMediaStore: uri=content://media/external/images/media/63275
```

It lives in `com.oplus.screenshot`, which is not in the module's LSPosed scope
(`com.android.systemui`, `com.google.android.inputmethod.latin`, `com.oppo.quicksearchbox`).
Adding it means hooking a new host process, and the uid wall above still blocks delivery. Two
new costs to land in the same place the chosen route reaches for free.

### 3. The framework-side path near the existing chord work — **rejected on precedent**

`OplusScreenshotManagerService` sits in `com.oplus.exsystemservice` at uid 1000. This is the
neighbourhood of the `android` / system_server scope that 3.2.2 deliberately deleted: the
Snapper chord hook blocked the native screenshot before knowing whether Snapper would replace
it, and killed Vol-Down+Power on this exact device. `CLAUDE.md` says not to reintroduce it.
Rejected without an attempt.

### 4. Observer on the screenshot output — **chosen**

The task called this the last resort and gated it on being reachable from a process that
already has access, without adding a storage permission. Both conditions hold:

```
$ adb shell dumpsys package com.google.android.inputmethod.latin | grep READ_MEDIA_IMAGES
        android.permission.READ_MEDIA_IMAGES: granted=true, flags=[ USER_SET|... ]
```

Gboard — the process that already owns the vault — can read the screenshot itself. **No new
permission, no new scope, no new process, no service, no accessibility.** Storage layout
confirmed:

```
$ adb shell content query --uri content://media/external/images/media \
    --projection _id:_data:bucket_display_name:relative_path:date_added \
    --where "relative_path LIKE '%Screenshots%'" --sort '_id DESC'
Row: 0 _id=63275, _data=/storage/emulated/0/Pictures/Screenshots/Screenshot_2026-09-10-01-52-03-96.jpg,
       bucket_display_name=Screenshots, relative_path=Pictures/Screenshots/, date_added=1788994323
```

---

## What was implemented

Two routes attach independently, in priority order, each with its own try/catch and its own
logged attach result:

| Route | Mechanism | Attach test |
|---|---|---|
| `MEDIA_STORE` | `ContentObserver` on `MediaStore.Images.Media.EXTERNAL_CONTENT_URI` | a probe query must return a cursor |
| `FILE_OBSERVER` | `FileObserver` on `Pictures/Screenshots` and `DCIM/Screenshots` | directory exists and is readable |

Both are kept when both attach, because either can attach and then stop delivering. Duplicates
are collapsed by file identity (`_data`, which both routes resolve to) in a single funnel, so
"exactly one entry per screenshot" is a property of one function rather than of route
bookkeeping. If neither attaches, one line says so and the module stays functional:

```
D AutoExpandShot: no route attached — screenshot capture unavailable on this build
```

Dedup across restarts uses a MediaStore `_id` watermark persisted in the vault's own `ae_meta`
table, so restoring or clearing the vault cannot leave a stale mark that swallows the next
screenshots. On first enable it arms at the newest existing screenshot rather than importing
the gallery.

### Confirmed working, on device

```
D AutoExpandShot: FILE_OBSERVER event=8 /storage/emulated/0/Pictures/Screenshots/Screenshot_...jpg
D AutoExpandShot: captured Screenshot_...jpg (file) at 1788986065879
D AutoExpandShot: skipped Screenshot_...jpg: already ingested by another route
D AutoExpandShot: saved as entry id=1955 940x2048 75236B evicted=0 hash=7578c645
D AutoExpandShot: saved as entry id=1956 940x2048 64144B evicted=0 hash=120dd4dc
```

The third line is the cross-route dedup: both routes saw the same file, one entry resulted.

### A bug this logging caught

Those two entries were screenshots from the *previous day*. `CLOSE_WRITE` and `MOVED_TO` fire
on existing files too — a media rescan walking the directory is enough — and unlike the
MediaStore route, the file route had no watermark. A rescan would have imported weeks of
screenshots at once. Fixed in `0471b11`: anything last modified before the watcher attached is
not a new screenshot, with ten seconds of slack for a capture already in flight.

---

## Known limits — stated rather than papered over

- **The watcher lives and dies with Gboard's process.** A screenshot taken while Gboard has not
  run is not seen live; it is picked up by the catch-up scan the next time the keyboard opens,
  which is necessarily before the user can open the vault to look for it. Entries carry the
  capture time from MediaStore, not the ingest time, so a caught-up batch still sorts honestly.
- **The keyboard must have been raised over a real editor at least once.** `inputType=0`
  targets (Termux, for example) bring the IME up without an editor and do not open the vault.
- **Secure/DRM screens** produce no MediaStore row and no file, so they produce no entry and no
  crash — but the module cannot log a reason, because it never learns a screenshot was
  attempted. The task asked for one log line there; this route cannot provide it. Only a
  pipeline hook could, at the cost ruled out above.
- **If the user revokes Gboard's photo access**, the MediaStore route fails its probe and says
  so; the file route follows. This is the one dependency the feature has, and it is a
  permission Gboard already holds rather than one the module asks for.

## Log filter

```
adb logcat -s AutoExpandShot:D
```

Covers: reconcile state changes, route attach results, per-screenshot capture, save with entry
id and dimensions, and every skip with its reason (unreadable, oversize, budget met only by
pins, low storage, already ingested, written before attach). Per-event chatter sits behind the
`shot_log_verbose` pref and is off by default. The same lines are mirrored onto the module's
LSPosed channel, because a device that filters app-level debug output should not cost the
evidence trail.
