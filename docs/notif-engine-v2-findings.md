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

## E. Still to measure (needs the probe, which needs a SystemUI restart)

1. Does `mIsSystemExpanded` survive shade close → re-open? Decides whether any per-open re-apply is
   needed at all, or whether the `onLayout` driver can simply be deleted.
2. Full truth table on the lock screen: `isExpanded(false)` vs `isExpanded(true)` vs `isShowingExpanded()`,
   authenticated vs not.
3. Who calls `resetUserExpansion()` / `setUserExpanded(false)` when a shade row collapses "by itself" —
   the stack-trace capture in `NotifProbe` answers this directly.
4. Whether LS height clamping (`mSaveSpaceOnLockscreen`, `setIgnoreLockscreenConstraints`) applies once
   the row does report expanded.
