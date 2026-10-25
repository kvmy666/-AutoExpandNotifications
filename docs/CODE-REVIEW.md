# Code review — engine, resolver and settings

Date: 2026-10-25 · Branch: `feat/oem-hook-resolver`

Scope: the Engine 2 core, the OEM resolver and the notification settings surface. The goal was
**behaviour-preserving cleanup**, not a rewrite. The verified HUD path was treated as frozen.

## What changed in this pass

| Change | Where | Risk |
|---|---|---|
| Freshness extraction: one `entryKey` / `entryWhen` / `isFresh` used by all four HUD paths | `NotifEngineV2.kt` | none — same calls, same order |
| `HUD_AGE_SLACK_MS` constant replaces four `5_000L` literals | `NotifEngineV2.kt` | none |
| Removed an unused `original` parameter from `promoteOnEntry` | `NotifEngineV2.kt` | none |
| `summaryPresent()` deduplicates the method/field summary check | `RowApi.kt` | none — same true/false/null contract |
| Engine selector moved to the **top** of Notifications, with plain-language copy and a "try both" hint | `SettingsScreens.kt` | UI only |
| Test senders moved to `debug/` (`TestNotifier`, `TestTriggerReceiver`) | source tree | package/import only |

## Deferred on purpose (with reason)

1. **`NotificationExpander.install()` is ~520 lines.** Splitting it is mechanical but it is the
   Engine 1 path whose hook *order* and `useV2()` gates are load-bearing. A split is safe only with
   an Engine 1 device to re-test; deferred rather than risk silent breakage.
2. **`NotifEngineV2.install()` is ~170 lines.** Same reason: hook installation order interacts with
   the verified HUD. Readable as-is because each block is labelled.
3. **`installHudLayoutHooks()` (3 blocks) and `installHudHook()` (2 blocks).** Extraction would be
   safe, but the gain is small and the HUD is the one thing measured end-to-end. Deferred.
4. **Remaining magic delays** (`250L` auto-collapse, `60L`/`300L` settle windows). They are
   documented at the call site and tuning them is a behaviour change.
5. **`NotificationExpander` duplicate package extraction** (`getNotificationPackage` vs
   `getNotificationPackageFromContentView`). Deferred with `install()` above.

## Do not move — order-sensitive code

- `setPinnedStatus` → `Attribution.ours { setExpandedWhenPinned(true); setSystemExpanded(true) }`.
  On both Oplus and Xiaomi the first setter throws (method absent), so the second is **not**
  reached today. That is part of the verified 1121 px result; reordering changes behaviour.
- `promoteNewest` / `promoteOnEntry`: `param.args[0] = …` is set **before** `hudPendingKey`, because
  the collapse deferral reads the pending key.
- `reconcile`: the newest-child update and the cold-trigger "not newest" return must stay before
  the policy decision.
- Engine 1 heads-up hooks must keep their `useV2()` early returns (two engines would fight).
- `Attribution.ours` must wrap every engine-initiated row write.

## Test note

`ClipboardDatabaseTest.deleteAllImages leaves every text entry untouched` is **flaky**: it failed
once on row order (`text two, text one` vs `text one, text two`) and passed on a clean rerun. It is
unrelated to the engine work. The fix is to order by a deterministic key (or compare as a set), not
to touch the engine.

## Verification performed

- `assembleDebug` green after each change.
- Unit suite: 77/78 pass; the one failure is the flaky clipboard test above.
- Emulator (Android 16, Engine 2): the stale-sibling test still shows the fresh child HUD.
