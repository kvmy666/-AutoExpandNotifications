# AutoExpand walkthrough

## For everyone (no technical words)

AutoExpand opens notifications for you. A message arrives, and instead of a cut-off one-liner you
see the whole message, with its buttons, without tapping anything.

There are **two ways** it can do that. Think of them as two different assistants:

- **Engine 1 — the older assistant.** It looks for the little arrow on the notification and taps it
  for you. Most phones understand this. On a few phones it can hesitate: the banner may flicker, or
  it may open an older chat instead of the new one.
- **Engine 2 — the newer assistant.** Instead of tapping, it reads what the notification is doing
  and opens the newest chat directly. It is the one built for OxygenOS/ColorOS 16, Xiaomi HyperOS
  and Android 16. On older phones it may not have everything it needs.
- **Auto** simply picks the safer one for your phone.

### How to test both (5 minutes)

1. Open AutoExpand → **Notifications**. The engine choice is the first card.
2. Pick **Engine 1**, tap **Apply & restart SystemUI**, and wait for the screen to come back.
3. Send yourself a message (or ask someone to). Watch the banner: does it open fully, without a
   flash, and show the newest chat?
4. Repeat with **Engine 2**.
5. Keep the one that felt right. If both feel the same, leave **Auto**.

If a banner opens the wrong chat, or blinks and disappears, that is the engine telling you it is
the wrong one for this phone. Switching is safe: nothing is deleted, and you can switch back at any
time.

## For developers

### Entry points

```
MainHook.handleLoadPackage
  └─ EngineSelection.isV2(prefs)          ← one cached decision per SystemUI process
       ├─ false → NotificationExpander.install()   (Engine 1)
       └─ true  → NotifEngineV2.install()          (Engine 2)
```

Both engines install hooks; the one that is off returns early inside its hooks (`useV2()`), so a
half-switched process cannot happen.

### Engine 2 flow

```
row callback (onNotificationUpdated / onAttachedToWindow)
  → capture(row)            RowApi: reflected facts, no writes
  → ExpandPolicy.decide     pure decision (sealed Decision)
  → Attribution.ours { apply… }   every engine-initiated write
  → RowStateStore           back-off / user-intent memory

HUD:
  Oplus:    HeadsUpManagerImpl.showNotification(entry, bool)
  AOSP/HyperOS: HeadsUpManagerImpl.onEntryAdded(HeadsUpEntry, PinnedStatus)
     → same freshness rule (60 s), stale vetoed, newest child substituted
```

The key state: `newestChildKey` / `newestChildEntry` (newest by `Notification.when`), `hudKey`,
`hudPendingKey` (HUD allowed but `setHeadsUp(true)` not yet seen), `hudExpandedKeys`.

### OEM resolver

```
OemDetector.current                  OPLUS, XIAOMI, SAMSUNG, VIVO, HONOR, ASUS, AOSP, UNKNOWN
HookTargets.row/contentView/…        candidate class names, AOSP first
HookTargetResolver.findClass         exact → hierarchy → shape (methods only)
RowApi.bind                          one resolution at install, cached in fields
DexKitBackend                        last resort, name-contains, never on a matching ROM
```

`RowApi.caps` is the first line to ask for in a bug report: it names the OEM, the row class and
every capability (`isExpandedArg`, setters, fields, content view, group summary).

### File map

| Area | Files |
|---|---|
| Engine 1 | `hook/NotificationExpander.kt` |
| Engine 2 | `hook/notif/NotifEngineV2.kt`, `ExpandPolicy.kt`, `RowStateStore.kt`, `Attribution.kt` |
| Reflection/resolver | `hook/notif/RowApi.kt`, `HookTargets.kt`, `HookTargetResolver.kt`, `OemDetector.kt`, `DexKitBackend.kt` |
| Engine choice | `hook/notif/EngineSelection.kt` |
| Prefs IPC | `hook/PrefsBridge.kt`, `PrefsJson.kt` |
| UI | `MainActivity.kt`, `HomeScreen.kt`, `SettingsScreens.kt` |
| Debug tools | `debug/TestNotifier.kt`, `debug/TestTriggerReceiver.kt`, `TestingScreen.kt` |

### Testing

```
# build + install
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk

# restart SystemUI and watch the engine lines
adb shell 'am start -a android.intent.action.VIEW -d "anywhere://open?sid=1776272750274"'
adb logcat -G 16M && adb logcat -c
adb logcat -d -v time | grep -E 'engine selected|RowApi caps|v2 engine|v2 HUD'
```

Debug notification shapes (not in release builds):

```
adb shell am broadcast -n io.github.kvmy666.autoexpand/.debug.TestTriggerReceiver \
  -a io.github.kvmy666.autoexpand.TEST --es kind StaleThenFresh --el delay_ms 3000
```

Kinds: `Group`, `StaleThenFresh`, `LoneGroupChild`, `Messaging`, `BigText`, `Inbox`, `LongText`,
`Silent`, `AutoGroupFlood`.

### Invariants (do not reorder)

1. The pinned path's `setExpandedWhenPinned` throws on both verified ROMs, so the following
   `setSystemExpanded(true)` is not reached. That is part of the measured HUD height.
2. `hudPendingKey` is written after `param.args[0]` in the HUD substitution.
3. `reconcile` must update the newest child and drop non-newest cold triggers before deciding.
4. Every engine write goes through `Attribution.ours`.
5. Engine 1's heads-up hooks must keep the `useV2()` gates.
6. `pipelineGroupedOf` returns `null` for unknown — never `false` — while a group is forming.
