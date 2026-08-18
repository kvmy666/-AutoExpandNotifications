# توثيق نظام الإشعارات — Auto Expand (v3.2.1)

> **حالة الوثيقة:** مرجع معماري فقط. لم يُعدَّل أي سطر من الكود أثناء إعدادها.
> **المخطَّط المرافق:** `docs/notifications-architecture.drawio` (ثلاث صفحات: تدفق الإعدادات، خريطة الخطافات، محرّك التوسيع ودورة حياة الـ HUD).
> **آخر مزامنة مع الكود:** الفرع `main`، الكوميت `26de914`.

---

## 1. النطاق

هذه الوثيقة تغطي **مسار الإشعارات فقط**:

| الملف | السطور | الدور |
|---|---|---|
| `hook/NotificationExpander.kt` | 1–800 | كل منطق التوسيع/الطيّ وجميع خطافات الإشعارات |
| `MainHook.kt` | 35–139 | نقطة الدخول، التقاط سياق SystemUI، استدعاء `notif.install()` |
| `hook/PrefsBridge.kt` | 1–169 | قراءة الإعدادات عبر ثلاث قنوات IPC + نبضة الحياة |
| `MainActivity.kt` | 53–113، 116–217 | كتابة الإعدادات ونشرها للنظام + القيم الافتراضية |
| `SettingsScreens.kt` | 89–102، 300–383 | واجهة قسم "Notifications" |
| `AppListActivity.kt` | 92، 177–179 | قائمة التطبيقات المستثناة |
| `PrefsJson.kt` | 13–21 | محلِّل JSON المشترك |

خارج النطاق: Snapper، Zones، Keyboard Enhancer، Global Search، Keep-Screen-On.

---

## 2. كيف يعمل نظام الإشعارات في أندرويد (الخلفية اللازمة)

### 2.1 الطبقات

```
التطبيق (WhatsApp) ──> NotificationManager ──> NotificationManagerService (system_server)
                                                        │
                                                        ▼
                                          SystemUI (com.android.systemui)
                                          ├── NotificationEntry           ← نموذج البيانات
                                          ├── ExpandableNotificationRow   ← الـ View الأب لكل إشعار
                                          │     ├── NotificationContentView (mPrivateLayout)
                                          │     │     ├── mContractedChild  (مطوي)
                                          │     │     ├── mExpandedChild    (موسّع)
                                          │     │     └── mHeadsUpChild     (بانر)
                                          │     └── NotificationChildrenContainer (mChildrenContainer)
                                          │           └── الصفوف الأبناء داخل المجموعة
                                          └── com.android.internal.widget.NotificationExpandButton
                                                (سهم التوسيع الحقيقي، id = android:expand_button)
```

### 2.2 التجميع (Grouping) — المشكلة الأصلية

عندما يرسل تطبيق عدّة إشعارات بنفس `groupKey`، ينشئ النظام:

- **صف أب (Group Summary)** يحمل العلَم `Notification.FLAG_GROUP_SUMMARY`.
- **صفوف أبناء** تُربَط داخل `mChildrenContainer` للأب.

الأب يظهر **مطويًا** افتراضيًا فيعرض سطرًا واحدًا مثل "رسالتان جديدتان". دور الموديول تاريخيًا:

> **وسِّع الأب، وأبقِ الأبناء في حالتهم الطبيعية (مطوية سطرًا واحدًا)** — أي أظهر قائمة المجموعة المدمجة فورًا بدل السطر الملخَّص.

### 2.3 الحالات الثلاث لعرض الإشعار

| الحالة | الشرط في الكود | الخطافات المسؤولة |
|---|---|---|
| **Shade** (لوحة الإشعارات) | `!mIsHeadsUp && !mOnKeyguard` | `[1] setSystemExpanded`, `[2] onLayout` |
| **Lock Screen** (شاشة القفل) | `!mIsHeadsUp && mOnKeyguard` | `[1]`, `[2]` + مسار كتم `onExpandClicked` |
| **Heads-Up / HUD** (البانر العائم) | `mIsHeadsUp == true` | `[3]…[8]` |

هذا التقسيم صارم: خطاف `onLayout` يخرج فورًا إذا كان `mIsHeadsUp`، وخطافات الـ HUD تخرج فورًا إذا لم يكن.

### 2.4 لماذا لا يكفي `setUserExpanded`

الحقيقة المؤكَّدة بـ LogCat على هذا البناء (OxygenOS 16 / API 36):

- `setUserExpanded(true, true)` **يُرجع نجاحًا لكنه لا يثبت** — خاصةً على المجموعات؛ يُعاد ضبطه في نفس تمريرة الـ layout.
- الشيء الوحيد الذي يوسّع فورًا وبثبات هو **الضغط البرمجي على السهم الحقيقي**:
  `findStrictExpandButton(rowView)?.performClick()`.

لذلك بُني المحرّك كله حول `performClick`، و `setUserExpanded` مجرّد خطة بديلة (fallback).

---

## 3. أين يتدخّل الموديول

### 3.1 نقاط الدخول

`app/src/main/assets/xposed_init`:
```
io.github.kvmy666.autoexpand.MainHook
io.github.kvmy666.autoexpand.KeyboardHook
```

نطاق LSPosed (`res/values/arrays.xml`):
`android` · `com.android.systemui` · `com.google.android.inputmethod.latin` · `com.oppo.quicksearchbox`

### 3.2 التوزيع على العمليات

| العملية | ما يُثبَّت |
|---|---|
| `android` (system_server) | `SnapperChordHook` فقط — **لا خطاف إشعارات هنا** |
| `com.android.systemui` | `NotificationExpander` + `ZonesHook` + `KeepScreenOnController` + خطاف اهتزاز الرجوع |

`MainHook.handleLoadPackage` (سطر 35) يلفّ كل شيء في `try/catch` مزدوج — قاعدة صارمة: **الفشل الصامت أفضل من bootloop**.

### 3.3 التهيئة داخل SystemUI

`MainHook.handleSystemUi` (سطر 77) يخطف `android.app.Application.onCreate` ثم:

1. `prefs.appContext = app` — يلتقط السياق اللازم لقراءة `Settings.Global`.
2. `prefs.loadFilePrefs()` — أول قراءة للإعدادات.
3. `prefs.startFileObserver()` — مراقبة `/data/local/tmp` لتحديث فوري.
4. `prefs.startHeartbeatThread()` — خيط daemon يكتب نبضة كل 60 ثانية.
5. كتابة العلامة القديمة `Settings.Global.autoexpand_active`.
6. **`notif.install(lpparam)`** (سطر 116) — تثبيت خطافات الإشعارات.

---

## 4. طبقة الإعدادات و IPC

### 4.1 مسار الكتابة (من التطبيق)

```
ToggleRow ──> onToggle(key, value)
                ├─ prefs.edit().putBoolean(...).apply()      → prefs.xml
                ├─ MainActivity.makePrefsWorldReadable()     → chmod o+r
                └─ MainActivity.broadcastPref(ctx, key, val)
                        ├─ sendBroadcast(PREF_CHANGED)   ← لا يستهلكه مسار الإشعارات
                        └─ MainActivity.writePrefsFile(ctx)
                                └─ su -c "cp …/tweaks_prefs.json ;
                                          chmod 644 … ;
                                          settings put global ae_prefs_json <base64>"
```

كل القيم تُخزَّن كنصوص: `Boolean → "1"/"0"`، `Set<String> → مفصولة بـ `\n``. هذا مقصود لتوافق قناة JSON المسطّحة (`PrefsJson.parse`).

### 4.2 مسار القراءة (داخل الخطاف) — `PrefsBridge`

`loadFilePrefs()` (سطر 69) يجرّب بالترتيب:

| # | القناة | لماذا |
|---|---|---|
| 1 | `Settings.Global["ae_prefs_json"]` (base64) | **القناة الأساسية** — قابلة للقراءة من SystemUI و system_server حتى مع حجب SELinux لـ `/data/local/tmp` |
| 2 | `/data/local/tmp/tweaks_prefs.json` | إرث؛ يعطي `EACCES` على LSPosed 2.0 + OxygenOS 16 |
| 3 | `XSharedPreferences("…/prefs")` | احتياطي؛ ينجو من قتل عملية التطبيق (Xiaomi SmartPower) |

**ذاكرة تخزين مؤقتة 2000 ms** عبر `reloadIfStale()` (سطر 61) — تعني أن أي تغيير في الإعداد يُلتقط حيًّا خلال ثانيتين، **دون إعادة تشغيل**. (رسالة "reboot required" في الواجهة تظهر لكل تبديل، وهي غير دقيقة للقيم — الإعادة لازمة فقط عند تغيير كود الخطاف نفسه.)

### 4.3 دلالات القيم الافتراضية — نقطة حسّاسة

| الدالة | الافتراضي عند غياب المفتاح | تُستخدم لـ |
|---|---|---|
| `isFeatureEnabled(key)` | **true** | الميزات (`expand_*`, `disable_headsup_popup_enabled`) |
| `isKillSwitchActive(key)` | **false** | مفاتيح الإيقاف (`disable_headsup_hooks_enabled`) |
| `isOptInEnabled(key)` | **false** (اسم مرادف للسابق) | الميزات الاختيارية |

الخطأ هنا يعني تعطيل ميزة للجميع أو تفعيل kill-switch عند كل من لم يُكتب ملف إعداداته بعد.

### 4.4 نبضة الحياة (Heartbeat)

- **كتابة:** خيط في SystemUI كل 60 ث → `Settings.Global["ae_heartbeat"] = SystemClock.elapsedRealtime()` + ملف احتياطي.
- **قراءة:** `MainActivity.isModuleActive()` (سطر 23) — نافذة 3 دقائق.
- استُخدم `elapsedRealtime` (نسبي للإقلاع) بدل ساعة الحائط لتفادي بلاغات "الموديول غير نشط" الكاذبة بسبب انحراف NTP.

---

## 5. `NotificationExpander` — التفصيل الكامل

### 5.1 الحالة (State)

#### حالة لكل صف — عبر `XposedHelpers.setAdditionalInstanceField`

| الحقل | النوع | المعنى | يُضبط في |
|---|---|---|---|
| `aeAutoExpanded` | Boolean | "وسّعتُ هذا الصف مرة واحدة في هذه الفتحة" (one-shot) | `[1]`, `[2]`، يُصفَّر في `[4]` عند `setHeadsUp(false)` |
| `aeLastSysExpTs` | Long | آخر توقيت لـ `setSystemExpanded` (لحساب الفجوة الهادئة) | `[1]` |
| `aeLastLayoutTs` | Long | آخر توقيت لـ `onLayout` (لحساب الفجوة الهادئة) | `[2]` |
| `aeIsGroup` | Boolean | هذا الصف أبٌ لمجموعة في وضع HUD | `[3]`, `[4]`, `expandGroupIfNeeded` |
| `aeCollapsed` | Boolean | حالة الطيّ الخاصة بنا للـ HUD، مستقلة عن توقيت `mExpandedWhenPinned` | `[4]`, `[8]`, `toggleHeadsUpExpandState` |

> **لماذا حقول إضافية بدل حقول النظام؟** لأن `mExpandedWhenPinned` يُضبط **بعد** أن تُستدعى دوال القياس، فتقرأ الخطافات قيمة قديمة. الحقول الخاصة تُضبط أولًا فتُرجع دوال الارتفاع القيمة الصحيحة.

#### حالة على مستوى الكائن (`@Volatile`)

| الحقل | السطر | الدور |
|---|---|---|
| `f1DownStartY` / `f1IsDownwardSwipe` / `f1HasToggled` / `f1SwipeTime` / `f1CurrentRow` | 30–33 | تتبّع سحب الأصبع للأسفل على الـ HUD |
| `lsAutoExpandUntil` | 123 | نهاية نافذة كتم `onExpandClicked` على شاشة القفل (400 ms) |
| `onExpandClickedHooked` | 124 | هل ثُبِّت الخطاف الديناميكي؟ (مرة واحدة فقط) |
| `expandClickField` / `expandClickFieldResolved` | 116–117 | تخزين مؤقت لنتيجة البحث الانعكاسي عن حقل المستمع |

### 5.2 الدوال المساعدة

| الدالة | السطر | المسؤولية | ملاحظات |
|---|---|---|---|
| `ts()` | 26 | ختم زمني بالمللي ثانية للسجلات | يُطبع كـ `[123456]` مع الوسم `TweaksHud` |
| `getNotificationPackage(row)` | 35 | `row.getEntry().getSbn().getPackageName()` | يُرجع `null` بدل الرمي |
| `isGroupSummaryRow(row)` | 50 | `FLAG_GROUP_SUMMARY` **و** `getRowChildCount() > 0` | شرط الأبناء ضروري: WhatsApp يضع العلَم على محادثة مفردة قبل وصول أي ابن؛ بدونه لن تتوسّع الإشعارات المفردة |
| `resourceEntryAndPkg(view)` | 62 | `(getResourceEntryName, getResourcePackageName)` | أساس التعرّف على السهم |
| `getRowChildCount(row)` | 70 | `mChildrenContainer.getNotificationChildCount()` | 0 عند الفشل |
| `getNotificationChildren(row)` | 76 | `getAttachedChildren()` أو `getNotificationChildren()` | مسارَان لاختلاف بناءات OEM |
| `findExpandButtonImpl(view, strict)` | 93 | بحث DFS عن زر التوسيع | يتخطّى أي `ExpandableNotificationRow` متداخل حتى لا يُضغط سهم ابن بدل الأب؛ يشترط `hasOnClickListeners()` و `visibility == VISIBLE` |
| `findStrictExpandButton(view)` | 87 | صارم: `pkg == "android"`، `id == expand_button`، **والصنف** `com.android.internal.widget.NotificationExpandButton` | يتجاهل `alternate_expand_target` الذي يفتح التطبيق على بعض الـ ROMs |
| `findExpandButton(view)` | 91 | متساهل: يقبل `expand_button` أو `alternate_expand_target` | يُستخدم فقط في مسار الـ HUD |
| `beginLsAutoExpandWindow()` | 126 | يفتح نافذة كتم 400 ms | `uptimeMillis + 400` |
| `ensureOnExpandClickedHook(listener)` | 133 | يثبّت خطافًا ديناميكيًا على `onExpandClicked` لصنف المستمع المرصود | مرة واحدة فقط طوال عمر العملية |
| `getExpandClickListenerField(row)` | 160 | يمشي على شجرة الأصناف بحثًا عن حقل اسمه يحوي `expandclick` أو نوعه يحوي `onexpandclicklistener` | مخزَّن مؤقتًا؛ لا تخمين لأسماء AOSP |
| `clickExpandSilentlyOnKeyguard(row, btn)` | 196 | توسيع صامت على شاشة القفل | دفاع مزدوج: نافذة الكتم **+** تفريغ حقل المستمع أثناء النقرة ثم إعادته في `finally`؛ **يفشل مفتوحًا** (نقرة عادية) إن لم يُعثر على الحقل |
| `getNotificationPackageFromContentView(view)` | 216 | `view.mContainingNotification` ← ثم `getNotificationPackage` | لخطاف `calculateVisibleType` |
| `shouldSkipNotification(key, pkg)` | 225 | البوابة الموحّدة: `!isFeatureEnabled(key) \|\| pkg ∈ excluded_apps` | **لا يقرأ** الـ kill-switch — ذلك متعمّد (انظر 5.4) |
| `expandGroupIfNeeded(rowView, rowObj, pkg)` | 243 | تمريرة توسيع مجموعة HUD واحدة | idempotent: يوسّع الأب فقط إن كان مطويًا، ويطوي الأبناء فقط إن كانوا موسّعين |
| `collapseChildNoAnim(child)` | 286 | `setUserExpanded(false, false)` ← ثم `setUserExpanded(false)` | يُرجع `true` إن نجح الاستدعاء |
| `toggleHeadsUpExpandState(row)` | 302 | تبديل توسيع/طيّ الـ HUD يدويًا | المجموعة → ضغط السهم؛ المفرد → قلب `aeCollapsed` + `setActualHeight` + `requestLayout` |

### 5.3 الخطافات الاثنا عشر

> النمط العام: كل `findAndHookMethod` ملفوف بـ `try/catch` خارجي (فشل التثبيت صامت)، وكل جسم خطاف ملفوف بـ `try/catch` داخلي (فشل التنفيذ صامت). لا استثناء يخرج إلى SystemUI أبدًا.

---

#### `[1]` `ExpandableNotificationRow.setSystemExpanded(boolean)` — سطر 384

- **النوع:** `afterHookedMethod`
- **متى يُطلق:** عند **نشر إشعار أو تغيّر المجموعة** فقط — **لا** يُطلق عند إعادة فتح اللوحة.
- **الحرّاس:** `mIsHeadsUp == false`، `isChildInGroup() == false`.
- **المفتاح:** `mOnKeyguard ? expand_lockscreen_enabled : expand_shade_enabled`.
- **إعادة الدخول:** إذا مرّ أكثر من 300 ms منذ `aeLastSysExpTs` ⇒ `aeAutoExpanded = false` (فتحة جديدة).
- **الإجراء:** `rowView.post { setUserExpanded(true,true) → وإلا findStrictExpandButton().performClick() }`، و `beginLsAutoExpandWindow()` أولًا إن كنّا على القفل.
- **الدور:** المسار السريع لأول ظهور. المحرّك العام `[2]` يغطّي كل ما بعده.
- **لماذا `post`؟** التنفيذ المتزامن داخل نفس تمريرة الـ layout التي استدعت `setSystemExpanded(false)` يُلغى بتلك التمريرة نفسها.

---

#### `[2]` `ExpandableNotificationRow.onLayout(...)` — سطر 456 ★ **المحرّك العام**

- **النوع:** `XposedBridge.hookAllMethods` + `afterHookedMethod`.
- **متى يُطلق:** في **كل** فتحة للوحة/شاشة القفل — لهذا اختير.
- **الحرّاس:** `!mIsHeadsUp`، `!isChildInGroup()`، `rowView.isShown`.
- **المفتاح:** كما في `[1]`.
- **إعادة الدخول:** فجوة هادئة 300 ms على `aeLastLayoutTs`.
- **إشارة الطيّ (لكل نوع):**
  - مجموعة (`isGroupSummaryRow`) → `areChildrenExpanded() == false`
  - مفرد → `isShowingExpanded() == false`
- **الإجراء:** `onKG ? clickExpandSilentlyOnKeyguard(row, btn) : btn.performClick()`؛ وإن لم يُعثر على سهم → `setUserExpanded(true, true)`.

**قواعد ثابتة لا تُكسر هنا:**
1. `isGroupExpanded()` **غير موجود** على هذا البناء (`NoSuchMethodError`)، و`isExpanded()` بلا وسائط يرمي أيضًا (التوقيع الحالي `isExpanded(boolean)`).
2. لا يُمسح `aeAutoExpanded` داخل `post` — محاولة "الشفاء الذاتي" السابقة كانت تمسحه في كل إطار فتحدث حلقة تغذية راجعة وتُفسد المجموعات.

---

#### `[3]` `NotificationChildrenContainer.addNotification(row, int)` — سطر 334

- **النوع:** `afterHookedMethod`.
- **متى:** عند ارتباط ابن جديد بمجموعة.
- **الفرع الفعّال:** **فقط** إذا كان الأب `mIsHeadsUp == true`.
- **الحرّاس:** `expand_headsup_enabled` + kill-switch `disable_headsup_hooks_enabled`.
- **الإجراء:** `aeIsGroup = true` → `parent.setUserExpanded(true, true)` → `collapseChildNoAnim(child)`.
- **الهدف:** طيّ الابن **قبل** أول رسم، لإزالة وميض الإطار الموسّع.
- ملاحظة: مسار الـ Shade/LS متروك تمامًا لهذا الخطاف بلا فرع — النظام يرسم الأبناء مطويين طبيعيًا عندما يكون الأب موسّعًا.

---

#### `[4]` `ExpandableNotificationRow.setHeadsUp(boolean)` — سطر 617

| الطور | الإجراء |
|---|---|
| `before(true)` | `aeCollapsed = false`؛ `aeIsGroup = false` إن لم يكن مضبوطًا |
| `before(false)` | تصفير `aeIsGroup` و `aeAutoExpanded` (الصف عائد لمخزون اللوحة) |
| `after(true)` | إن لم يكن `aeIsGroup` ⇒ `expandGroupIfNeeded(rowView, row, pkg)` |

---

#### `[5]` `NotificationContentView.calculateVisibleType()` — سطر 517

- **النوع:** `afterHookedMethod`.
- **الشرط:** `mIsHeadsUp == true` **و** النتيجة `== 2` (المطوي).
- **يخرج** إذا كان الصف مجموعة (`aeIsGroup` أو `isGroupSummaryRow`) أو `aeCollapsed`.
- **الإجراء:** `param.result = 1` (الطفل الموسّع) إن كان `mExpandedChild != null`.
- **مسار الإنقاذ (سطر 539):** إذا لم يكن `aeIsGroup` لكن الصف مجموعة فعليًا — أي ROM لا نستطيع خطف `NotificationChildrenContainer.addNotification` فيه (مثل Xiaomi 17) — يُستدعى `expandGroupIfNeeded` هنا.

---

#### `[6]` `ExpandableNotificationRow.getIntrinsicHeight()` — سطر 558

- HUD + **غير مجموعة** + `!aeCollapsed` فقط.
- `result = mPrivateLayout.mExpandedChild.measuredHeight` (إن كان > 0 ومختلفًا).

#### `[7]` `ExpandableNotificationRow.getPinnedHeadsUpHeight(boolean)` — سطر 590

- نفس الحرّاس.
- `result = max(result, getMaxExpandHeight())`.

> **`[5]` و`[6]` و`[7]` هي أماكن فرض الأبعاد الوحيدة في المشروع، وهي مقصورة على الـ HUD المفرد.** المجموعات في كل الحالات، وكل شيء في الـ Shade/LS، يُترك للنظام ليحسب أبعاده. انظر §7.1.

---

#### `[8]` `ExpandableNotificationRow.setExpandedWhenPinned(...)` — سطر 657

- `hookAllMethods` + `beforeHookedMethod`.
- يخرج إن كان `aeIsGroup`.
- `aeCollapsed = !args[0]` — مزامنة حالتنا **قبل** تشغيل الأصل، لتقرأ خطافات الارتفاع القيمة الصحيحة.

---

#### `[9]` `OplusHeadsUpTouchHelper.onInterceptTouchEvent(MotionEvent)` — سطر 679

- الصنف: `com.oplus.systemui.notification.headsup.windowframe.OplusHeadsUpTouchHelper` (خاص بـ OPlus؛ يفشل التثبيت بصمت على غيرها).
- البوابة: `disable_headsup_popup_enabled`.
- `ACTION_DOWN` → تسجيل `f1DownStartY` وتصفير الأعلام.
- `ACTION_MOVE` (بشرط `param.result == true`) و `dy > 10` → `f1IsDownwardSwipe = true`، `f1SwipeTime = now`، `f1CurrentRow = getMTouchingHeadsUpView()`.
- **تتبّع سلبي فقط** — لا يعدّل نتيجة الدالة.

#### `[10]` `StatusBarNotificationActivityStarter` — سطر 715

- `hookAllMethods` + `beforeHookedMethod` على `onNotificationClicked` و `startNotificationIntent`.
- الشرط: سحبة للأسفل حديثة (`f1IsDownwardSwipe && age < 2000 ms`).
- **الإجراء:** `param.result = null` ⇒ إلغاء فتح التطبيق/النافذة المصغّرة، ثم `toggleHeadsUpExpandState(row)` مرة واحدة (`f1HasToggled`).
- **استخراج الصف:** `args[1]` → أو أول وسيط اسم صنفه يحوي `ExpandableNotificationRow` → أو `NotificationEntry.getRow()` → أو `f1CurrentRow`.

#### `[11]` `com.android.internal.widget.MessagingTextMessage.setMaxDisplayedLines(int)` — سطر 779

- تُستدعى من `MessagingLinearLayout.onMeasure` بقيمة `Integer.MAX_VALUE` عندما نفرض التوسيع الكامل.
- البوابات: `expand_headsup_enabled` مفعّل **و** kill-switch مطفأ.
- `maxLines = getIntPref("headsup_max_lines", 5)`؛ **0 = بلا حد** (يخرج).
- `if (args[0] > maxLines) args[0] = maxLines`.

#### `[12]` `<listener>.onExpandClicked(...)` — ديناميكي، سطر 133

- يُثبَّت **كسولًا** من أول نسخة مستمع نرصدها في `clickExpandSilentlyOnKeyguard`.
- `beforeHook`: إن كان `uptimeMillis() < lsAutoExpandUntil` ⇒ `param.setResult(null)`.
- **السبب:** على شاشة القفل، الضغط على السهم يستدعي `CentralSurfaces.onExpandClicked` → `goToLockedShade` → ستارة تعتيم + طلب فتح القفل. هذا الخطاف يقتل الأثر الجانبي عند مصدره، ويغطّي أي إرسال مؤجَّل/غير متزامن — بينما تفريغ الحقل يغطّي النقرة المتزامنة فقط.

---

### 5.4 مصفوفة الإعدادات والبوابات

| المفتاح | الافتراضي | يتحكّم في |
|---|---|---|
| `expand_shade_enabled` | ON | `[1]` `[2]` عندما `!onKeyguard` |
| `expand_lockscreen_enabled` | ON | `[1]` `[2]` عندما `onKeyguard` |
| `expand_headsup_enabled` | ON | `[3]` `[4]` `[5]` `[6]` `[7]` `[11]` |
| `disable_headsup_popup_enabled` | ON | `[9]` `[10]` |
| `headsup_max_lines` | `"5"` (0 = بلا حد) | `[11]` |
| `excluded_apps` | `{}` | كل خطاف يمرّ بـ `shouldSkipNotification` |
| `disable_headsup_hooks_enabled` | **OFF** (kill-switch) | يمنع `expandGroupIfNeeded` وفرع HU في `[3]` و`[11]` |
| `ungroup_notifications_enabled` | ON | **لا شيء — لا يقرؤه أي خطاف** (انظر §8.1) |

**سلوك الـ kill-switch دقيق ومقصود:** عند تفعيله، تبقى **الإشعارات المفردة** في الـ HUD تتوسّع طبيعيًا؛ ما يتوقّف هو مسار **مجموعات** الـ HUD فقط (يبقى الأب على السلوك الافتراضي للـ ROM). لهذا `shouldSkipNotification` لا يستشير هذا المفتاح — التعليق عند السطر 229 يوثّق ذلك صراحةً.

---

## 6. السيناريوهات الكاملة

### 6.1 إشعار مفرد يصل واللوحة مغلقة، ثم يفتح المستخدم اللوحة

1. `setHeadsUp(true)` → `[4] before`: `aeCollapsed = false`.
2. `[4] after` → `expandGroupIfNeeded` → `childCount == 0` ⇒ خروج فوري (ليس مجموعة).
3. `[5] calculateVisibleType` يُرجع 2 → نحوّلها إلى 1 (الموسّع).
4. `[6]`/`[7]` يرفعان ارتفاع البانر ليطابق الطفل الموسّع.
5. `[11]` يقصّ الأسطر إلى 5.
6. انتهاء الـ HUD → `setHeadsUp(false)` → تصفير `aeIsGroup` و `aeAutoExpanded`.
7. فتح اللوحة → `onLayout` `[2]` → `isShowingExpanded() == false` → `performClick` على السهم.
8. إعادة الفتح لاحقًا → فجوة > 300 ms → تصفير `aeAutoExpanded` → توسيع مرة أخرى (نقرة واحدة بالضبط لكل فتحة).

### 6.2 رسالتان من WhatsApp (مجموعة) في الـ HUD

1. أول رسالة: مسار المفرد أعلاه (`FLAG_GROUP_SUMMARY` قد يكون موجودًا لكن `childCount == 0`، فيُعامَل كمفرد — هذا مقصود، انظر التعليق في `isGroupSummaryRow` سطر 55).
2. وصول الابن → `[3] addNotification` → `aeIsGroup = true`، توسيع الأب، طيّ الابن قبل الرسم.
3. `[5]` `[6]` `[7]` **تخرج فورًا** لأن `aeIsGroup` — النظام يقيس المجموعة بنفسه.
4. على ROM بلا `NotificationChildrenContainer` مخطوف → مسار الإنقاذ داخل `[5]` يستدعي `expandGroupIfNeeded`.

### 6.3 مجموعة على شاشة القفل

1. `onLayout` `[2]` → `onKG == true` → المفتاح `expand_lockscreen_enabled`.
2. `areChildrenExpanded() == false` ⇒ مطوي.
3. `clickExpandSilentlyOnKeyguard`:
   - `beginLsAutoExpandWindow()` → 400 ms.
   - `ensureOnExpandClickedHook(listener)` → تثبيت `[12]` (أول مرة فقط).
   - تفريغ حقل المستمع → `performClick()` → إعادة الحقل في `finally`.
4. لا ستارة تعتيم ولا طلب فتح قفل.

### 6.4 سحب الأصبع للأسفل على الـ HUD

1. `[9]` يرصد `dy > 10` ⇒ `f1IsDownwardSwipe = true` + يحفظ الصف.
2. يحاول النظام فتح التطبيق → `[10]` يلغيها (`result = null`).
3. `toggleHeadsUpExpandState(row)`:
   - مجموعة → `findExpandButton().performClick()`.
   - مفرد → قلب `aeCollapsed`، ضبط `mExpandedWhenPinned`، ثم `setActualHeight(getIntrinsicHeight())` + `requestLayout()` على الصف وأبيه.

---

## 7. الدروس المستفادة وأخطاء الجولات السابقة

هذا القسم مستخرَج من ذاكرة المشروع (`feedback_notif_no_size_force`, `reference_notif_expand_mechanism`, `project_v320_plan`, `reference_hook_technical`). **اقرأه قبل أي تعديل.**

### 7.1 ❌ لا تفرض أبعاد الإشعار — القاعدة الذهبية

> المستخدم صحّح هذا مرارًا (وبانفعال): *"DID YOU FORCE THE BUILD OF SIZE OF NOTIF! there is conflict, let the sys build the size"*، ولاحقًا *"again you force build the notification size so the look of notification fucked up in shade even heads up and ls"*.

**القاعدة:** مهمّتنا هي قيادة **حالة** التوسيع فقط (أب موسّع، أبناء مطويون سطرًا واحدًا)، ويترك لـ SystemUI حساب الأبعاد.

- ✅ مسموح: تجاوزات `getIntrinsicHeight` / `getPinnedHeadsUpHeight` / `calculateVisibleType` **المقصورة على `mIsHeadsUp` والتي تتخطّى المجموعات** — هذا هو سلوك 3.1.0 الأصلي.
- ❌ ممنوع: أي فرض أبعاد جديد، وأي `setChildrenExpanded` على ملخّص المجموعة في الـ Shade/LS (كسر الشكل البصري فعليًا).
- ✅ توسيع مجموعة الـ Shade/LS يتم عبر `setUserExpanded(true, true)` على الملخّص، **لا** `setChildrenExpanded`.

### 7.2 ❌ `setUserExpanded` وحده لا يثبت

`setUserExpanded(true, true)` يُرجع `true` لكن لا يصمد على المجموعات ومتقلّب على المفردات. الشيء الوحيد الفوري والموثوق هو `performClick()` على `NotificationExpandButton` الحقيقي. `setUserExpanded` مسموح كخطة بديلة فقط.

### 7.3 ❌ `setSystemExpanded` وحده لا يكفي

كان هذا سبب علّة "يعمل عند أول سحبة فقط / يعمل عندما أحذف إشعارًا": `setSystemExpanded` يُطلق عند نشر الإشعار أو تغيّر المجموعة فقط، لا عند إعادة فتح اللوحة. الحل كان اكتشاف أن `onLayout` يُطلق في كل فتحة. **لا تُزل خطاف `onLayout`.** وأيضًا: **لا تفصل المجموعات خارج `setSystemExpanded`** — جُرِّب وأدّى لانحدار في كل الحالات الستّ.

### 7.4 ❌ فخّ الشفاء الذاتي الذي أفسد المجموعات

نسخة سابقة كانت تمسح `aeAutoExpanded` بناءً على `isExpanded()` بلا وسائط. تلك الدالة **ترمي دائمًا** على هذا البناء، فكان الشرط يتحقّق في كل إطار ⇒ إعادة تطبيق مستمرة ⇒ "كل ابن يصبح أبًا مستقلًا". البديل الصحيح: **تصفير الفجوة الهادئة 300 ms فقط**.

لماذا 300 ms تحديدًا: الإطلاقات داخل الفتحة الواحدة تتجمّع في ≤ ~205 ms، بينما إعادة الفتح الحقيقية تبعد ≥ ~300 ms.

### 7.5 ❌ لا تخمّن أسماء الأصناف/الدوال

- `isGroupExpanded()` غير موجود → `NoSuchMethodError`.
- `isExpanded()` بلا وسائط غير موجود (التوقيع `isExpanded(boolean)`).
- المنهجية المطلوبة من المستخدم: **LogCat أولًا** — خطاف استطلاعي للقراءة فقط لمعرفة ماذا يُطلق ومتى، ثم التنفيذ، ثم اختبار المستخدم على الجهاز. الطريقة التي حلّت المشكلة فعليًا: خطاف `onVisibilityAggregated`/`onAttachedToWindow`/`onLayout` للقراءة فقط لمعرفة أيّها يُطلق في كل فتحة، ثم استطلاع ثانٍ لمعرفة أي دالة تُبلّغ حالة الطيّ بصدق.

### 7.6 ❌ سهم OEM ليس السهم الصحيح

- `oplus_expand_button_pill` غير قابل للنقر.
- `alternate_expand_target` يوجّه إلى فتح التطبيق على بعض الـ ROMs.
- استخدم `findStrictExpandButton` (صنف + id + package) في مسارات الـ Shade/LS.

### 7.7 ⚠️ شاشة القفل — قيد OEM مؤجَّل

على OxygenOS 16، شاشة القفل **تفكّ تجميع** الإشعارات: تظهر المجموعة ~1.4 ثانية ثم تتسطّح إلى صفوف مستقلة (نفس كائنات الصفوف تقلب `isSummary`/`childCnt` إلى false). لذلك لا يمكن الحفاظ على "أب موسّع + أبناء سطر واحد" على القفل دون مصارعة أنيميشن OEM. حالة 3.1.0 على القفل كانت تبدو موسّعة فقط بالانتقال من فتحة لوحة سابقة. **مؤجَّل عمدًا.**

### 7.8 ⚠️ IPC — `/data/local/tmp` مات على الأجهزة الحديثة

على OnePlus/OxygenOS 16 + LSPosed 2.0، عمليات الخطاف تحصل على `EACCES` عند قراءة/كتابة `/data/local/tmp`. الحل المطبَّق: نشر كامل الإعدادات (base64) في `Settings.Global["ae_prefs_json"]`. **ملاحظة مهمة:** لأن `isFeatureEnabled` يعطي `true` افتراضيًا عند فراغ الذاكرة المؤقتة، فإن فشل الـ IPC **لم** يعطّل التوسيع — لا تشخّص أعطال التوسيع على أنها مشكلة IPC قبل التحقق.

### 7.9 ⚠️ قواعد Xposed عامة (من `reference_hook_technical`)

- استخدم `param.setResult(value)` دائمًا — لا `param.result = null` على الدوال `void` (لا يضبط `returnEarly`).
- `XposedBridge.log()` **لا يظهر** في `adb logcat`؛ استخدم `Log.d(...)`.
- `hookAllMethods` قد يُرجع مجموعة فارغة بصمت — يفحص `getDeclaredMethods()` على الصنف المحدَّد فقط.
- **لا تخطف `ClassLoader.loadClass` داخل SystemUI أبدًا** — انهيار/قتل watchdog مؤكَّد.
- لا تتنقّل في `declaredMethods` للاكتشاف — قد ترمي `NoClassDefFoundError` على أصناف OEM.
- مخزن logcat يدور خلال ~12 ثانية تحت ضجيج OEM — استخدم بثًّا مفلترًا بالوسم في الخلفية، لا `logcat -d`.

---

## 8. الديون التقنية والملاحظات المفتوحة

### 8.1 `ungroup_notifications_enabled` — مفتاح ميّت

- معرَّف في `MainActivity.kt:130,138-139`، ومعروض في الواجهة `SettingsScreens.kt:100,347-352` مع نصوص كاملة في `strings.xml:28-29`.
- **لا يقرؤه أي خطاف.** بحث نصّي في كل ملفات `.kt` لا يُظهر أي استهلاك.
- بالنسبة للمستخدم: التبديل يظهر ويُحفظ ولا يفعل شيئًا. قرار مطلوب: تنفيذه، أو إخفاؤه، أو حذفه.

### 8.2 رسالة "reboot required" مضلِّلة جزئيًا

`onToggle` (`SettingsScreens.kt:193`) يعرض `rebootMsg` عند كل تبديل، بينما `PrefsBridge` يلتقط القيم حيًّا خلال ≤ 2 ثانية. إعادة التشغيل لازمة فقط عند تحديث الموديول نفسه (كود خطاف جديد).

### 8.3 اعتماد `getIntrinsicHeight` على قيمة سابقة داخل `expandGroupIfNeeded`

`expandGroupIfNeeded` يقرأ `getIntrinsicHeight` للأبناء لأغراض السجلات فقط (سطر 275, 279) — لا أثر وظيفي، لكنه استدعاء انعكاسي إضافي في مسار ساخن.

### 8.4 كثافة السجلات

الوسم `TweaksHud` يطبع في `onLayout` و `calculateVisibleType` و `addNotification` — أي في مسارات تُنفَّذ عشرات المرات في الثانية. لا يوجد بوابة تشغيل/إيقاف للسجلات في مسار الإشعارات (على عكس `DebugLogHelper`).

### 8.5 صنف OPlus مكتوب صراحةً

`[9]` مربوط بـ `com.oplus.systemui.notification.headsup.windowframe.OplusHeadsUpTouchHelper`. على غير أجهزة OPlus يفشل التثبيت بصمت، فتتعطّل ميزة `disable_headsup_popup_enabled` كاملة دون أي مؤشر للمستخدم.

---

## 9. التشخيص عبر LogCat

| الوسم | المصدر | المحتوى |
|---|---|---|
| `TweaksHud` | `NotificationExpander` | كل أحداث التوسيع، مع ختم زمني `[ms]` — `addNotification ENTER/EXIT`, `expandGroupIfNeeded`, `shade/LS post-expand`, `onLayout expand`, `calcVisibleType`, `setHeadsUp(true/false)` |
| `TweaksLS` | مسار شاشة القفل | `LS silent expand`, `onExpandClicked BLOCKED`, `expandClick listener field = …` |
| `AutoExpand` | `MainHook` | أخطاء تهيئة الخطاف |
| `Snapper` | `PrefsBridge` | تشخيص الـ IPC — `DIAG: SystemUI hook init`, `DIAG: prefs published`, `DIAG: FileObserver started` |

**أمر مقترح:**
```
adb logcat -s TweaksHud:D TweaksLS:D AutoExpand:E
```

**قراءة سريعة للحالة الصحيحة لمجموعة في الـ HUD:**
```
setHeadsUp(true) row=… children=0 aeGroup=false
addNotification ENTER parent=… parentHU=true childIdx=1 match=true
addNotification EXIT  parent=… branch=HU ok=true childH=…
expandGroupIfNeeded ENTER row=… count=1
children collapsed row=… stateSet=1 clicked=0 total=1
```

---

## 10. الخلاصة التنفيذية

1. **محرّك واحد يقود كل شيء في الـ Shade/LS:** `onLayout` + `performClick` على السهم الحقيقي، مع one-shot وفجوة هادئة 300 ms.
2. **مسار HUD منفصل تمامًا:** يعتمد على `setHeadsUp` و `addNotification` و حقول `ae*` الخاصة، وهو **المكان الوحيد** الذي نفرض فيه أبعادًا — وللمفردات فقط.
3. **شاشة القفل تُعامَل كحالة خاصة** بدفاع مزدوج ضد أثر `onExpandClicked` الجانبي.
4. **الإعدادات تصل عبر ثلاث قنوات** بأولوية `Settings.Global` ثم الملف ثم `XSharedPreferences`، بذاكرة مؤقتة ثانيتين.
5. **القيود الصارمة:** لا فرض أبعاد جديد، لا تخمين أسماء، لا اعتماد على `setUserExpanded` وحده، لا إزالة خطاف `onLayout`، LogCat قبل الكود.
