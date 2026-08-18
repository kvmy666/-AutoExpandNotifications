# CHECKPOINT — notification engine v2

**Written 2026-08-16.** Resume file for a fresh session. Everything needed to continue is here or
in the files it points at; nothing important lives only in chat history.

---

## 1. Where things stand in one paragraph

The notification expand engine has been rebuilt as an opt-in **v2 engine** that drives expansion
by *state* instead of clicking the expand arrow. It is **built, installed on the device, enabled,
and working** — a notification posted while the phone is locked now renders **expanded in place**
on the lock screen (`getIntrinsicHeight` 305 vs 169 collapsed), something the old engine could
only achieve by opening the locked shade. Heads-up and swipe-to-toggle are untouched. What remains
is **human visual verification** (§5) — the automated evidence is done.

## 2. Repo state

- Branch: **`feat/notif-engine-v2`**, working tree clean.
- `main` is **code-untouched**. It received exactly one docs-only commit (`f8997bf`,
  `docs/notifications-ar.md` + `docs/notifications-architecture.drawio`). `git diff 26de914 main`
  shows only those two files.
- Commits on the branch, oldest first:

| commit | what |
|---|---|
| `6e78943` | Phase 1 instrumentation: test-notification sender, Restart-SystemUI button, read-only probe |
| `ad022e5` | Baseline evidence log (static bytecode + runtime trace of the 3.2.1 engine) |
| `cb7c9bc` | Probe upgraded to a trigger census; captured the lock-screen oscillation |
| `d9945b3` | **The v2 engine** + debug SET_PREF receiver + engine-selection bugfix |
| `866ecef` | **Group parent + children toggles** (§11) + POST_TEST/CANCEL_TEST broadcasts |

- Version still `30201` / `3.2.1` — deliberately not bumped yet.

## 3. Device state (as left)

OnePlus **CPH2747**, Android 16 / SDK 36, OxygenOS **`CPH2747_16.0.9.400 (EX01)`**, wireless adb at
`192.168.100.245:6666`.

Debug APK installed with these prefs live:

```
notif_engine_v2      = 1     <- v2 is ACTIVE
notif_debug_logging  = 1
notif_probe_enabled  = 0     <- verbose; leave off for normal daily use
expand_shade_enabled = 1  expand_lockscreen_enabled = 1  expand_headsup_enabled = 1
expand_group_parents_enabled  = 1    <- groups open automatically
expand_group_children_enabled = 0    <- rows inside a group stay one-line
disable_headsup_hooks_enabled = 0
```

> The probe is chatty. For a realistic day of use set `notif_probe_enabled = 0` and
> `notif_debug_logging = 0` (§4), then restart SystemUI.

## 4. How to resume — exact commands

All adb commands need `MSYS_NO_PATHCONV=1` in Git Bash or remote paths get mangled.

```bash
export MSYS_NO_PATHCONV=1
ADB="C:/Users/krom3/AppData/Local/Android/Sdk/platform-tools/adb.exe"
"$ADB" connect 192.168.100.245:6666
```

**Restart SystemUI (no root needed on the host).** The user's status-bar triple-tap is an Anywhere
deep link, and it can be fired directly — this is the reload loop:

```bash
"$ADB" shell 'am start -a android.intent.action.VIEW -d "anywhere://open?sid=1776272750274"'
# verify it actually restarted:
"$ADB" shell "pidof com.android.systemui"     # pid must change
```

**Flip any pref durably** (debug-only receiver, absent from release builds):

```bash
"$ADB" shell "am broadcast -a io.github.kvmy666.autoexpand.SET_PREF \
  -n io.github.kvmy666.autoexpand/.DebugPrefReceiver --es key notif_engine_v2 --es value 0"
```
Then restart SystemUI — **engine choice is read once at startup by design**, so it never changes
under live hooks. `value 1` goes back to v2. (Patching `Settings.Global` directly does *not* stick:
any `writePrefsFile` call republishes from SharedPreferences and drops keys never written there.)

**Build + install:**
```bash
export JAVA_HOME="C:/Program Files/Android/Android Studio/jbr"
./gradlew.bat :app:assembleDebug -q
"$ADB" install -r app/build/outputs/apk/debug/app-debug.apk
```

**Capture logs** — always a filtered background stream, never `logcat -d`; the buffer rolls in
~12 s under OEM noise:
```bash
"$ADB" logcat -c
"$ADB" logcat -s AENotif:D AENotifProbe:D TweaksHud:D &
```

**Post test notifications without touching the phone:**
```bash
"$ADB" shell 'cmd notification post -S bigtext -t "Title" tag1 "line one. line two. line three."'
# styles: bigtext | inbox | messaging | bigpicture | media
```

`cmd notification post` cannot build a **grouped** notification, so debug builds expose the app's
own sender over the same receiver. `kind` is any `TestNotifier.Kind` name; `delay` is in ms and is
what makes a lock-screen test possible at all (the app must be behind the keyguard when it fires):

```bash
"$ADB" shell "am broadcast -a io.github.kvmy666.autoexpand.POST_TEST \
  -n io.github.kvmy666.autoexpand/.DebugPrefReceiver --es kind Group --es delay 0"
# kinds: BigText | Messaging | Inbox | LongText | Group | AutoGroupFlood | Silent
"$ADB" shell "am broadcast -a io.github.kvmy666.autoexpand.CANCEL_TEST \
  -n io.github.kvmy666.autoexpand/.DebugPrefReceiver"
```

**Drive the UI:** lock `input keyevent 26`; wake `input keyevent 224`; unlock swipe
`input swipe 636 1800 636 700` then `input text <PIN>` then `input keyevent 66`; open shade
`input swipe 636 5 636 1800 250`. Screen is 1272x2772. **`cmd statusbar expand-notifications` is a
no-op on OxygenOS** — use the swipe. Lock-after-timeout is 5 s, so screen-off must exceed ~12 s to
get a genuine keyguard.

## 5. What still needs YOUR eyes

This is the only outstanding work. Everything else is measured.

| # | Test | Pass condition |
|---|---|---|
| 1 | Open/close the shade ~10 times with real notifications | Expanded every time, **no self-collapse** |
| 2 | Collapse one manually in the shade | **Stays collapsed** until the app updates it |
| 3 | Collapse one manually on the lock screen | **Stays collapsed** — this used to force itself open |
| 4 | Lock screen, fresh lock, new notification | Expands **in place** — no scrim, no unlock prompt, no locked shade opening |
| 5 | Lock screen while authenticated-but-still-locked | Same; this was the worst bug |
| 6 | Heads-up banners, all app types | **Byte-identical to before** — this path was not modified |
| 7 | Swipe down on a heads-up banner | Toggles expand/collapse, does **not** launch the app |
| 8 | WhatsApp / Telegram threads with several messages | Sensible; app-declared groups still defer to their summary |
| 9 | Turn on "hide sensitive content on lock screen" | Redacted rows stay **collapsed** — privacy must hold |

If something fails, capture the log stream from §4 and read the `v2 <trigger> <decision>` lines —
every decision logs its reason (`skip=UserCollapsed`, `skip=AppGroupChild`, …).

To A/B against the old engine: flip `notif_engine_v2` to `0`, restart SystemUI, repeat. No rebuild.

## 6. Facts established — do NOT re-derive these

All verified against the **live** SystemUI pulled from this device (`dexdump`) and/or runtime traces.
Full detail in `docs/notif-engine-v2-findings.md`.

1. **`isExpanded(Z)` — the argument is `allowOnKeyguard`.** Order inside the method:
   `shouldShowPublic()` → keyguard gate → `mHasUserChangedExpansion` / `mIsSystemExpanded` /
   `mUserExpanded`. On keyguard **no state value can expand a row**; lock-screen expansion only
   ever worked because clicking the arrow triggered `goToLockedShade`.
2. **Therefore: rewrite the ARGUMENT, never the return value.** Forcing the return bypasses
   `shouldShowPublic()` → sensitive notifications render expanded on the lock screen (privacy bug),
   and bypasses the `mUserExpanded` tail → user can never collapse. Confirmed at runtime:
   `public=true` correlates perfectly with `isExp(T)=false`.
3. **`isShowingExpanded()` calls `isExpanded(false)`** → it lies on keyguard. The old idempotence
   guard was blind there. `isExpanded(true)` is the honest read and `getIntrinsicHeight()` tracks it.
4. **Grouping was not removed — Android 16 added system bundles** (`g:Aggregate_AlertingSection`,
   `g:Aggregate_SilentSection`) with real summary rows. Ordinary notifications become children of
   these, so `isChildInGroup()` is true for them, and the old blanket child-skip silently dropped
   them. It is also a race (`child=false` at attach, `true` moments later) — the best explanation
   of the old ~10% failure rate.
5. **The old keyguard mitigation was dead code.** `getExpandClickListenerField` matches
   `name.contains("expandclick")`, and dex sorts fields by name, so it always bound
   `mExpandClickListener` (#107) rather than `mOnExpandClickListener` (#323). Zero occurrences of
   `LS silent expand` / `onExpandClicked BLOCKED` in any trace.
6. **`onVisibilityAggregated` binds 0 methods** (inherited, not declared) — ruled out as a trigger,
   and proof that silent `hookAllMethods` failure is a live hazard on this class. Always log the
   bind count.
7. **`setSystemExpanded` fires on shade open**, not only on post — the old docs (§7.3 of
   `notifications-ar.md`) are wrong on this build.
8. **`resetUserExpansion()` fires twice for one logical reset** (~4 ms apart).
9. `adb shell` has **no root**; `run-as` is refused because the app makes its data dir
   world-readable. Hence the deep-link restart and the SET_PREF receiver.

## 7. Design decisions locked (with the user)

| Decision | Choice |
|---|---|
| Lock screen | Rewrite `allowOnKeyguard` argument; **no synthetic click on keyguard at all** |
| Shade | Rewrite the argument of `setSystemExpanded(false)` — SystemUI does the write itself |
| System aggregate children | Treat as **standalone and expand**; app-declared group children still defer to their summary |
| `resetUserExpansion` | Re-apply once, then back off (debounced) — never oscillate |
| Heads-up + swipe-toggle | **Frozen.** Not modified, not moved |
| Rollout | Pref-gated `notif_engine_v2`, legacy path retained for A/B |
| Test screen | Debug builds only |
| Never | Force size/height/visible-type; hook notification creation; name an OEM-only class as a hard dependency |

## 8. File map (new code)

```
hook/notif/RowApi.kt          reflection facade; resolves 14 handles once, logs `RowApi caps:`
hook/notif/ExpandPolicy.kt    PURE decision logic (no Android/Xposed) + RowFacts + Decision
hook/notif/RowStateStore.kt   per-notification-key state; wipe debounce + back-off
hook/notif/Attribution.kt     thread-local "this write is ours" token (re-entrancy + attribution)
hook/notif/NotifLog.kt        lambda-gated logging, zero allocation when off
hook/notif/NotifEngineV2.kt   the engine: LS gate, shade rewrite, cold triggers, anti-thrash
hook/NotifProbe.kt            read-only trigger census; pref-gated, ships disabled
TestNotifier.kt               7 test notification shapes + delay
TestingScreen.kt              debug-only screen: senders, engine toggle, probe toggles, restart
RootShell.kt                  one-shot su for the APP process only
src/debug/…/DebugPrefReceiver.kt   host-driven pref control; absent from release
```

`NotificationExpander.kt` keeps heads-up + swipe-toggle unchanged; its two legacy shade/LS drivers
early-return via `useV2()`.

## 9. Open items

1. **Visual verification (§5)** — the gate for everything else.
2. Remove the legacy shade/LS path and the dead `clickExpandSilentlyOnKeyguard` machinery once v2 is
   confirmed; bump `versionCode`/`versionName`.
3. **Verify on a non-OPlus ROM** (Xiaomi). Ask for the single `RowApi caps:` log line — that is the
   whole compatibility report. If `isExpandedArg=0` the lock-screen gate simply doesn't engage.
4. `ungroup_notifications_enabled` is still a **dead pref** (UI toggle, no hook reads it). Given
   finding 4 it may become the user-facing switch for aggregate-bundle behaviour.
5. Correct `docs/notifications-ar.md` §7.2/§7.3 — its claims about `setUserExpanded` not sticking
   and `setSystemExpanded` firing only on post are wrong for this build.
6. ProGuard: debug builds also minify (`build.gradle.kts:48`); watch for reflection-facing rules if
   anything breaks only in an installed build.

## 10. Traces kept in the repo

`docs/trace-baseline-3.2.1.log` (old engine), `docs/trace-shade-probe.log`,
`docs/trace-lockscreen-probe.log` (old engine on LS, the oscillation),
`docs/trace-v2-lockscreen.log` (v2 on LS). `docs/notif-engine-v2-findings.md` is the full evidence
log; `docs/notifications-ar.md` + `docs/notifications-architecture.drawio` describe the *old*
architecture.

## 11. Grouped notifications (added after the v2 engine)

Two toggles, in **Notifications → Grouped notifications**:

| Pref | Default | Effect |
|---|---|---|
| `expand_group_parents_enabled` | **ON** | Every group summary opens itself in shade and on LS |
| `expand_group_children_enabled` | **OFF** | Inside an open group, expand each row too |

They are independent — children-only means "when *I* open a group, its rows come out expanded".

**The primitives, and why these and not others.** Read out of this device's dex, not guessed:

- **Parent** — `setUserExpanded(true, allowChildExpansion = true)`. For a summary this branches to
  `GroupExpansionManager.setGroupExpanded(entry, true)` and **returns** before it writes
  `mUserExpanded`, `mHasUserChangedExpansion` or any height. It is exactly what the arrow does.
  `setChildrenExpanded` remains forbidden — it drives the container directly and breaks the look.
  SystemUI's own `shouldShowPublic()` sits in front of that branch, so a redacted group never opens.
- **Child** — the ordinary `setSystemExpanded` path, gated on `isGroupExpanded()`. While a group is
  closed the container sizes itself from each child's intrinsic height, so expanding children then
  would inflate the collapsed preview.
- **Trigger for summaries** — the *after* phase of `setSystemExpanded`. It fires on the parent every
  time the shade renders it (this is the legacy engine's trigger, re-used). The *before*-phase
  argument rewrite now **skips summaries**: setting `mIsSystemExpanded` on a summary expands its own
  content, not the group.

**Facts established — do not re-derive:**

10. **The arrow does not reach the group through `setUserExpanded` on this ROM.**
    `ExpandableNotificationRow$1.onClick` calls `GroupExpansionManagerImpl.setGroupExpanded`
    directly. Hooking the row's setter observes nothing. Full caller census of that manager method:

    | caller | expanded | meaning |
    |---|---|---|
    | `ExpandableNotificationRow$1.onClick` | toggled | the expand arrow — **the user** |
    | `GroupExpansionManagerImpl.collapseGroups` | false | bulk reset when the shade closes |
    | `GroupExpansionManagerImpl$$…Lambda0.onBeforeRenderList` | false | summary left the list |
    | `NotificationRemoteInputManager.activateRemoteInput` | true | inline reply opened it |
    | `StatusBarRemoteInputCallback$$…Lambda0.run` | true | inline reply, deferred |

    Both non-user collapses originate *inside* the manager, so a manager frame short-circuits
    the initiator test. The test is otherwise **positive**: the stack must contain a frame that
    *is* the gesture — `onClick`, `onExpandClicked`, `performExpansion`, `setUserExpanded`. The
    first two are interface overrides and so survive R8, unlike the classes holding them
    (`$1`, `$$ExternalSynthetic…`), which are R8 output and change between builds.
14. **"Any row frame on the stack" is NOT a valid test for "the user did this."** Entering the
    keyguard calls `ExpandableNotificationRow.setHideSensitive`, which collapses the group and
    therefore leaves a row frame on the stack. The loose test marked every bundle as
    user-collapsed the first time the screen locked, and it then stayed shut for good
    (`skip=UserCollapsed`) — the exact failure the collapse memory exists to prevent. Symptom:
    notifications arrive expanded on the lock screen, but after one lock/unlock cycle come back
    as a collapsed bundle and never reopen. Rejected collapses now log their frame
    (`v2 group system collapse ignored`), so a new ROM path is one log line away.
11. **SystemUI keeps no record that a group collapse was deliberate** — the group branch of
    `setUserExpanded` returns before writing `mHasUserChangedExpansion`. Hence
    `RowStateStore.groupCollapsedByUser`. Without it the parent toggle reopens every group the user
    just closed, on the very next render.
12. **`isGroupExpanded` is named `isGroupExpanded$1`** on this build (R8; a synthetic accessor owns
    the plain name). `RowApi` tries both.
13. **`setSystemChildExpanded` has exactly one caller left** — `removeNotification`, setting false.
    AOSP's `updateExpansionStates()` is gone under the async-group-header-inflation flag, so
    `mIsSystemChildExpanded` is effectively unowned and nothing fights a write to it.

15. **`isChildInGroup()` / `isSummaryWithChildren()` are view state and are FALSE on the pass that
    matters.** They only flip once the row is attached under its summary, which is after the
    engine first reconciles it. Classify from the `StatusBarNotification` instead — it is correct
    from the first frame:
    - `FLAG_GROUP_SUMMARY` (0x200) on `sbn.getNotification().flags` → this row is a summary.
    - group key carries `|g:` → grouped; otherwise standalone. On OxygenOS 16 a single's group key
      is **its own key** (`0|pkg|2000|null|10441`), not the AOSP `c:` form — so test for the
      presence of `|g:`, whose failure mode is "treat as single", the existing behaviour.

    Symptom when this is got wrong: an app group's children render as separate fully-expanded
    cards scattered through the shade and the summary's own content is expanded too. Almost
    certainly also the real story behind the old engine's ~10% failure rate (finding 4).
16. **A summary must be held back until its children attach** (`skip=GroupNotReady`).
    `setUserExpanded(true, true)` only reaches its group branch when `mIsSummaryWithChildren` is
    set; before that it falls through to the single path and expands the summary's own content.

**Verified on device** (CPH2747, OxygenOS 16 — screenshots + `AENotif` traces):
groups open in shade and with `kg=true`; children follow their toggle and render natively (image
previews, action buttons) with no forced sizing; a manual collapse survives shade close/reopen
(`skip=UserCollapsed`) while *other* groups still open; reopening by hand clears the memory
(`group user expanded=true`); three shade cycles produce one `EXPAND_GROUP` per group with no
back-off and no oscillation.

**Lock screen is covered too.** `input keyevent 224` (WAKEUP) auto-dismisses — the paired OnePlus
Watch is a trusted device — but **`input keyevent 26` twice** (off, then on) lands on the real lock
screen with the unlocked padlock, which is the state that matters. Confirmed there: two lock/unlock
cycles leave the `g:Aggregate_AlertingSection` bundle open with all three rows expanded.

**Aggregate bundles interact with the parent toggle.** Three notifications from one app get
auto-grouped into `g:Aggregate_AlertingSection` with an `AUTOGROUP_SUMMARY` row. Before the bundle
forms they are separate rows and expand as singles; once it forms, the *summary* is what has to be
opened, and its children then expand as singles anyway (`isSystemAggregateChild` bypasses the
children toggle by design, §7). So the end state matches either way — which is why a bundle that
fails to open looks like "they collapsed themselves after a while".

Tapping the arrow on a **collapsed** group on the lock screen opens the locked shade — that is
SystemUI's own handler (`goToLockedShade`), not us, and §7 keeps it that way. With the parent
toggle working there is nothing to tap.

Note the engine never force-collapses anything, so turning a toggle **off** does not un-expand rows
that are already expanded — it stops new writes. Restart SystemUI for a clean read.

**Full test-shape sweep** (`POST_TEST`, shade, one shape at a time) — all pass:

| Shape | Expected | Result |
|---|---|---|
| BigText | all four body lines | ✅ |
| Messaging | all three messages | ✅ |
| Inbox | all five lines | ✅ |
| LongText | expanded; the system's own clamp shows 10 of 14 in the shade | ✅ (not our clamp — `headsup_max_lines` is heads-up only) |
| Silent | expanded in the Silent section, no banner | ✅ |
| AutoGroupFlood | five singles expanded; aggregate summary opens once ready | ✅ |
| Group | children stay one-line; summary `GroupNotReady` → `EXPAND_GROUP` → `already` | ✅ |

**Confirmed NOT ours:** a two-child app-declared group renders flattened, with its children as
separate top-level rows and no summary. It does exactly the same with
`disable_headsup_hooks_enabled = 1` and every expand pref off — i.e. with no hook of ours running —
so it is stock OxygenOS 16. Do not "fix" it. `ungroup_notifications_enabled` is still a dead pref
and is not involved.

---

## 12. Two field bugs from daily use (2026-08-18) — both fixed

Reported after two days of real use, with screenshots. Both were reproduced on the device,
A/B-confirmed against the toggles, and fixed. Device: CPH2747, OxygenOS 16, debug build of
`feat/notif-engine-v2`.

### 12.1 Rows painted over each other on the lock screen

**Symptom.** With ~20 notifications from several apps on the lock screen, rows overlapped —
one notification drawn on top of the one above and the one below.

**Repro (deterministic).** Wake to the lock screen, then post 13+ notifications
(`cmd notification post`, any app) so they auto-group. The bundle opens and the rows overlap.
Same flood with `expand_group_parents_enabled = 0`: a clean collapsed bundle, no overlap.
Two notifications is not enough — the lock screen has to be tight for space.

**Cause.** An open group is a **shade-only shape**. SystemUI never draws one on the keyguard by
any path of its own: tapping the arrow on a collapsed group there runs `goToLockedShade`. So
`NotificationStackSizeCalculator.getSpaceNeeded` (which reads
`ExpandableView.getHeightWithoutLockscreenConstraints`, verified in this device's dex) budgets a
*collapsed* summary, and a group opened behind its back draws taller than its slot.

**Fix.** `ExpandPolicy` skips a summary when `onKeyguard` → `skip=GroupOnKeyguard`. The group
opens on the next shade render instead. Singles still expand in place on the lock screen — that
path is untouched and was re-verified.

### 12.2 A row showing one line of text inside a full-size card

**Symptom.** Rows in an open group rendered as a full-size card holding a single line of
"sender: message" at the top with blank space below — no icon, no timestamp, no expand arrow.
Only some rows, and it healed itself later, which is why it looked random.

**Cause — the two halves, both read out of this device's dex:**

17. **`NotificationContentView.getVisualTypeForHeight` returns `VISIBLE_TYPE_SINGLELINE` (3) for
    any child whose group reads closed** — checked *before* it looks at a height at all
    (`mIsChildInGroup && !isGroupExpanded$1() && mSingleLineView != null && !mUserExpanding &&
    !rowEx.isChildrenExpandedAnimating()`). And `calculateVisibleType()` otherwise just calls
    `getVisualTypeForHeight(min(mContentHeight, getIntrinsicHeight()))`.
18. **`ExpandableNotificationRow.getIntrinsicHeight()` sizes a child of an *open* group from its
    contracted layout** (`isChildInGroup()` → group open → `isExpanded(true) ? getMaxExpandHeight()
    : getShowingLayout().getMinHeight(false)`), and only returns `mPrivateLayout.getMinHeight()`
    (the one-line height) while the group reads closed.

So a child that never re-ran its layout selection after the group opened keeps one line of content
inside a card sized for three. The engine opens groups from inside SystemUI's own callbacks, which
run during a layout traversal, where a `requestLayout()` is dropped for the current pass.

**Fix.** After every reconcile of a summary whose group reads open, any attached child still
showing `VISIBLE_TYPE_SINGLELINE` — *and* whose own `isGroupExpanded()` is true, so the repair
converges — gets `NotificationContentView.selectLayout(animate = false, force = true)`.
That is SystemUI's own "work out what to show" primitive: it sets no height and no expansion
state, and on a correct row it recomputes the same type and does nothing.

**Measured.** 24 children repaired across one keyguard→shade transition — the same moment the
reported screenshots were taken — 6 on a second lock/unlock cycle, and **0 across three ordinary
shade open/close cycles**. So the stale state is specific to the keyguard transition and the
repair does not churn.

### 12.3 What the stock shapes look like (so a screenshot can be read at a glance)

| State | Look |
|---|---|
| Group **collapsed** | *one* card, count badge on the icon, 2–3 one-line children inside, **no** section header |
| Group **open** | section header (`WhatsApp ^`) + each child its **own** card with icon, timestamp, arrow |
| The bug | section header + own cards, but children still drawing the **one-line** layout |

### 12.4 Debug notes

- `RowApi caps:` now ends with `content=1 visType=1 selectLayout=1` — the three handles the repair
  needs. Zero there means the repair is a no-op on that ROM, nothing else changes.
- `skip=` lines now carry `kg= grpExp= exp=`, so a wrong skip is diagnosable from the log alone.
- The probe snapshot gained `actualH= grpExp= visType=` — `visType=3` with `grpExp=true` is
  exactly the 12.2 state.
- `cmd notification post` has **no cancel**, and `pm clear` / `pm revoke` are refused for
  `com.android.shell`, so flood tests leave their notifications behind. Clear them by collapsing
  the **Shell** section in the shade and swiping the collapsed bundle away.
