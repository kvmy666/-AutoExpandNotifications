# AutoExpand v4 — Resume Checkpoint

Date: 2026-10-24 · Branch: `feat/autoexpand-v4` · Last commit: `1ff1756`
Device: `192.168.100.245:6666` · CPH2747 · Android 16 / SDK 36
OTA: `CPH2747_11.A.43_0430_202607012229`

Full interview + evidence log: `brainstorms/2026-10-24-autoexpand-engine2-headsup.md`

---

## Goal

Two selectable engines, never mixed:

- **Engine 1** — the existing legacy `NotificationExpander` stack. Frozen; it is the
  engine for users on the older ROM. Do not change its behavior.
- **Engine 2** — `NotifEngineV2`. Owns **heads-up + shade + lock screen** on the new
  ROM (Android 16 / OxygenOS 16).

Engine 2 target behavior:

1. Exactly one HUD for the genuinely new conversation. Stale/old rows never appear.
2. No HUD for the conversation the user is actively viewing; other chats may peek.
3. Shade: group parent opens, children stay **one-line** (children toggle is OFF).
4. HUD entry/exit matches a normal single-app HUD (~700–1500 ms), not a forced exit.
5. Expansion is performed by the **real system arrow** (`mExpandClickListener`).
   No notification rebuild, no state rewriting.
6. UX: auto default (SDK 36+ → Engine 2, older → Engine 1) + manual override,
   with Apply & Restart SystemUI.

---

## Current state

### Installed build

- Debug APK built with the current uncommitted tree and installed at 23:52.
- SystemUI restarted; pid was `1763` at 23:52 (may change).
- Current HUD guard is **per-entry freshness**:
  - `HUD_FRESH_MS = 60_000L`
  - allow when `Notification.when` is within `[-5 s, 60 s]`
  - log: `v2 HUD allow fresh …` / `v2 HUD veto stale …`
  - no per-package map, no burst window.
- **This exact build has not yet been tested** for the cross-chat case or the
  forced HUD exit.

### Uncommitted files (4 + capture doc)

- `app/src/main/java/io/github/kvmy666/autoexpand/hook/notif/RowApi.kt`
- `app/src/main/java/io/github/kvmy666/autoexpand/hook/notif/ExpandPolicy.kt`
- `app/src/main/java/io/github/kvmy666/autoexpand/hook/notif/NotifEngineV2.kt`
- `app/src/main/java/io/github/kvmy666/autoexpand/hook/NotificationExpander.kt`
- `brainstorms/2026-10-24-autoexpand-engine2-headsup.md` (untracked)

### What is in the tree

**RowApi**
- `rootEntry` resolved by type; `pipelineGroupedOf`:
  `ROOT_ENTRY → false`, summary → `true`, non-root `GroupEntry` without summary → `null`.
- `mExpandClickListener` cached; `clickExpand(row)` invokes the real arrow listener.
- `caps` logs `rootEntry=` and `arrow=`.

**ExpandPolicy**
- `RowFacts.pkgHasLiveSummary`.
- `isGroupChild` = `!summary && (pipeline == true || (pipeline == false && belongsToGroup && pkgHasLiveSummary) || (pipeline == null && belongsToGroup))`.
- `Reason.Internal`; skip keys starting `-2|` (SystemUI HUD container).

**NotifEngineV2**
- `groupSummaryPkgs` registry (`SUMMARY_TTL_MS = 120_000`).
- `newestChildKey` / `newestChildWhen` from `Notification.when` (not update order).
- Cold triggers = `onAttachedToWindow`, `onNotificationUpdated`, `setOnKeyguard`.
- Cold guards:
  - `defer not-newest` for non-summary rows that are not `newestChildKey`;
  - `defer group-forming` for `belongsToGroup && !isGroupChild && !isGroupSummary`.
- `repairChildLayouts` gated by `groupChildrenEnabled` (children stay one-line).
- `applyExpanded` / `applyGroupExpanded` / `applyCollapsed` use
  `RowApi.clickExpand(row)` inside `Attribution.ours`; no `setSystemExpanded` /
  `setUserExpanded` in those paths.
- `installExpandClickSuppressor`: `StatusBarNotificationPresenter.onExpandClicked`
  returns early only when `Attribution.isOurs()` (prevents locked-shade transition).
- `installHudHook`: on `setHeadsUp(true)`, click the arrow only for
  `newestChildKey`, attached and not already expanded, 60 ms delayed.
- `installHudVeto`: `HeadsUpManagerImpl.showNotification` freshness guard (above).

**NotificationExpander (Engine 1)**
- All heads-up hooks now return immediately when `useV2()`:
  `addNotification`, `calculateVisibleType`, `getIntrinsicHeight`,
  `getPinnedHeadsUpHeight`, `setHeadsUp` (before/after), `setExpandedWhenPinned`,
  and `expandGroupIfNeeded`.
- `setMaxDisplayedLines` remains shared.
- Verified on device: `TweaksHud` produced **0 lines** across a 180 s window.

### Verified on earlier builds

- Engine 1 heads-up is silent under Engine 2.
- Old-row follow-up was eliminated by the monotonic guard (one clean run).
- In-chat HUD suppression was observed as fixed on the arrow build (re-verify).
- Shade child expansion was still wrong before the `repairChildLayouts` gate;
  the gate is in the tree (re-verify).

### Known remaining defects (evidence-backed)

1. **Cross-chat miss / wrong row**
   - On the monotonic build, the row that HUD'd (`-1404489531`) had
     `Notification.when` **2.6 minutes old**, while the genuinely new chat was current.
   - The per-package guard allowed the wrong row. The current freshness guard is the
     fix candidate; it is untested.
2. **Forced HUD exit**
   - Telegram HUD lasted **694 ms** vs stock ~700–1500 ms.
   - Sequence: summary `setChildrenExpanded(true)` → `v2 arrow GROUP` +
     `v2 onNotificationUpdated EXPAND_GROUP` → `setHeadsUp(false)` 8 ms later.
   - An earlier `applyCollapsed` clicked the row that became the HUD row.
   - Not fixed yet.

---

## Next steps (one change per build)

1. **Test the current freshness build — cross-chat**
   - Phone unlocked, home screen.
   - Group chat active / no shade notification.
   - Another Telegram chat sends a message.
   - Expected: stale sibling vetoed; new chat allowed.
   - Read `v2 HUD allow fresh` / `v2 HUD veto stale` and `T:setHeadsUp(true)`.
   - If the new chat still does not HUD, determine whether
     `HeadsUpManagerImpl.showNotification` was called for its key.
     - If not called: the issue is SystemUI's HUD representative selection, not the veto.
       Next candidate: veto the stale sibling so the system promotes the right one,
       or hook `OplusHeadsUpCoordinator` / `onEntryAdded`.
2. **Fix the forced exit**
   - Do not open the group summary or collapse a row while that row owns the HUD.
   - Gate `applyGroupExpanded` / `applyCollapsed` on `isHeadsUp` / pinned, or defer
     group expansion until the HUD ends.
   - Measure the new HUD duration against a normal single-app HUD.
3. Re-verify shade children stay one-line with the children toggle OFF.
4. Re-verify no HUD while inside the active chat; other chats may still peek.
5. Implement the v4 engine selector UI:
   - auto default: SDK 36+ → Engine 2, older → Engine 1;
   - manual override; Apply & Restart SystemUI.
6. Commit the v4 changes; build release; verify Engine 1 on the old ROM is unchanged.
7. Optional: other OEMs (HyperOS / One UI), Telegram-bot automated E2E.

---

## Test protocol

Build:

```bash
cd /home/abdulkarim/AutoExpandNotifications
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
ANDROID_HOME=/home/abdulkarim/Android/Sdk \
./gradlew :app:assembleDebug
```

Install:

```bash
adb -s 192.168.100.245:6666 install -r app/build/outputs/apk/debug/app-debug.apk
```

Reload SystemUI (verify the pid changes):

```bash
adb -s 192.168.100.245:6666 shell \
  'am start -a android.intent.action.VIEW -d "anywhere://open?sid=1776272750274"'
adb -s 192.168.100.245:6666 shell pidof com.android.systemui
```

Live filtered capture (survives buffer rotation):

```bash
timeout 240 adb -s 192.168.100.245:6666 logcat -v time \
  -s AENotif:D AENotifProbe:D TweaksHud:D > /tmp/ae_streamN.log
```

What to read:
- `v2 HUD allow fresh` / `v2 HUD veto stale` — key, `when`, `age`.
- `v2 HUD arrow EXPAND` / `v2 HUD arrow missing` — newest-child arrow.
- `T:setHeadsUp(true)` / `T:setHeadsUp(false)` — duration and order.
- `v2 arrow GROUP` / `v2 … EXPAND_GROUP` / `v2 … COLLAPSE_CHILD` — group cascade.
- `TweaksHud` — must stay absent under Engine 2.

Device prefs at checkpoint:

```
notif_engine_v2=1
expand_headsup_enabled=1
disable_headsup_hooks_enabled=0
disable_headsup_popup_enabled=1
expand_group_parents_enabled=1
expand_group_children_enabled=0
notif_debug_logging=1
notif_probe_enabled=1
```

---

## Tooling

- REA: `rea-agents@6.7.0`, Android provider JAR
  `/home/abdulkarim/.local/share/rea/android/jadx-headless-mcp-0.7.1-all.jar`
  (SHA-256 `6e5eacf500b64292bfb73c49797c1958f6ee44646e43e868039ae7feb573ff75`).
  Wired into `~/.config/kilo/kilo.jsonc` as `REA_JADX_MCP_JAR` + `JAVA_HOME`;
  a Kilo restart is needed for the MCP process to pick it up.
- Live SystemUI APK: `/tmp/SystemUI.apk`
  (SHA-256 `dbe67fd3688a5bb5ebe7200190aaaac0bfbf88068a1534b6aebd62cd3f588ff4`).
- Decompiled sources: `/tmp/jadx-out/sources/...`:
  `ExpandableNotificationRow`, `HeadsUpManagerImpl`, `GroupExpansionManagerImpl`,
  `OplusHeadsUpCoordinator`, `OplusNotificationListContainerWraper`.
- Key decompiled fact: `ExpandableNotificationRow.mExpandClickListener` is the real
  arrow path (summary → `GroupExpansionManagerImpl.setGroupExpanded`;
  single → `setUserExpanded`; then `onExpandClicked`).
- `HeadsUpManagerImpl.showNotification(NotificationEntry, boolean)` returns `void`;
  a veto must skip the original call (current approach) or hook `onEntryAdded`.

## Subagent models / cost

- `small_model` = `opencode-go/beam-free` (free).
- `explore-fast` = `opencode-go/beam-free`; `permission: "rea_*": deny` so the free
  model's 128-tool limit is not exceeded. Verified with `MODEL_OK`, git tool use, and
  a log grep.
- `builder` / `planner` / `review-strict` = `opencode-go/deepseek-v4.1-flash`
  ($0.15 in / $0.60 out; kept by user choice, over the $0.30 output cap).
- No `glm` / `gemini` in the agent config.
- Under-cap paid `kilo` models exist (DeepSeek V4 Flash $0.14/$0.28,
  Seed 1.6 Flash $0.075/$0.30) but the account returns “Add credits to continue.”
