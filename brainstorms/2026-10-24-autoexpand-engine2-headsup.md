# AutoExpandNotifications v4 — Engine 1/2 + Heads-Up bugs: Brainstorm / Discovery Notes

Date: 2026-10-24 · Goal: align on the v4 dual-engine architecture, the exact heads-up behavior, and the automated test strategy before any code is written.

## Summary / key decisions

- **Engine mapping (corrected by user, 2026-10-24):** Engine 1 = the existing legacy `NotificationExpander` path (`notif_engine_v2 = 0`). It works on the **old Android/ROM version** and is shipped for users who are still there. It **does not work on the user's new version**, so on the new ROM Engine 2 (`NotifEngineV2`, `notif_engine_v2 = 1`) is the only usable engine. The two engines are therefore **ROM-era strategies**, not two equally valid choices on one device.
- **What this does to the A/B result:** "v1 clean" cannot be treated as a correct-behavior baseline on the new ROM — v1 may be inert there. The confirmed fact is narrower: the heads-up bugs appear on **Engine 2, the only engine that works on the new ROM**. The defect still points at v2's ungated heads-up writes, but v1 is not a same-device control.
- **Engine 1 on the new ROM (user recollection, tentative):** grouped notifications make the notification sound but **do not appear in heads-up at all**; they only appear in the shade. That means the legacy heads-up path is not a usable fallback on the new ROM and cannot simply be shared with Engine 2.
- **Consequence for the fix:** Engine 2 has to own correct heads-up behavior on the new ROM. "Keep v2 out of heads-up and use v1's HU" is not viable there, because v1's HU is what fails to show groups.
- **Leading cause (code):** `reconcile()` runs `repairChildLayouts()` for a summary whose group is open even when the decision is `Skip(HeadsUp)`. `repairChildLayouts()` is the only v2 write not gated by `isHeadsUp`; it calls `selectLayout(false, true)` on children during the HUD layout pass.
- **Product direction (user):** keep both engines, fix Engine 2, and add a user-facing toggle between them. Engine 1 must remain untouched/known-good.
- **Architecture decision (Q4): full ownership per engine.** Engine 1 stays the untouched legacy stack for old ROMs. Engine 2 owns the complete new-ROM pipeline: heads-up + shade + lock screen. The toggle selects one complete engine, never a mix. This is the v4 strategy boundary: no shared HU path between the engines.
- **Selection decision (Q5): auto default + manual override.** Android 16+ defaults to Engine 2; older versions default to Engine 1. Settings exposes the resolved choice and an override, plus an Apply & Restart SystemUI step.
- **Not yet verified on device:** no device is currently connected to adb, so the exact `selectLayout`/reconcile sequence has not been traced in logcat. The A/B result and Engine 1's new-ROM behavior are user-reported.

## Step 1 — Codebase and Git findings

### What exists today

- **v1 — `hook/NotificationExpander.kt`** (legacy, always installed). Expansion is **click simulation**: find the real `android:expand_button` / `NotificationExpandButton` and `performClick()` it, or fall back to `setUserExpanded(true, true)`.
  - Shade/LS drivers: `setSystemExpanded(false)` after-hook (deferred `post{}`, one-shot `aeAutoExpanded`, 300 ms quiet gap) and `onLayout` after-hook.
  - Keyguard "silent click" mitigation is **dead code**: `getExpandClickListenerField()` matches `name.contains("expandclick")`, which always binds `mExpandClickListener` (#107), not the intended `mOnExpandClickListener` (#323).
  - Heads-up path is also here and is **shared by both engines**.
- **v2 — `hook/notif/NotifEngineV2.kt` + `ExpandPolicy`/`RowApi`/`RowStateStore`/`Attribution`** (opt-in via `notif_engine_v2`, installed from `Application.onCreate`). State-driven, no clicks:
  - Shade: rewrite the argument of `setSystemExpanded(false)` to `true`.
  - Lock screen: rewrite `allowOnKeyguard` in `isExpanded(boolean)` for rows holding the per-row token. Never rewrites the return value (preserves `shouldShowPublic()` redaction and the `mUserExpanded` tail).
  - Cold triggers: `onAttachedToWindow`, `setOnKeyguard`, `onNotificationUpdated`, `resetUserExpansion`.
  - Groups: summary opens via `setUserExpanded(true, true)`; children expand via the ordinary path; classification from the SBN (`FLAG_GROUP_SUMMARY`, `|g:`) and pipeline (`GroupEntry` with a summary), not the view flags.
  - Group parents default **ON** (`expand_group_parents_enabled`); children default **OFF**.
- **Heads-up is not owned by v2.** It is the legacy path in `NotificationExpander.kt`, and it is the path both reported bugs live in.

### Git history

- `3589d10` extracted `NotificationExpander` and created the heads-up group machinery (`expandGroupIfNeeded`, `aeIsGroup`); the symbols have not been removed since.
- `d9945b3` created v2 and added only a small `useV2()` gate to the legacy file.
- Every later `fix(notif)` group commit touched **only `NotifEngineV2.kt`**.
- Conclusion: **v2 did not replace v1.** They are parallel, and v2 only stands down the legacy shade/LS drivers. Heads-up stayed legacy.

### Terminology mismatch (important)

The handoff's **Engine 1 / Engine 2** are **grouping models** (legacy parent+children vs Android 16 split conversation parents).

The repo's **v1 / v2** are **expansion mechanisms** (click simulation vs state rewriting).

They are orthogonal. Bugs A and B are in the shared heads-up code regardless of which expansion engine is selected in Settings. This must be resolved before Phase 2's "dual-engine" switch can be designed.

### Current heads-up code path (`NotificationExpander.kt`)

| Hook | Behavior |
|---|---|
| `NotificationChildrenContainer.addNotification` after | For a heads-up parent: mark `aeIsGroup`, `setUserExpanded(true, true)` on the parent, collapse the newly attached child. Runs for every child attach. |
| `expandGroupIfNeeded(rowView, rowObj, pkg)` | If not `disable_headsup_hooks_enabled`: `setUserExpanded(true, true)` on the parent if not expanded, then `collapseChildNoAnim(child)` for every attached child. |
| `setHeadsUp(true)` after | If not already marked a group, calls `expandGroupIfNeeded`. |
| `setHeadsUp(false)` before | Resets `aeIsGroup` / `aeAutoExpanded`. |
| `calculateVisibleType` after | For heads-up (`result == 2`), if grouped it calls `expandGroupIfNeeded` as a recovery path; otherwise forces `result = 1` when not `aeCollapsed`. |
| `getIntrinsicHeight` / `getPinnedHeadsUpHeight` after | Force expanded height for heads-up singles only. |
| `setExpandedWhenPinned` before | Syncs `aeCollapsed` for non-group rows. |
| `StatusBarNotificationActivityStarter` + OPlus touch helper | Swipe-down on a HUD toggles expand/collapse instead of launching the app (`disable_headsup_popup_enabled`, default ON). |
| `MessagingTextMessage.setMaxDisplayedLines` | Clamps line count for heads-up and, since `1ff1756`, shade/LS when `max_lines_shade_enabled`. |

Relevant prefs: `expand_headsup_enabled` (default ON), `disable_headsup_hooks_enabled` (default OFF), `disable_headsup_popup_enabled` (default ON), `headsup_max_lines` (default 5), `expand_group_parents_enabled` (default ON), `expand_group_children_enabled` (default OFF), `notif_engine_v2` (opt-in).

### Unverified hypotheses for the two bugs

- **v2 leaks into heads-up (strong candidate, found in code):** `NotifEngineV2.reconcile()` always executes its post-decision block when `facts.isSummaryWithChildren && isGroupExpanded(row)`, *even when the decision is `Skip(HeadsUp)`*. It calls `repairChildLayouts()`, which iterates the attached children and calls `selectLayout(false, true)` on every child still showing `VISIBLE_TYPE_SINGLELINE`. A HUD summary whose group was opened by the legacy path therefore gets its children re-selected from inside SystemUI's heads-up layout pass. `reconcileChildren()` is gated by `groupChildrenEnabled`, but `repairChildLayouts()` is **not gated by `isHeadsUp`**. This is a v2-only write into the heads-up path and is the leading explanation for "v1 is fine, v2 flashes".
- **Bug A — sequential flashing (3rd new, then 2nd old, then 1st old):** a new child arriving in a group causes `addNotification`/`setHeadsUp(true)`/`calculateVisibleType` to run `expandGroupIfNeeded`, which expands the parent and then calls `setUserExpanded(false, false)` on **every attached child**. Because this runs inside SystemUI's heads-up layout pass, each child is likely being re-bound/re-measured in sequence, producing the 3→2→1 flash. The path has no "new notification vs update of an existing one" guard.
- **Bug B — old notifications re-pop while in-chat:** any update to a Telegram child may re-promote the group summary; `expandGroupIfNeeded` then re-expands the parent and touches all children, which can re-alert old rows. There is no foreground-chat / "same group, older child" suppression, and no check that the child being updated is the one that should peek.
- Both hypotheses need device evidence (logcat + `dumpsys`) before a fix is designed.

### Requirement conflict spotted

The handoff says groups **must not be forcibly expanded all at once by default** in Shade/LS. The current v2 default is `expand_group_parents_enabled = true`, which does exactly that. The target behavior and the current default disagree; this needs an explicit decision.

## Session notes

- 2026-10-24: user says **"so i built the v2"**.
- Clarified: **Engine 2 APK is built and installed on the new-ROM phone.** Device address: `192.168.100.245:6666`.
- No device was attached to adb at the last check; connecting now for evidence capture.

## Device evidence — 2026-10-24

- Device: **CPH2747**, Android **16 / SDK 36**, OTA `CPH2747_11.A.43_0430_202607012229`, SystemUI pid `8014`.
- Module: **versionCode 30305 / versionName 3.3.5** (newer than the CLAUDE.md 3.3.0 note).
- Live prefs (both `ae_prefs_json` and `/data/local/tmp/tweaks_prefs.json` agree):
  - `notif_engine_v2 = 1`
  - `expand_headsup_enabled = 1`
  - `disable_headsup_hooks_enabled = 0`
  - `disable_headsup_popup_enabled = 1`
  - `expand_group_parents_enabled = 1`
  - `expand_group_children_enabled = 0`
  - `notif_debug_logging = 1`
  - `notif_probe_enabled = 0`
- Active Telegram notification (one record):
  - `key = 0|org.telegram.messenger|1463792512|null|10384`
  - `channel = 0channel_private_b9fad_-2874144909636836607`
  - `shortcut = ndid_1463792512`
  - `category = msg`, `vis = PRIVATE`, `locId = LocusId[15 chars]`
  - `groupKey = 0|org.telegram.messenger|c:0channel_private_b9fad_...`
  - `number = 4` messages under `MessagingStyle`; `extras.isGroupConversation = false`
  - importance HIGH, `seen = false`, `flags = AUTO_CANCEL`
  - `dismissalId = tguser1463792512_63637`
- Interpretation: Telegram posts **one notification per conversation**, bundling multiple messages under `MessagingStyle`. The notification key/shortcut is per-chat, which gives us candidate conversation identities for active-chat matching (`shortcutId`, `LocusId`, or the `tguser…` dismissal id).

## Root-cause evidence — Bug A (device capture, 2026-10-24 21:20:57)

Repro: one old Telegram conversation in the shade, a new message arrives; the old row appeared in heads-up first, then the new row.

Exact sequence from `/tmp/ae_capture.log`:

| time | event |
|---|---|
| 21:20:57.288 | v2 `onNotificationUpdated already` on the **old** Telegram row (`1463792512`); no write. |
| 21:20:57.288 | legacy `TweaksHud setHeadsUp(true)` on the old row — **SystemUI is pinning it**. |
| 21:20:57.291 | legacy `expandGroupIfNeeded` → `count=0` → returns. |
| 21:20:57.296 | `setPinnedStatus(PinnedBySystem)` on the old row. |
| **21:20:57.298** | **v2 `onAttachedToWindow EXPAND` on key `-2|com.android.systemui|1|null|0`** — the HUD container row. Stack: `NotifEngineV2.applyExpanded` → `setUserExpanded(true,true)` (line 232) **and** `setUserExpanded(true)` (line 233). |
| 21:20:57.973 | `setHeadsUp(false)` old row — unpinned. |
| 21:20:57.978 | legacy `setHeadsUp(true)` on the **new** row (`241670288`); `calcVisibleType` forces expanded. |
| **21:20:57.982** | **v2 `onAttachedToWindow EXPAND` again on `-2|com.android.systemui|1|null|0`**, same two `setUserExpanded` calls. |
| 21:21:03.680 | new row `setHeadsUp(false)`. |

Two concrete defects:

1. **v2 reconciles the SystemUI HUD container.** Key `-2|com.android.systemui|1|null|0` is not a user notification, but `isHeadsUp` is false on it, so the policy treats it as an ordinary row and expands it inside the heads-up attach path. This is the v2-only write into heads-up that the earlier A/B pointed at.
2. **`applyExpanded` calls two/three setters.** The `?:` chain is not a fallback: `Method.invoke` returns `null` for `void`, so it always falls through. The capture shows both `setUserExpanded(true,true)` (line 232) and `setUserExpanded(true)` (line 233) executed for the same row.

The legacy `TweaksHud` hook only **reacted** to `setHeadsUp(true)`; it did not initiate the pin. The old row's initial pin at .288 comes from SystemUI's `HeadsUpManagerImpl.showNotification` / `onEntryAdded`. v2's HUD-container expansion lands immediately after and may be what keeps/replays the HUD sequence. The minimal test is to stop v2 from touching the container and see whether the old-row flash disappears.

### Candidate minimal fix (not implemented)

1. In `reconcile` and the `setSystemExpanded` hook, skip notifications that are not user/app notifications: `pkg == "com.android.systemui"` or key starting `-2|`.
2. Replace the `?:` chain in `applyExpanded` with an explicit `if/else` so exactly one setter runs.
3. Keep `isHeadsUp` skip as-is; add the package/key guard because the HUD container's `isHeadsUp` is false.
4. Rebuild, install, repeat the same Telegram repro, and compare the `AENotif`/`TweaksHud` sequence.

If the old row still flashes after this, the next layer is to veto `HeadsUpManagerImpl.showNotification` / `onEntryAdded` for entries that are not the newly updated conversation.

## Root-cause evidence — Bug B (device capture, 2026-10-24 21:29:58)

Repro: while inside Telegram chat A, person B sent a message; old rows flashed first, then the new one. The capture spans a SystemUI restart (pid 8014 → 28057), and the bug reproduces on the fresh pid.

| time | event |
|---|---|
| 21:29:58.478–.483 | SystemUI `setChildrenExpanded(false)` on the Telegram summary and both children — the group is being collapsed. |
| **21:29:58.496** | **v2 `onAttachedToWindow EXPAND` on old row `-1354278688`** — `child=false`, `grouped=false` at that instant. |
| **21:29:58.501/502** | **v2 `onAttachedToWindow EXPAND` on old row `241670288`** — same. |
| 21:29:58.607 | `v2 onNotificationUpdated already` on `-1354278688`. |
| 21:29:58.608 | legacy `setHeadsUp(true)` row=35244936 → old row pinned at .614. |
| 21:29:58.617/.618 | v2 expands the **HUD container** `-2|com.android.systemui|1|null|0` twice (triple setters). |
| 21:29:59.276 | old row unpinned. |
| 21:29:59.280 | legacy `setHeadsUp(true)` row=93632179 → second row pinned at .287. |
| 21:29:59.285/.286/.303/.304 | v2 expands the HUD container again (triple setters). |

Three distinct defects, all confirmed:

1. **`pipelineGroupedOf` returns `false` for a group in formation.** The parent is a non-root `GroupEntry` whose `getSummary()` is still `null`, so the code returns `false`. That is interpreted as "not a group child", overriding the `|g:` group-key fallback. The row is then expanded as a standalone single. It should return `null` (unknown) for a non-root `GroupEntry` without a summary, and reserve `false` for `GroupEntry.ROOT_ENTRY` (the top level). This preserves the Instagram lone-child fix while fixing the forming-group race.
2. **v2 reconciles the SystemUI HUD container** (`-2|com.android.systemui|1|null|0`). It is not a user notification; the policy should skip internal notifications whose key starts with `-2|`.
3. **`applyExpanded` calls multiple setters.** The `?:` chain never short-circuits for `void` methods. The capture shows `(true,true)`, `(true)` and `(true,false)` for the same row. Replace it with an explicit `if/else` so exactly one setter runs.

### Refined fix plan for Engine 2

1. `RowApi.bindPipeline`: resolve the static `ROOT_ENTRY` field on `GroupEntry` by type (not by obfuscated name).
2. `RowApi.pipelineGroupedOf`:
   - `ROOT_ENTRY` → `false`
   - `GroupEntry` with a summary → `true`
   - non-root `GroupEntry` without a summary → `null` (let the `|g:` fallback decide)
3. `ExpandPolicy`: add `Reason.Internal` and skip `key.startsWith("-2|")` (SystemUI/internal rows).
4. `NotifEngineV2.applyExpanded`: explicit `setSystemExpanded` → `setUserExpanded(2)` → `setUserExpanded(1)` fallback; one call only.
5. Rebuild, install, restart SystemUI, repeat Bug A and Bug B; compare `AENotif`/`TweaksHud`.

## Implementation — first Engine 2 fix (2026-10-24)

Applied on `feat/autoexpand-v4`, three files only (Engine 1 untouched):

- **`RowApi.kt`** — cached static `rootEntry` resolved by type; `pipelineGroupedOf` now returns `false` only for `ROOT_ENTRY`, `true` when a summary exists, and `null` for a non-root `GroupEntry` whose summary has not attached. `caps` logs `rootEntry=`.
- **`ExpandPolicy.kt`** — `RowFacts.INTERNAL_KEY_PREFIX = "-2|"`, new `Reason.Internal`, and a first-line skip for internal notifications.
- **`NotifEngineV2.kt`** — `applyExpanded` now calls exactly one setter via `when` (system → user2 → user1), not the old `?:` chain.

Build/install:

- Default JDK 25 is rejected by Gradle 8.13. Built with `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ANDROID_HOME=/home/abdulkarim/Android/Sdk ./gradlew :app:assembleDebug` → **BUILD SUCCESSFUL** (3m 48s).
- `local.properties` still contains a Windows SDK path; `ANDROID_HOME` is required on this Linux host.
- `adb install -r app/build/outputs/apk/debug/app-debug.apk` → **Success**.
- SystemUI not yet restarted at the time of writing; runtime verification pending.

## Verification 1 — first fix build (2026-10-24)

- Installed on `feat/autoexpand-v4`; SystemUI reloaded to pid `25682`; `RowApi caps` ends `rootEntry=1`.
- **Result: both bugs still flash.**
- The three v2 defects are fixed in code (classification, internal-row skip, single setter), but the old-row HUD promotion remains. Next question: is v2 now clean in the trace? If yes, the remaining cause is SystemUI's own HUD decision layer (`HeadsUpManagerImpl.showNotification` → `onEntryAdded` → `setEntryPinned`), and the fix must veto non-new entries there.

## User constraint — expansion mechanism (2026-10-24)

> "we dont change the notification behavior like rebuilding it, we just hit the system arrow to make it expand"

- The module must not reimplement notification content/grouping/HUD behavior.
- Expansion should be performed by **clicking the real system expand arrow** on the row, not by rewriting state (`setSystemExpanded` / `setUserExpanded`) or rebuilding the notification.
- This conflicts with the current Engine 2 `applyExpanded` state setters and with the proposed HUD veto. The first fix (classification + internal skip + single setter) still relies on state setters, so it does not satisfy this constraint.
- Open scope question: does the rule also forbid a heads-up veto for old rows, or only forbid rewriting the notification/expansion state?

## Decompiled SystemUI (REA/JADX, 2026-10-24)

- Artifact: `/tmp/SystemUI.apk`, SHA-256 `dbe67fd3688a5bb5ebe7200190aaaac0bfbf88068a1534b6aebd62cd3f588ff4`, OxygenOS 16 `CPH2747_11.A.43_0430_202607012229`.
- Tooling: REA's Android provider needs `REA_JADX_MCP_JAR` (not configured); used the pinned `jadx-headless-mcp 0.7.1` engine directly to decompile the five target classes under `/tmp/jadx-out/sources`.
- `ExpandableNotificationRow.mExpandClickListener` is the **real system arrow listener** (`AnonymousClass1.onClick`, decompiled lines 123–171):
  - summary: `GroupExpansionManagerImpl.setGroupExpanded(entryLegacy, !isGroupExpanded)`
  - single: `setUserExpanded(!isExpanded(false))`
  - both: `StatusBarNotificationPresenter.onExpandClicked(entryLegacy, newState)`
- `HeadsUpManagerImpl.showNotification(NotificationEntry, boolean)` is `void`; it defers the actual pin to `AvalancheController.update` and `HeadsUpManagerImpl$$ExternalSyntheticLambda6`. A HUD veto would have to skip the original call or hook `onEntryAdded` — it cannot use a return value.
- Oplus HUD decision layer: `OplusHeadsUpCoordinator.handlePostedEntry` → `bindForAsyncHeadsUp` → `oplusAvalanche.show` → `HeadsUpViewBinder.bindHeadsUpView` → `onHeadsUpViewBound`. `PostedEntry` exposes `wasAdded`, `shouldHeadsUpEver`, `shouldHeadsUpAgain`, `isHeadsUpAlready`.

### Arrow-first fix plan (aligned with the user's rule)

1. `RowApi`: resolve `mExpandClickListener` and add `clickExpand(row)` that calls `listener.onClick(row)` — the real system arrow path.
2. `NotifEngineV2`: replace `applyExpanded` / `applyGroupExpanded` / `applyCollapsed` state setters with `RowApi.clickExpand(row)`. No notification state is rewritten.
3. Install a suppressor on `StatusBarNotificationPresenter.onExpandClicked` that returns early when `Attribution.isOurs()`, so a programmatic arrow click does not open the locked shade on keyguard.
4. In `reconcile`, for cold triggers only (`onAttachedToWindow`, `onNotificationUpdated`), defer a row when `facts.belongsToGroup && !facts.isGroupChild && !facts.isGroupSummary` (`defer group-forming`). This is the pre-HUD expansion that made old Telegram rows pins.
5. Keep the existing `isExpanded(allowOnKeyguard)` gate for lock-screen visibility; keep the shade `setSystemExpanded` driver unchanged for now.
6. Rebuild, install, restart SystemUI, repeat both Telegram repros.

## Arrow-first implementation (2026-10-24)

- `RowApi`: cached `mExpandClickListener`; `clickExpand(row)` invokes the real system arrow listener. `caps` now reports `arrow=1/0`.
- `NotifEngineV2`: `applyExpanded` / `applyGroupExpanded` / `applyCollapsed` call `RowApi.clickExpand(row)` inside `Attribution.ours`; no `setSystemExpanded` / `setUserExpanded` remains in the apply paths.
- `reconcile`: cold triggers now defer when `facts.belongsToGroup && !facts.isGroupChild && !facts.isGroupSummary` (`v2 … defer group-forming`), preventing the pre-HUD expansion of forming-group children.
- `installExpandClickSuppressor`: hooks `StatusBarNotificationPresenter.onExpandClicked` and returns early only when `Attribution.isOurs()`, so a programmatic arrow click does not open the locked shade.
- Build: `BUILD SUCCESSFUL`, installed on the device. SystemUI not yet restarted.
- Known caveat: `applyCollapsed` on keyguard can toggle the wrong way because the arrow listener reads the gated `isExpanded(false)`; verify on lockscreen separately. Shade/HUD collapse is unaffected.

## Verification 2 — arrow-first build (2026-10-24)

- **Bug B (in-chat): fixed.**
- **Bug A: partially fixed.** The new notification arrived in the shade correctly, then a different row flashed afterward. In the shade, 3 messages plus the new one were grouped under "Telegram", but the message that arrived *after* the new one was **not** in that group — so its row is separate and is the one that flashed.
- This points at a second grouping race: a later message creates/updates a conversation row that is not a member of the forming `g:messages` group at attach time, so the cold-defer/group-child logic does not cover it. Need the trace to identify its key, group key, and which trigger expanded it.
- Open question: is the ungrouped row a Telegram conversation that arrived after the summary, or a system aggregate child that was re-parented after the HUD decision?

## Verification 3 direction — Engine 1 still acts under Engine 2

User hypothesis (2026-10-24): "maybe the bug from engine 1 act while engine 2 is on."

Trace evidence for it:
- Under Engine 2, the legacy `TweaksHud` hooks still fire: `setHeadsUp(true/false)`, `expandGroupIfNeeded ENTER`, `calcVisibleType result=2 willOverride=true`, and `setHeadsUp(false) … resetting aeIsGroup/aeAutoExpanded`.
- The remaining flash (`-1910818235`, 22:15:13.017–15.465) was **not** caused by a v2 arrow click; SystemUI promoted it right after the new row's `setHeadsUp(false)`.
- But Engine 1 was still mutating its HUD state (`aeIsGroup`, `aeCollapsed`) and forcing visible types while Engine 2 was selected. That violates the Q4 "full ownership per engine" decision.

Next experiment:
1. Gate **all** Engine 1 heads-up hooks on `useV2()`: `addNotification`, `calculateVisibleType`, `getIntrinsicHeight`, `getPinnedHeadsUpHeight`, `setHeadsUp`, `setExpandedWhenPinned`, and `expandGroupIfNeeded` itself. Keep `setMaxDisplayedLines` shared.
2. In Engine 2, add a `setHeadsUp(true)` handler that clicks the **real arrow** on the newest child only (tracked from `onNotificationUpdated`), and only if the row is not already expanded. Old rows that SystemUI re-pins must not be clicked.
3. Rebuild, install, restart, repeat both repros. If the old row still flashes after Engine 1 is fully out, the remaining behavior is SystemUI's own HUD queue and needs a separate decision.

## Verification 4 — full engine ownership (2026-10-24)

Implemented and installed:

- **Engine 1 heads-up is gated off under Engine 2.** `useV2()` now returns early from `addNotification`, `calculateVisibleType`, `getIntrinsicHeight`, `getPinnedHeadsUpHeight`, `setHeadsUp` (before and after), `setExpandedWhenPinned`, and `expandGroupIfNeeded`. The shared `setMaxDisplayedLines` hook remains.
- **Engine 2 owns HUD expansion.** A `setHeadsUp(true)` hook clicks the real arrow only for `newestChildKey` (the latest non-summary `onNotificationUpdated` key), only if the row is attached and not already expanded. Older rows that SystemUI re-pins are ignored.
- Build/install: `BUILD SUCCESSFUL`, `Success`; SystemUI reload pending.
- Expected log signature: `v2 HUD arrow hook installed`, then `v2 HUD arrow EXPAND key=<newest>` for the new message; no `TweaksHud setHeadsUp` / `calcVisibleType` / `expandGroupIfNeeded` lines under Engine 2.

## Verification 5 — full ownership result (2026-10-24)

- Engine 1 HU is fully silent; Engine 2's HUD arrow hook is installed.
- User result: the new notification arrived, **with another old one behind it**. The old one was **outside the Telegram group** and was **expanded because it was treated as a single**.
- This is not the HUD queue re-pinning a group child; it is a **grouping-membership/cold-trigger** problem. The old notification is not in `g:messages` at that moment, so `belongsToGroup` is false, the group-forming defer does not fire, and v2 expands it as a standalone single.
- Next: identify that key and its `grp=` / `pipeGrp=` / `isGroupChild` values from the run. Then gate cold-trigger expansion to the **newest** child only, so an older ungrouped row cannot be expanded by a re-attach/update.

## Function → log → value map (2026-10-24)

### Heads-up
| Function | Engine | Writes | Log | Values |
|---|---|---|---|---|
| `NotificationChildrenContainer.addNotification` | E1 | parent expand + child collapse | `TweaksHud addNotification` | `parentHU`, `onKG`, `childIdx`, `childParentRef/match`, `branch=HU`, `ok`, `childH` |
| `expandGroupIfNeeded` | E1 | parent expand + child collapse | `TweaksHud expandGroupIfNeeded` | `count`, `parentWasExpanded`, `stateSet`, `clicked`, `h=before→after` |
| `ExpandableNotificationRow.setHeadsUp` | E1+E2 | pin/unpin, E2 arrow | `TweaksHud setHeadsUp`, `v2 HUD arrow` | `children`, `aeGroup`, `aeCollapsed`, `key` |
| `NotificationContentView.calculateVisibleType` | E1 | force `result=1` | `TweaksHud calcVisibleType` | `result`, `isGroup`, `aeCollapsed`, `willOverride` |
| `getIntrinsicHeight` / `getPinnedHeadsUpHeight` | E1 | force expanded height | silent | `isHeadsUp`, `aeGroupFlag`, `collapsed`, `expandedHeight`, `maxExpand` |
| `setExpandedWhenPinned` | E1 | sync `aeCollapsed` | silent | `isGroup`, `value` |
| `setMaxDisplayedLines` | shared | line clamp | silent | `maxLines` |

### Shade
| Function | Engine | Writes | Log | Values |
|---|---|---|---|---|
| `setSystemExpanded` before | E2 | `false → true` rewrite | `v2 setSystemExpanded rewritten/already` | `key`, `kg`, decision |
| `setSystemExpanded` after | E2 | group-parent trigger | `v2 … EXPAND_GROUP`, `skip=GroupNotReady` | `isSummaryWithChildren`, `groupParentsEnabled` |
| cold triggers (`onAttachedToWindow`, `onNotificationUpdated`, `setOnKeyguard`) | E2 | `reconcile` | `v2 <trigger> …` | `key`, `kg`, `grouped`, `bundle`, `grpExp`, `exp`, `pipeGrp`, `grpKey` |
| `repairChildLayouts` | E2 | `selectLayout(false,true)` | `v2 … repaired stale single-line child` | `key`, `visType`, `isGroupExpanded` |
| `reconcileChildren` | E2 | child policy | `v2 group/child …` | `groupChildrenEnabled` |
| legacy `onLayout` / `setSystemExpanded` | E1 | old shade driver, gated by `useV2()` | `onLayout expand` | n/a under E2 |

### Lock screen
| Function | Engine | Writes | Log | Values |
|---|---|---|---|---|
| `isExpanded(allowOnKeyguard)` before | E2 | flips argument for token rows | silent | `lsGateEnabled`, `mOnKeyguard`, token |
| `applyExpanded` / `applyGroupExpanded` / `applyCollapsed` | E2 | arrow click | `v2 arrow` | `key` |
| `StatusBarNotificationPresenter.onExpandClicked` | E2 | suppressed for our clicks | `v2 expand-click suppressor installed` | `Attribution.isOurs()` |
| `resetUserExpansion` after | E2 | re-apply / back off | `v2 backing off` | `key`, `isSummaryWithChildren` |
| `GroupExpansionManagerImpl.setGroupExpanded` | E2 | group-collapse memory | `v2 group` | `key`, `expanded` |

### Value dictionary
`kg` = `mOnKeyguard`; `hu` = `mIsHeadsUp`; `pinned` = `isPinned`; `child` = `isChildInGroup`;
`summaryKids` = `isSummaryWithChildren`; `grpExp` = `isGroupExpanded`; `exp`/`isExp(T)` =
`isExpanded(true)`; `pipeGrp` = `pipelineGroupedOf`; `grpKey` = `groupKey.contains("|g:")`;
`grouped` = policy `isGroupChild`; `bundle` = `isSystemAggregate`; `visType` =
`calculateVisibleType` (`3` = single-line); `newest` = `newestChildKey`.

### Fault map
1. **Old ungrouped row expands behind the new one:** cold `reconcile` → `isGroupChild=false`
   (`pipeGrp=false`, `grpKey=false`) → `Decision.Expand` → `applyExpanded` arrow.
   Fix applied: cold triggers only expand `newestChildKey` (`defer not-newest`).
2. **Shade children expand with the children toggle off:** `repairChildLayouts` →
   `selectLayout(false,true)` unconditionally. Fix applied: return early when
   `groupChildrenEnabled=false`.
3. **Old row pinned by SystemUI:** `HeadsUpManagerImpl.showNotification` → `onEntryAdded` →
   `setEntryPinned`. Engine 1 HU is gated off; Engine 2 only clicks the newest child.

## Verification 6 — transient group exit (2026-10-24)

User observation: the two notifications were not flashing together. The **new arrived for ~1 s, then the old followed**. The old one **was in the Telegram group**, then after the new notification it **left the group** and appeared after the new one.

Interpretation:
- This is the group re-parent window. When a new child arrives, SystemUI detaches/reorders the old child; while its pipeline parent is `ROOT_ENTRY`, `pipelineGroupedOf` returns `false` even though the notification's group key still contains `|g:`. `isGroupChild` trusts `false` over `belongsToGroup`, so the old row is treated as a single and expands.
- A second possibility is that the old row's late `onNotificationUpdated` sets `newestChildKey` to the old key, so the newest-only guard also lets it through.
- The old row is pinned by SystemUI after the new one, so a pure expansion guard cannot remove the 1 s follow-up; a targeted HUD veto for a transiently ungrouped group child may be required.

Next evidence: timeline of `grp=` / `pipeGrp=` / `grpKey=` for the old key, the `newest=` value at its EXPAND, and the pin times.

## Verification 7 — HUD veto is non-deterministic (2026-10-24)

- User ran the repro 3–4 times: **2 passed** (no old row) and **2 were followed by the old row**.
- The new notification's HUD exit now feels "forced"; the entry timing also changed.
- The veto is therefore not covering every burst. Candidate reasons:
  1. `groupSummaryPkgs` was stale/empty at `showNotification` time, so the veto returned early.
  2. The old row's `Notification.when` was refreshed by the group repost and looked newer than `hudNewestWhen`, so the veto allowed it.
  3. The follow-up arrived after `HUD_BURST_MS` in some runs.
  4. The old row took a HUD path that does not call `HeadsUpManagerImpl.showNotification`.
- Next: classify the pass/fail bursts from the log, then make the guard deterministic (first grouped child per burst; allow a later grouped child only when a genuine content update has a strictly newer message time).

## Current vs Goal — baseline after revert (2026-10-24)

### Current behavior (baseline build)
- Engine 2 is selected; Engine 1 heads-up hooks are gated off.
- New Telegram message: one HUD appears.
- Old-row follow-up: **sometimes** (2 of 4 in the last measured run). The HUD guard is the 2.5 s burst with a `Notification.when` newer allowance.
- In-chat: no HUD (fixed on the arrow build).
- Shade group children: one-line, because `repairChildLayouts` is gated by `groupChildrenEnabled`.
- Shade group parent: opens (parents ON, children OFF).
- HUD exit: reported "forced" only on the 10 s/freshness builds; baseline exit is under observation.
- Retained proven fixes: `pkgHasLiveSummary` transient-child classification, `setOnKeyguard` cold guard, repair gate, real-arrow expansion, Engine 1 HU gate.

### Goal behavior
- Exactly one HUD for the newest conversation; no old row follows, ever.
- A group burst with genuinely new content still produces a HUD (at least one).
- In-chat: no HUD for the active conversation.
- Shade: parent open, children one-line.
- HUD entry/exit remains stock — no content/grouping rebuild, no blanket suppression.
- Expansion only through the real system arrow.

### Protocol for every next change
1. Change **one** thing.
2. Build/install/restart.
3. Capture one clean log and read the `v2 HUD allow/veto`, `newest`, `when`, and pin lines.
4. Compare against the baseline; keep only if it moves current → goal without breaking another line.
5. No stacked guesses.

## Verification 8 — baseline HUD timing (2026-10-24)

- Single Bug A run on the reverted 2.5 s baseline.
- New notification HUD lasted **~200 ms**; the user's observed normal single-notification HUD is **700–1500 ms**.
- An old notification appeared randomly afterward. User hypothesis: the chat that never arrives in HUD caused the old one.
- Goal now includes **stock HUD timing**: match a normal single-app HUD, measured from the same log, not a forced constant.
- Next evidence: per-HUD `setHeadsUp(true)`→`setHeadsUp(false)` duration, `v2 HUD allow/veto` lines, pin replacement order, and a normal single-app HUD duration from the same window.

## Verification 9 — monotonic guard (2026-10-24)

- **Old-row follow-up: fixed.** No stale row appeared with the per-package monotonic content guard.
- Remaining defect: **HUD exit is fast and looks forced.** Entry is normal.
- Next: compare the Telegram HUD teardown with a normal single-app HUD from the same run; find the exact state change immediately before `setHeadsUp(false)` and map it to the function that emitted it.

## Verification 10 — cross-chat miss and freshness (2026-10-24)

Live capture `/tmp/ae_stream2.log`:

- 23:47:45.811 `v2 HUD allow key=…|-1404489531| when=1792874706000 last=1792873926000`
- 23:47:45.814 `T:setHeadsUp(true)` for that key; it stayed pinned until 23:47:51.515 (~5.7 s).
- The allowed row's `when` was **~2.6 minutes old**. The genuinely new chat's content was current.
- `/tmp/ae_stream.log` (180 s): 2127 lines, live window `23:45:29–23:48:29`; **TweaksHud = 0 lines**, confirming Engine 1 heads-up stayed silent.
- The user reported no HUD for the new chat: the per-package monotonic guard let the **wrong** old sibling through and the new chat never reached `showNotification` in that window.

Single change: **per-entry freshness**.
- `HUD_FRESH_MS = 60_000`.
- `v2 HUD allow fresh` only when `Notification.when` is within the window.
- `v2 HUD veto stale` otherwise.
- No per-package map, no burst window.

### Forced-exit evidence (separate bug, next)

From `/tmp/ae_capture_monotonic1.log`:
- Telegram HUD duration **694 ms** (23:32:07.518 → 23:32:08.212).
- Immediately before teardown: summary `setChildrenExpanded(true)` → **`v2 arrow GROUP`** + **`v2 onNotificationUpdated EXPAND_GROUP`** on the summary (23:32:08.204) → `setHeadsUp(false)` 8 ms later.
- An earlier `applyCollapsed` clicked the row that became the HUD row (`RowApi.clickExpand` → `setUserExpanded`).
- Next single change after the freshness test: do not open the group summary / collapse the pinned row while that row owns the HUD.

## Q&A log

### Q1 - What should the v4 selector switch: grouping modes or whole engines?
- Asked: the handoff's Engine 1/2 are grouping models, the repo's v1/v2 are expansion mechanisms; which should the UI switch?
- Captured (user's words): "i want two engines, engine v1 i think it is already there in the code, it works fine, then we fix engine two and make a toggle or somthing to change between the two engines."
- Interpretation: the user wants **two selectable engines**, keeps Engine 1 as the known-good path, fixes Engine 2, and adds a toggle. They rejected the "one core + grouping strategy" framing.
- Flags: which concrete code path is "Engine 2" on the device (`notif_engine_v2` pref vs the ROM's split-grouping behavior) -> user/device evidence.

### Q2 - A/B: is the defect v1 or v2?
- Asked: with `notif_engine_v2 = 0` (legacy v1), do Bugs A and B disappear, and return at `1`?
- Captured: **"Yes: v1 clean, v2 buggy."**
- **Correction from the user after this answer:** "v1 works on old version and i make it there for my users but v1 dont work for my version the new one." So Engine 1 is for old-ROM users only; it is not a working baseline on the new ROM. The A/B answer means the bugs occur on the new-ROM engine (v2), not that v1 is a valid same-device control. The leading v2 heads-up leak is still the working hypothesis, but it needs new-ROM evidence.
- Consequence: the first fix must make Engine 2's heads-up behavior correct on the new ROM; keeping v2 out of HU is still the narrowest candidate, but v1 cannot be used as the reference behavior.
- Flags: what exactly Engine 1 does on the new ROM (inert vs partial vs wrong grouping) -> user.

### Q3 - What does Engine 1 do on the new ROM?
- Asked: on the new-version device, when Engine 1 is selected, what actually happens?
- Captured (user's words): "actullay i dont remember but i think the groups didnt shows in heads up in shade only so i hear the notif sound only."
- Interpretation: on the new ROM, Engine 1 does not render grouped notifications in heads-up; there is only the sound and the shade rows. This is a **failure of the legacy HU path on the new grouping model**, not merely "inert".
- Consequence: the earlier option "keep v2 out of heads-up and use v1's HU" is **rejected** for the new ROM. Engine 2 must own heads-up there. Engine 1 remains the engine for old-ROM users.
- Flags: recollection is tentative; confirm with a screenshot/logcat once the device is connected.

### Q4 - Should each engine own the whole pipeline?
- Asked: should Engine 2 own heads-up + shade + lock screen on the new ROM, with Engine 1 untouched for old-ROM users?
- Captured: **"Yes — full ownership per engine."**
- Decision: Engine 1 = complete legacy stack, frozen for old-ROM users. Engine 2 = complete new-ROM stack (HU + shade + LS). The UI toggle selects one engine; no shared HU between them. This becomes the v4 Strategy boundary.
- Flags: HU implementation details for Engine 2 still to be designed after the new-ROM HUD evidence is captured.

### Q5 - How should users select an engine?
- Asked: auto-detect, manual toggle, or auto-only?
- Captured: **"Auto default + manual override (Recommended)."** Android 16+ defaults to Engine 2; older versions default to Engine 1. Settings shows the current choice plus an override, with an Apply & Restart SystemUI step.
- Flags: exact detection key (SDK_INT vs OEM ROM build) and restart mechanism -> design details.

### Q6 - In-chat heads-up rule
- Asked: when a new message arrives in the chat you are actively viewing, what should Engine 2 do?
- Captured (user's words): "when i am online chatting with somone and he send a massage the old notifs arrives in HUD i test it in telegram only."
- Interpretation: the user restated **Bug B** rather than choosing the new-message behavior. The confirmed requirement is that **old notifications must not enter HUD** when the new message arrives. Telegram is the Phase 1 repro app.
- Still open: whether the **new** message itself should still peek while the user is inside that chat.
- Flags: new-message visibility in the active chat -> user.

### Q7 - Should the new message itself peek in the active chat?
- Asked: while inside that Telegram chat, should the new message still appear in heads-up?
- Captured: **"No — no heads-up while I'm in that chat."** The new message still updates the shade; the HUD is suppressed for the active conversation.
- Consequence: Engine 2 needs an active-conversation / foreground-chat suppression rule, not just "filter out old rows". This is stronger than the handoff's minimum and must be implemented deliberately.
- Flags: does suppression apply only to the active conversation, to all of Telegram while a chat is open, or to all apps? -> user.

### Q8 - Suppression scope
- Asked: if Salem sends a new message while the user is in Fahad's chat, should Salem's HUD appear?
- Captured: **"Yes — different chat can peek."** Suppress only the conversation being actively viewed; new messages from other chats still peek individually.
- Consequence: Engine 2 needs to identify **both** the notification's conversation and the currently open conversation. That is the hard part; it is not simple foreground-app suppression.
- Flags: how exact the active-conversation match must be (exact chat ID vs best-effort heuristic), and what Telegram actually exposes in its notification -> user/device.

### Q9 - Bug A repro shape
- Asked: in the 2-older + 1-new repro, are the older notifications from the same chat as the new one?
- Captured: **"Different chats / different people."** Three separate Telegram conversations.
- Decision: the HUD must show only the third (new) conversation's message; the first two conversations must never re-enter the HUD. This is an isolation rule across conversations, not merely across rows of one group.
- Flags: if the *new* chat itself has older unread messages, whether the HUD shows only the newest message or that chat's unread set -> user.

### Q10 - HUD content when the new chat has older unread messages
- Asked: show only the newest message, the chat's unread set, or whatever the system shows?
- Captured: **"The new chat's unread messages together."**
- Decision: HUD is per **conversation**, not per individual message. The new chat's own unread messages are shown together; every other chat is excluded. The engine should preserve the conversation's messaging style rather than truncate it to one message.
- Flags: conversation identity source (shortcutId/extras) still to be confirmed from a device dump.

### Q11 - Shade/LS group default on Engine 2
- Asked: collapsed until tapped, parent opens with children one-line, or fully open?
- Captured: **"Parent opens, children stay one-line."**
- Decision: this is the **current default** (`expand_group_parents_enabled = true`, `expand_group_children_enabled = false`). Keep it for Engine 2. The handoff's "groups stay cleanly collapsed" wording is superseded: the parent opening is intended; what must not happen is expanding every child or breaking the bundle.
- Flags: none.

### Q12 - Next step after the Bug A root cause
- Asked: capture Bug B first, fix now, or finish the spec?
- Captured: **"Capture Bug B, then fix both."**
- Decision: keep the current build as the Bug B baseline, then implement the two v2 guards (skip SystemUI/HUD-container rows, single-setter `applyExpanded`), rebuild, install, and retest both bugs.
- Flags: Bug B baseline capture pending.

## Open flags (pending input)

- Bug B baseline capture -> user/device
- Behavior when several genuinely new messages arrive at once -> user
- How to detect the active conversation (notification shortcutId vs Telegram app hook vs best-effort) -> design + device evidence
- Telegram notification dump for a second chat, to compare shortcutId/LocusId -> user/device
- Exact engine-switch restart mechanism -> design
- Telegram bot credentials / bot setup for Phase 3 -> user
- Multi-OEM test devices (Xiaomi HyperOS, Samsung One UI) -> user
