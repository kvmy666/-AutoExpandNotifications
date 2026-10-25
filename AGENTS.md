# AutoExpand — Agent Notes

Last updated: 2026-10-25 (device local time)
Branch: `feat/autoexpand-v4` · last commit: `1ff1756`
Device: `192.168.100.245:6666` · CPH2747 · Android 16 / SDK 36
OTA: `CPH2747_11.A.43_0430_202607012229`

Read `docs/CHECKPOINT-notif-v4.md` for the long-form resume state. This file records
the confirmed Engine 2 HUD causes and the paths that failed, so they are not retried.

---

## Engine 2 HUD — confirmed root causes

### 1. The stale sibling is the only entry offered to the HUD manager

OxygenOS 16 calls
`HeadsUpManagerImpl.showNotification(NotificationEntry, boolean)` with the **stale
sibling**, not the fresh chat. The fresh child is never offered. Vetoing the stale
entry (`param.result = null`) leaves the HUD manager with nothing to show, which is
why the old row disappeared and the new row also disappeared.

Fix: store the newest child's `NotificationEntry` in `reconcile`
(`newestChildEntry = RowApi.entryOf(row)`) and, in `installHudVeto`, swap the stale
argument for it:

```kotlin
if (promoteNewest(param, key)) return
param.result = null
```

`promoteNewest` validates the substitute's key and `Notification.when` freshness,
sets `param.args[0] = entry`, and sets `hudPendingKey`. Log line:

```
v2 HUD replace stale key=<stale> fresh=<newest> when=<ms> age=<ms>
```

This is the fix that made the stale-sibling + new-chat test correct (user-confirmed).

### 2. Freshness is per-entry, from `Notification.when`

`HUD_FRESH_MS = 60_000L`; allow `age` in `[-5 s, 60 s]`; otherwise veto. It applies
to any grouped entry (`groupKey != null`). No package map, no burst window, no
summary gate. Logs:

```
v2 HUD allow fresh key=… when=… age=…
v2 HUD veto stale key=… when=… age=…
```

### 3. Group keys do not contain `|g:`

Telegram keys are `g:messages` and `c:0channel_…`; the actual `getGroupKey()` has no
leading pipe. `groupKey.contains("|g:")` never matched, so the veto never ran. The
final code uses `if (groupKey == null) return`.

### 4. The summary registry gate was too early

`groupSummaryPkgs[pkg] ?: return` returned before the Telegram summary was
registered, so the first stale HUD slipped through; a later stale attempt was vetoed.
That gate was removed from the veto. The registry still feeds
`RowFacts.pkgHasLiveSummary`.

### 5. The HUD arrow must not run for group children

Clicking the child's real arrow expands the group and forces the HUD out. The row's
`isChildInGroup()` is **false during the HUD re-parent**, so that check is not a
valid gate. Use the engine fact:

```kotlin
val grouped = try { capture(row)?.isGroupChild == true } catch (_: Throwable) { true }
if (grouped) return
```

`capture(row).isGroupChild` uses `pipelineGrouped` + `pkgHasLiveSummary`, which is
reliable during the re-parent window.

### 6. Group expansion and child collapse must wait for the HUD

- `applyGroupExpanded` (the summary's real arrow) during the HUD opens the group and
  forces the HUD out. Gate on `hudKey != null`:
  `v2 … defer EXPAND_GROUP hud`.
- `applyCollapsed` on a heads-up/pinned row forces the HUD out. Gate on
  `facts.isHeadsUp || facts.isPinned`.
- The engine was also collapsing the newest child **2 ms before** `setHeadsUp(true)`.
  A collapsed banner gets the system's short (~800 ms) timeout; the expanded banner
  gets the longer stock timeout. `hudPendingKey`/`hudPendingAt` defer that collapse
  from the allow/substitution until the HUD ends (5 s cap):
  `v2 … defer COLLAPSE hud`.

### 7. Engine 1 heads-up hooks must be gated by `useV2()`

Engine 1's `NotificationExpander` hooks (`addNotification`, `calculateVisibleType`,
`getIntrinsicHeight`, `getPinnedHeadsUpHeight`, `setHeadsUp` before/after,
`setExpandedWhenPinned`, `expandGroupIfNeeded`) must return immediately under
Engine 2. Otherwise they run in parallel and produce the old/duplicate HUD.
`TweaksHud` must stay absent under Engine 2.

### 8. The HUD container is not a notification

The row key `-2|com.android.systemui|…` is SystemUI's internal HUD container. Skip it
with `Reason.Internal` / `INTERNAL_KEY_PREFIX = "-2|"`; otherwise it is reconciled as
a real row.

### 9. Group-forming re-parent

`pipelineGroupedOf` can be `false` while SystemUI re-parents a child into a freshly
updated group. `RowFacts.pkgHasLiveSummary` distinguishes that from a genuine
standalone. `isGroupChild`:
`pipeline == true` → true; `pipeline == false && belongsToGroup && pkgHasLiveSummary`
→ true; `pipeline == null && belongsToGroup` → true.

### 10. Expansion uses the real arrow

`applyExpanded` / `applyGroupExpanded` / `applyCollapsed` invoke
`RowApi.clickExpand(row)` (the real `mExpandClickListener`) under `Attribution.ours`.
Do not call `setSystemExpanded` / `setUserExpanded` setters in those paths. The old
`?:` chain called three setters and broke layout/group state.

### 11. Newest is by `Notification.when`, not update order

`newestChildKey`/`newestChildWhen` are updated only when `contentWhen` is newer.
Cold triggers must not re-expand an older row (`defer not-newest`).

---

## Do not retry — these paths hit a wall

| Attempt | Why it failed |
|---|---|
| `groupKey.contains("|g:")` filter | Actual keys are `g:messages` / `c:…`; never matched, veto never ran. |
| `groupSummaryPkgs[pkg] ?: return` gate | Returned before the summary was registered; first stale HUD slipped through. |
| Per-package monotonic veto | Allowed the wrong row once, then blocked a genuinely new chat after an old one. |
| 2.5 s / 10 s burst windows | Either let the old follow-up through or suppressed a genuine second chat. |
| 15 s freshness + message extras | The extras were empty; no HUD. Use `Notification.when`, 60 s. |
| Veto stale to nothing | The fresh key is never offered; vetoing leaves no HUD. Substitute the fresh entry. |
| `RowApi.isChildInGroup` as HUD-arrow gate | False during the HUD re-parent; the child arrow still ran and forced the HUD out. |
| `facts(row)` inside the hook | `facts` is a local function; compile error. Use `capture(row)`. |
| `MethodHookParam` unqualified outside the hook object | Compile error. Use `XC_MethodHook.MethodHookParam`. |
| HUD arrow on a group child | Expands the group; 700 ms forced exit. The 3.9 s HUD had no arrow. |
| `applyGroupExpanded` / `applyCollapsed` during HUD | Forced exit; gate on `hudKey` / heads-up / pinned. |
| Collapse the newest child just before the HUD | Collapsed banner gets the short ~800 ms timeout; defer with `hudPendingKey`. |
| Unquoted `logcat -s AENotif:D …` in one capture | Returned 0 lines. Use quoted `'AENotif:D'` or capture all and grep; the filtered `-s AENotif:D AENotifProbe:D` works. |
| `builder` / `general` DeepSeek subagents | Intermittent `Bad Request: {"model":"deepseek-v4.1-flash"}`. Denying `rea_*` helped once; `explore-fast` builds/installs reliably. |
| Main-session `bash` / `background_process` | Denied by the global permission rule; use a subagent for adb/build. |

---

## Lock-screen grouping — do not retry (2026-10-25)

- The lock screen shows the stock **closed Telegram summary**; the shade shows the **open group**.
  The `GroupOnKeyguard` skip is intentional: the keyguard size calculator budgets a collapsed
  summary, and opening it in place paints over neighbours on a tight lock screen.
- `ungroup_notifications_enabled` was a **dead pref** and has been **removed** (UI, first-run
  default, upgrade block, strings, README claim). Nothing reads it; do not reintroduce it without
  an actual ungroup implementation.
- `GroupEntry.getSummary` returning null on the keyguard **crashed SystemUI** and sent LSPosed
  into safe mode (all modules off). Reverted; never retry a null group-pipeline summary.
- `ExpandGroupOnKeyguard` + `setUserExpanded(true, true)` + `setChildrenExpanded(true)` had no
  visible effect on the lock screen. Reverted.
- The source is back to the pre-keyguard-open build; the confirmed HUD/layout fix remains. The
  lock-screen "three parents" request is **not delivered**.

## Engine selection (2026-10-25)

- `notif_engine_mode` = `auto` | `v1` | `v2`; UI selector + **Apply & restart SystemUI** live in
  Notifications › Expand engine. The debug Testing screen no longer has an engine toggle.
- `EngineSelection` is the single resolver, cached once per SystemUI process. `MainHook` (install
  gate) and `NotificationExpander.useV2()` both call it, so they cannot disagree.
- Migration: an explicit mode wins; otherwise a present `notif_engine_v2` is honoured as V2/V1;
  otherwise Auto. Auto = Engine 2 only on SDK ≥ 36 + Oplus family, Engine 1 elsewhere.
- Verified on device (`pid 2030`): `engine selected mode=v2 effective=v2`, all HUD hooks
  installed, no DexKit line, no missing-capability line.

## OEM resolver (2026-10-25)

- `OemDetector` logs the skin once (`resolver OEM=OPLUS (…) sdk=36 brand=OnePlus …`).
- `HookTargets` holds the class catalog; `HookTargetResolver` resolves exact name → hierarchy →
  conservative shape match (params/return + name hint). Fields are **never** shape-matched.
- **Verified against the pulled APK**, not guessed: `/tmp/sysui-pull/SystemUI.apk`, build
  `CPH2747_16.0.9.400(EX01)`, SHA-256 `dbe67fd3688a5bb5ebe7200190aaaac0bfbf88068a1534b6aebd62cd3f588ff4`.
- Findings that changed code:
  - `NotificationChildrenContainer` is at
    `com.android.systemui.statusbar.notification.stack.NotificationChildrenContainer` with
    `addNotification(ExpandableNotificationRow, int)`. The old `…notification.row` path does not
    exist here, so Engine 1's child hook had silently bound nothing.
  - `ExpandableNotificationRow.setExpandedWhenPinned(boolean)` **does not exist**; only the
    `mExpandedWhenPinned` field. The Engine 2 call remains a caught no-op — do not write the field
    directly on the verified HUD path.
  - `isGroupExpanded` exists only as `isGroupExpanded$1`; `mExpandClickListener` is typed
    `ExpandableNotificationRow$1` (the arrow cast still works — confirmed by probe traces).
  - `MessagingTextMessage` is in the APK (framework widget), `OplusHeadsUpTouchHelper` at the
    expected Oplus path.
- No unverified OEM class names are shipped. Xiaomi/Samsung candidates were removed; a renamed
  row class is found by DexKit only if its name still contains `ExpandableNotificationRow`.
  Fully renamed classes are unsupported until a real APK from that OEM proves the names.
- DexKit (`org.luckypray:dexkit:2.3.0`) is lazy: native lib loaded on first miss, bridge created
  only when exact names fail, failure degrades to reflection. `extractNativeLibs="true"` is
  required; the lib is at the module's `lib/arm64/libdexkit.so` (verified). On OxygenOS it never
  initializes.

## Current verified state

- Committed base: `39eeceb` on `feat/autoexpand-v4`; OEM work is on `feat/oem-hook-resolver`.
- User-confirmed on 2026-10-25: with a stale Telegram sibling kept in the shade, the
  old row stays suppressed and the new chat HUDs correctly (after the substitution
  fix). Exact duration was user-perceived; no final log capture was kept.
- Latest device run (Engine 2, `pid 2030`): `RowApi caps` all 1s including `oem=OPLUS`,
  `row=ExpandableNotificationRow`; `v2 engine installed`; HUD arrow/visible-type/intrinsic/
  pinned-height/veto hooks installed; engine 1 reports no missing container/content/messaging/
  Oplus-helper capability.
- Unit tests: 78 pass, including the 5 new `EngineSelectionTest` cases.
- Still to verify by hand (needs real chats / unlock):
  - shade children stay one-line with `expand_group_children_enabled=0`;
  - no HUD while inside the active chat, other chats may peek;
  - two genuinely new chats in quick succession;
  - Engine 1 on the old ROM unchanged;
  - the selector chips visually on device.

Live prefs:

```
notif_engine_mode=<absent → Auto, legacy fallback v2>
notif_engine_v2=1            (legacy, still honoured)
expand_headsup_enabled=1
disable_headsup_hooks_enabled=0
disable_headsup_popup_enabled=1
expand_group_parents_enabled=1
expand_group_children_enabled=0
notif_debug_logging=1
notif_probe_enabled=1
```

Tip for startup captures: a 256 KB log buffer evicts the install lines during SystemUI boot.
Run `adb shell logcat -G 16M` before `logcat -c` and the restart, or the caps/engine lines look
missing even though the engine installed.

---

## Build / device workflow

Build:

```bash
cd /home/abdulkarim/AutoExpandNotifications
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
ANDROID_HOME=/home/abdulkarim/Android/Sdk \
./gradlew :app:assembleDebug
```

Install only on success:

```bash
cd /home/abdulkarim/AutoExpandNotifications && \
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
ANDROID_HOME=/home/abdulkarim/Android/Sdk \
./gradlew :app:assembleDebug && \
adb -s 192.168.100.245:6666 install -r app/build/outputs/apk/debug/app-debug.apk
```

Reload SystemUI (verify the pid changes):

```bash
adb -s 192.168.100.245:6666 shell \
  'am start -a android.intent.action.VIEW -d "anywhere://open?sid=1776272750274"'
adb -s 192.168.100.245:6666 shell pidof com.android.systemui
```

Filtered capture:

```bash
adb -s 192.168.100.245:6666 logcat -d -v time \
  -s AENotif:D AENotifProbe:D > /tmp/ae_streamN.log
```

If `-s` returns an empty file, capture all and grep:

```bash
adb -s 192.168.100.245:6666 logcat -d -v time > /tmp/ae_streamN_full.log
grep -nE 'v2 HUD|T:setHeadsUp|v2 arrow|EXPAND|COLLAPSE|TweaksHud' /tmp/ae_streamN_full.log
```

Read:
- `v2 HUD allow fresh` / `v2 HUD veto stale` / `v2 HUD replace stale` — key, `when`, `age`.
- `T:setHeadsUp(true/false)` — duration, `grp=`, `child=`, `summaryFlag=`, `userExp=`.
- `defer EXPAND_GROUP hud` / `defer COLLAPSE hud` — gates working.
- `v2 HUD arrow EXPAND` — must be absent for group children.
- `TweaksHud` — must be absent under Engine 2.

---

## Subagent / model notes

- `small_model` and `explore-fast`: `opencode-go/beam-free` (free).
- `explore-fast` has `permission: "rea_*": deny` so the free model fits the 128-tool limit.
- `explore-fast` can run adb and the Gradle build/install; its limit is 10 steps, so keep
  each task focused (capture + grep, or build + install).
- `builder` / `planner` / `review-strict`: `opencode-go/deepseek-v4.1-flash`
  ($0.15 in / $0.60 out; kept by user choice, over the $0.30 output cap).
- DeepSeek subagents intermittently return `Bad Request: {"model":"deepseek-v4.1-flash"}`.
  Denying `rea_*` let the builder run once; the builder's model change to `beam-free`
  did not take effect (frontmatter validation error `No context found for instance`).
  For a small edit/build, use `explore-fast` for the build/install and the main `edit`
  tool for the code.
- Main-session `bash` and `background_process` are denied; use a subagent for device
  commands.

---

## Next

- Verify the remaining hand tests above on real chats.
- Other OEM support needs real SystemUI APKs (device pull, never a guessed name or a web
  "SystemUI.apk"). Once dumped, add the verified names to `HookTargets` and extend
  `HookTargetResolver` only for what the dex actually shows.
- M4 (rooted AOSP AVD) is still environment-dependent; no emulator image is provisioned on this
  host yet.
