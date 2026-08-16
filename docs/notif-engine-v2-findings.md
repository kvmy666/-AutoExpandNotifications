# Notification engine v2 — evidence log

Device: **CPH2747**, Android 16 / SDK 36, **OxygenOS `CPH2747_16.0.9.400 (EX01)`**, built 2026-07-01.
Baseline trace: `docs/trace-baseline-3.2.1.log` (engine as shipped in 3.2.1, before any v2 code).

Everything below is measured, not inferred. Static facts come from `dexdump` of the **live**
`/system_ext/priv-app/SystemUI/SystemUI.apk` pulled from this device; runtime facts come from a
filtered logcat stream during scripted interaction.

---

## A. Static — `ExpandableNotificationRow.isExpanded(Z)Z` on the shipping build

Re-verified against the July build; **logically identical to the April dump**. Argument is `allowOnKeyguard`.

```
0000  isPromotedOngoing()                         -> branch
...normal path from 001f...
0021  mOnKeyguard
0023  mLockscreenShadeTransitionController.isOnOrGoingKeyguard(mOnKeyguard)  -> v0
0027  shouldShowPublic()                          -> v2
002b  if (v2 != 0) return false                   <-- PRIVACY CHECK, evaluated FIRST
002d  if (v0 == 0) goto 0031                      (not on keyguard -> skip gate)
002f  if (allowOnKeyguard == 0) return false      <-- HARD KEYGUARD GATE
0031  if (mHasUserChangedExpansion != 0) goto 003d
0035  if (mIsSystemExpanded != 0) return true
0039  if (mIsSystemChildExpanded != 0) return true
003d  return mUserExpanded
```

Three consequences, all load-bearing for the design:

1. **On keyguard, no state value can make a row expand.** `mUserExpanded` / `mIsSystemExpanded` are
   never reached. The only escapes are `allowOnKeyguard == true`, `mOnKeyguard == false`, or
   `isOnOrGoingKeyguard()` going false — the last of which is exactly what `goToLockedShade` does.
   **So lock-screen expansion has never worked by expanding; it worked by opening the locked shade.**
2. `shouldShowPublic()` is checked **before** the gate. Rewriting the *argument* preserves redaction;
   forcing the *return value* would render sensitive notifications expanded on the lock screen.
3. The tail (`mHasUserChangedExpansion` / `mUserExpanded`) is also below the gate, so argument-rewrite
   preserves manual collapse, while return-forcing would make collapse impossible.

Related confirmed API: `isShowingExpanded()` → calls `isExpanded(false)` (so it lies on keyguard);
`areChildrenExpanded()` → plain `mChildrenExpanded` field read (honest on keyguard);
`getIntrinsicHeight()` → consults `isExpanded` then picks `getMaxExpandHeight()`/`getCollapsedHeight()`
**itself** — i.e. driving `isExpanded` makes SystemUI compute every dimension;
`setUserExpanded(Z,Z)` → sets `mHasUserChangedExpansion = true` and `mUserExpanded`.

## B. Static — the existing lock-screen mitigation is dead code

`getExpandClickListenerField()` matches on `name.lowercase().contains("expandclick")`. Dex sorts fields
by name and the class declares both:

| field index | name |
|---|---|
| 107 | `mExpandClickListener` — the row's own `View.OnClickListener` |
| 323 | `mOnExpandClickListener` — the `CentralSurfaces` callback we actually wanted |

`mexpandclicklistener` contains `expandclick`, so the walk **always binds #107**. Nulling it has no
effect (the listener was already attached to the button at inflation), and `ensureOnExpandClickedHook`
is then handed a `View.OnClickListener` whose class has no `onExpandClicked` — `hookAllMethods` returns
empty *silently* while `onExpandClickedHooked = true` latches permanently.

Confirmed at runtime: **`LS silent expand` = 0 occurrences, `onExpandClicked BLOCKED` = 0 occurrences**
across the whole session. Both layers of the "defence in depth" have never run.

## C. Runtime — which driver actually does the work

Occurrences across the baseline session (notification posts, lock/unlock, shade swipes):

| Driver | log string | count |
|---|---|---|
| `setSystemExpanded` afterHook | `shade/LS post-expand` | **50** |
| `onLayout` afterHook | `onLayout expand` | **8** |
| HUD group path | `expandGroupIfNeeded` | 0 |
| Keyguard silent expand | `LS silent expand` | 0 |

Notes:

- **`setSystemExpanded` fires on shade open**, not only on post/set-change as `docs/notifications-ar.md`
  §7.3 claims. A shade swipe with no new notification produced an 8-row burst of `post-expand`.
- The `onLayout` driver *is* installed (`onLayout` is declared on the row class, verified statically)
  but is heavily suppressed by the shared `aeAutoExpanded` one-shot that `setSystemExpanded` consumes
  first in the same frame. It is a distant second contributor, not the "universal driver" the docs describe.
- Of its 8 fires, **2 read `collapsed=false`** and correctly skipped; 6 read `collapsed=true` and clicked.
  So on this build the toggle-an-already-expanded-row hazard did not materialise in this sample —
  the more likely shade failure mode is the *opposite*: a system collapse with no re-apply.
- `group=false` on **every** row observed, from every app. Consistent with grouping being gone on this build.

## D. Runtime — environment / tooling facts

- `adb shell` has **no root** (`su: inaccessible or not found`), so SystemUI cannot be restarted from
  the host. That is what the in-app Restart button and the Termux fallback are for.
- `adb shell settings put global …` **does** work, so prefs can be patched from the host.
- `cmd notification post` works and supports `bigtext`, `inbox`, `messaging`, `bigpicture`, `media` —
  enough to drive most of the matrix without touching the phone.
- `cmd statusbar expand-notifications` is a **no-op on OxygenOS**; use `input swipe 540 5 540 1600 250`.
- Git Bash mangles remote paths — prefix adb commands with `MSYS_NO_PATHCONV=1`.
- The device re-locks lazily: powering the screen off and waking within the "lock after screen off"
  window leaves it **authenticated but showing the lock screen** — precisely the bug-2 state.

## E. Runtime — hook install census (P0)

Probe logs the bind count for every candidate trigger. On this build:

| method | methods bound |
|---|---|
| `onLayout` · `onMeasure` · `onAttachedToWindow` | 1 each |
| `setSystemExpanded` · `setChildrenExpanded` · `onNotificationUpdated` | 1 each |
| `resetUserExpansion` · `onExpansionChanged` · `setHeadsUp` · `setPinnedStatus` | 1 each |
| **`onVisibilityAggregated`** | **0 — inherited, not declared** |

So `hookAllMethods` silently binding nothing is a live hazard on this class, and
`onVisibilityAggregated` is ruled out as a trigger. `onLayout` *is* bound (1 method), so the
shipped engine's driver is installed — its rarity is caused by the `isShown` guard and the
shared one-shot, not by a failed hook.

## F. Grouping was NOT removed — it was replaced by system aggregation ⚠️

The single most consequential finding, and it contradicts the working assumption for this rebuild.

Android 16 / OxygenOS 16.0.9.400 bundles notifications into **system-created aggregate sections**.
Observed group keys, live:

| groupKey | count |
|---|---|
| `g:Aggregate_AlertingSection` (incl. a real summary row, `summaryFlag=true`) | 26 |
| `g:Aggregate_SilentSection` | 3 |
| app-declared groups (`g:incoming_message_group_key`, `g:prayer`, `g:…::SUMMARY::wx`, …) | rest |

Consequences:

1. **App-declared grouping still exists**, so the legacy group path stays necessary.
2. **A new bundle layer sits on top**: individual notifications become **children of a system
   aggregate summary**. `isChildInGroup()` returned **true for 13 of 58** observations.
3. The shipped engine's shade *and* lock-screen drivers both begin with
   `if (isChildInGroup()) return` — "the parent drives its children". With aggregation, the
   "parent" is a system bundle that never expands them, **so those rows are silently skipped**.
4. Whether a given notification is skipped depends on *when* our hook fires relative to the system
   re-parenting it into the bundle. The trace shows a row that is `child=false` at
   `onAttachedToWindow` and `child=true` a moment later at `setSystemExpanded`. **That race is the
   most credible explanation of the ~10% "sometimes it just doesn't expand / collapses" rate.**

## G. Lock screen — the loop, measured

Full state table for one notification posted while locked (`h` is `getIntrinsicHeight()`;
169 = collapsed, 245 = expanded):

| trigger | userExp | userChanged | isExp(F) | isExp(T) | showingExp | h |
|---|---|---|---|---|---|---|
| `onAttachedToWindow` | false | false | false | false | false | 0 |
| `setOnKeyguard(true)` | false | false | false | false | false | 0 |
| `onLayout` | false | false | false | false | false | 169 |
| *our* `setUserExpanded(true,true)` | → true | → true | **false** | false | false | 169 |
| `onExpansionChanged(true,false)` | true | true | **false** | false | false | 169 |
| **`resetUserExpansion`** | **false** | **false** | false | false | false | 169 |
| *our* `setUserExpanded(true,true)` | true | true | **false** | **true** | false | **245** |
| `setSystemExpanded(false)` | true | true | **false** | false | false | **169** |

Four facts, all confirmed at runtime:

1. **`isExpanded(false)` is `false` on every single sample**, including when `mUserExpanded` is
   true. The keyguard gate from section A, observed live.
2. **`isExpanded(true)` is the honest read**, and `getIntrinsicHeight()` tracks it exactly
   (169 ↔ 245). This is the direct proof that rewriting `allowOnKeyguard` is sufficient *and*
   that SystemUI computes the height itself — no size forcing required.
3. **`isShowingExpanded()` is `false` throughout**, so the shipped engine's idempotence guard is
   blind on the lock screen — it can never tell "already expanded" from "collapsed".
4. **`resetUserExpansion()` fires on the lock screen and wipes our expansion**, after which the
   driver re-applies. Five `setUserExpanded(true,true)` calls for a single notification, with `h`
   oscillating 169 → 245 → 169 → 245 → 169. **That oscillation is the "forced expansion" the user
   reports** — the row visibly flickers and cannot be kept collapsed.

## H. Tooling win: root-free remote SystemUI restart

`adb shell` has no root, but the user's status-bar zone shortcut is an Anywhere deep link, and
`ActionDispatcher.launchShortcut` fires it as a plain intent. So it can be triggered from the host:

```
adb shell 'am start -a android.intent.action.VIEW -d "anywhere://open?sid=1776272750274"'
```

Verified: SystemUI pid 2290 → 9974. This gives a complete edit → build → install → reload → capture
loop with no physical interaction.

The probe pref can likewise be flipped from the host by patching the published prefs blob
(decode `settings get global ae_prefs_json`, set `notif_probe_enabled`, re-encode, `settings put`).

## I. v2 engine — measured result

Engine installed and verified on device (`RowApi caps:` all 14 handles resolved, `lsGate=true`,
legacy driver stands down).

**Lock screen, notification posted while locked:**

| trigger | userExp | isExp(T) | public | h |
|---|---|---|---|---|
| `onLayout` | true | true | false | **305** |
| `setSystemExpanded(false)` | true | false | **true** | 169 |
| `setOnKeyguard(true)` | true | false | **true** | 169 |
| `setSystemExpanded(false)` | true | true | false | **305** |

`h=305` on the lock screen is expansion **in place** — the old engine could only ever reach that
by transitioning to the locked shade. Decision counts for one notification: one `EXPAND`, the
rest `already` / `rewritten`, i.e. one real write instead of the old engine's five, and no
`performClick` anywhere, so the locked-shade transition can no longer be triggered by us.

**`shouldShowPublic()` explains the remaining 305↔169 movement, and it is correct behaviour.**
`public=true` correlates perfectly with `isExp(T)=false` and `h=169` across every sample. That is
the redacted lock-screen view, which has no expanded variant. The row flips as the device moves
between authenticated and not — the "unlocked but still on the lock screen" state.

This is also the strongest possible validation of the argument-rewrite decision: because
`shouldShowPublic()` is evaluated *before* the keyguard gate, our rewrite **cannot** expose
redacted content. Had we forced the return value instead — the original plan — every one of those
`public=true` rows would have rendered expanded with sensitive content on the lock screen.

### Two bugs found in the v2 engine itself and fixed

1. **Back-off tripped instantly.** `resetUserExpansion` fires twice for one logical reset
   (measured 4 ms apart), so a single reset counted as two wipes. Added a 250 ms debounce.
2. **Backing off collapsed the row.** The back-off path cleared the lock-screen token, which
   stopped the gate rewriting and actively collapsed an already-open row. "Stop writing" must not
   mean "un-expand" — `Decision.Reason.clearsLockscreenToken` now excludes `BackedOff`.

### Engine-selection bug found in the shipped code

`prefs.isOptInEnabled(...)` read from `install()` (i.e. from `handleLoadPackage`) always falls
back to `XSharedPreferences`, because the `Application.onCreate` hook that captures a context and
calls `loadFilePrefs()` has not run yet. Engine selection is now resolved lazily on first hook
invocation, and v2 installs from the `Application.onCreate` hook.

## J. Still to measure

1. Does `mIsSystemExpanded` survive shade close → re-open? Decides whether any per-open re-apply is
   needed at all, or whether the `onLayout` driver can simply be deleted.
2. Full truth table on the lock screen: `isExpanded(false)` vs `isExpanded(true)` vs `isShowingExpanded()`,
   authenticated vs not.
3. Who calls `resetUserExpansion()` / `setUserExpanded(false)` when a shade row collapses "by itself" —
   the stack-trace capture in `NotifProbe` answers this directly.
4. Whether LS height clamping (`mSaveSpaceOnLockscreen`, `setIgnoreLockscreenConstraints`) applies once
   the row does report expanded.
