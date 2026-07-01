package io.github.kvmy666.autoexpand

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.graphics.Color
import java.io.File

// =============================================================================
// Status Bar Zones Tab
// =============================================================================

// File-level class — NOT inside the composable to avoid Kotlin local-class
// descriptor instability across recompositions.
internal class ZonePreviewHolder {
    var left:  View? = null
    var right: View? = null
}

internal fun zonePreviewParams(w: Int, h: Int, x: Int) = WindowManager.LayoutParams(
    w, h,
    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
    PixelFormat.TRANSLUCENT
).apply { gravity = Gravity.TOP or Gravity.START; this.x = x; y = 0 }

@Suppress("LongParameterList")
@Composable
internal fun ZonesTab(
    prefs: SharedPreferences,
    zonesEnabled: Boolean,
    zonesPreviewActive: Boolean,
    zonesLeftPct: Int,
    zonesRightPct: Int,
    zLSingle: String, zLDouble: String, zLTriple: String, zLLong: String,
    zRSingle: String, zRDouble: String, zRTriple: String, zRLong: String,
    zLAppPkg: String, zRAppPkg: String,
    zLShortcuts: Map<String, String>,
    zRShortcuts: Map<String, String>,
    onEnabledChange: (Boolean) -> Unit,
    onPreviewActiveChange: (Boolean) -> Unit,
    onLeftPctChange: (Int) -> Unit,
    onRightPctChange: (Int) -> Unit,
    onPreviewDone: () -> Unit,
    onActionChange: (prefKey: String, value: String, appPkg: String?) -> Unit,
    onShortcutChange: (shortcutPrefKey: String, shortcutData: String) -> Unit,
) {
    val context = LocalContext.current
    // Use application context — Activity context WindowManager can produce
    // BadTokenException on second open when the previous Activity token is stale.
    val wm = remember { context.applicationContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager }

    // Compute display geometry once
    val screenW = remember { wm.currentWindowMetrics.bounds.width() }
    val sbHeightPx = remember {
        val h = wm.currentWindowMetrics.windowInsets.getInsets(WindowInsets.Type.statusBars()).top
        if (h > 0) h else {
            val rid = context.resources.getIdentifier("status_bar_height", "dimen", "android")
            if (rid > 0) context.resources.getDimensionPixelSize(rid) else 80
        }
    }
    val cutout = remember { wm.currentWindowMetrics.windowInsets.displayCutout }
    // maxLeftW / maxRightW: max zone widths in px, using bounding rect for centered punch-holes
    val (maxLeftW, maxRightW) = remember {
        if (cutout == null) {
            Pair(screenW * 3 / 10, screenW * 3 / 10)
        } else {
            val topRect = cutout.boundingRects.minByOrNull { it.top }
            if (topRect != null && topRect.left > 0) {
                Pair(topRect.left, screenW - topRect.right)
            } else {
                val cl = cutout.safeInsetLeft.takeIf { it > 0 } ?: (screenW * 3 / 10)
                val cr = cutout.safeInsetRight.takeIf { it > 0 } ?: (screenW * 3 / 10)
                Pair(cl, cr)
            }
        }
    }

    // File-level holder — stable across recompositions
    val holder = remember { ZonePreviewHolder() }

    fun makePreviewView(fillColor: Int, borderColor: Int): View {
        val fill   = Paint().apply { color = fillColor;   style = Paint.Style.FILL }
        val border = Paint().apply { color = borderColor; style = Paint.Style.STROKE; strokeWidth = 4f }
        return object : View(context.applicationContext) {
            override fun onDraw(c: Canvas) {
                c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fill)
                c.drawRect(2f, 2f, width - 2f, height - 2f, border)
            }
        }.apply { setLayerType(View.LAYER_TYPE_HARDWARE, null) }
    }

    // Create/destroy preview views when active changes
    DisposableEffect(zonesPreviewActive) {
        if (zonesPreviewActive) {
            if (!android.provider.Settings.canDrawOverlays(context)) {
                return@DisposableEffect onDispose {}
            }
            // Clean up any stale views from a previous session that onDispose may not have caught
            holder.left?.let  { v -> try { wm.removeView(v) } catch (_: Throwable) {} ; holder.left  = null }
            holder.right?.let { v -> try { wm.removeView(v) } catch (_: Throwable) {} ; holder.right = null }

            val lw = (screenW * zonesLeftPct  / 100f).toInt().coerceIn(1, maxLeftW.coerceAtLeast(1))
            val rw = (screenW * zonesRightPct / 100f).toInt().coerceIn(1, maxRightW.coerceAtLeast(1))
            val lv = makePreviewView(
                android.graphics.Color.argb(110, 0, 120, 255),
                android.graphics.Color.argb(220, 0, 120, 255)
            )
            val rv = makePreviewView(
                android.graphics.Color.argb(110, 0, 190, 100),
                android.graphics.Color.argb(220, 0, 190, 100)
            )
            try {
                wm.addView(lv, zonePreviewParams(lw, sbHeightPx, 0))
                holder.left = lv
            } catch (t: Throwable) { Log.e("Zones", "preview left addView failed: $t") }
            try {
                wm.addView(rv, zonePreviewParams(rw, sbHeightPx, screenW - rw))
                holder.right = rv
            } catch (t: Throwable) { Log.e("Zones", "preview right addView failed: $t") }
        }
        onDispose {
            holder.left?.let  { v -> try { wm.removeView(v) } catch (_: Throwable) {} ; holder.left  = null }
            holder.right?.let { v -> try { wm.removeView(v) } catch (_: Throwable) {} ; holder.right = null }
        }
    }

    // Resize preview views in real-time as sliders change (runs after every recomposition)
    SideEffect {
        if (!zonesPreviewActive) return@SideEffect
        val lw = (screenW * zonesLeftPct  / 100f).toInt().coerceIn(1, maxLeftW.coerceAtLeast(1))
        val rw = (screenW * zonesRightPct / 100f).toInt().coerceIn(1, maxRightW.coerceAtLeast(1))
        holder.left?.let  { v -> try { wm.updateViewLayout(v, zonePreviewParams(lw, sbHeightPx, 0)) }            catch (_: Throwable) {} }
        holder.right?.let { v -> try { wm.updateViewLayout(v, zonePreviewParams(rw, sbHeightPx, screenW - rw)) } catch (_: Throwable) {} }
    }

    // App picker ──────────────────────────────────────────────────────────────
    data class AppEntry(val name: String, val pkg: String)
    var appPickerFor by remember { mutableStateOf<String?>(null) }
    val appList      = remember { mutableStateListOf<AppEntry>() }

    LaunchedEffect(appPickerFor) {
        if (appPickerFor == null || appList.isNotEmpty()) return@LaunchedEffect
        val entries = withContext(Dispatchers.IO) {
            context.packageManager.getInstalledApplications(0)
                .filter { it.packageName != context.packageName }
                .map { AppEntry(context.packageManager.getApplicationLabel(it).toString(), it.packageName) }
                .sortedBy { it.name.lowercase() }
        }
        appList.addAll(entries)
    }

    if (appPickerFor != null) {
        AlertDialog(
            onDismissRequest = { appPickerFor = null },
            title = { Text("Pick an App") },
            text = {
                LazyColumn {
                    items(appList) { app ->
                        Text(
                            text = "${app.name}\n${app.pkg}",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val key = appPickerFor ?: return@clickable
                                    onActionChange(key, "open_app", app.pkg)
                                    appPickerFor = null
                                }
                                .padding(vertical = 8.dp, horizontal = 4.dp)
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { appPickerFor = null }) { Text("Cancel") } }
        )
    }

    // Shortcut picker ─────────────────────────────────────────────────────────
    data class ShortcutEntry(
        val packageName: String,
        val shortcutId: String,
        val label: String,
        val appName: String,
    )

    var shortcutPickerFor by remember { mutableStateOf<String?>(null) }
    val shortcutList = remember { mutableStateListOf<ShortcutEntry>() }
    var shortcutSearch by remember { mutableStateOf("") }
    var shortcutsLoaded by remember { mutableStateOf(false) }
    var shortcutTimedOut by remember { mutableStateOf(false) }

    LaunchedEffect(shortcutPickerFor) {
        if (shortcutPickerFor == null) return@LaunchedEffect
        shortcutList.clear(); shortcutSearch = ""; shortcutsLoaded = false; shortcutTimedOut = false
        var dbError = false
        val diag = StringBuilder("=== Anywhere Picker Diagnostic ===\n")

        fun runSu(cmd: String): Triple<Int, String, String> = try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
            val out = p.inputStream.bufferedReader().readText()
            val err = p.errorStream.bufferedReader().readText()
            val exit = p.waitFor(); p.destroy()
            Triple(exit, out, err)
        } catch (t: Throwable) { Triple(-1, "", t.toString()) }

        // Parse one Row line from `content query` output.
        // Format: "Row: N _id=VALUE, app_name=VALUE, param_1=..., param_2=..., description=..."
        // Projection flag is often ignored by the content tool, so all columns may appear.
        // We only need _id and app_name — find each value by stopping at the next ", word=" boundary.
        fun parseContentRow(line: String): Triple<String, String, String>? {
            val row = line.substringAfter("Row:").trimStart().dropWhile { it.isDigit() }.trimStart()
            val idStart   = row.indexOf("_id=");        if (idStart < 0) return null
            val nameStart = row.indexOf(", app_name="); if (nameStart < 0) return null
            val id = row.substring(idStart + 4, nameStart).trim()
            val nameValueStart = nameStart + 11  // skip ", app_name="
            // Stop at the next ", identifier=" — works regardless of column order
            val nameEnd = Regex(",\\s*[A-Za-z_]\\w*=").find(row, nameValueStart)?.range?.first ?: row.length
            val name = row.substring(nameValueStart, nameEnd).trim()
            if (id.isBlank() || id == "NULL" || name.isBlank()) return null
            return Triple(id, name, "")
        }

        val entries = withContext(Dispatchers.IO) {
            // ── Stage 1: ContentProvider query via root 'content' CLI ────────────────
            // Anywhere exports a ContentProvider (authority: com.absinthe.anywhere_.coreprovider,
            // table: anywhere_table, id col: _id, name col: app_name).
            // Running via 'su -c content query' bypasses the READ permission entirely —
            // no SELinux file access, no DB copying, works even if Anywhere is not running.
            val authority = "com.absinthe.anywhere_.coreprovider"
            val (cpExit, cpOut, cpErr) = runSu(
                "content query --uri 'content://$authority/anywhere_table'" +
                " --projection '_id:app_name:description' 2>&1"
            )
            diag.appendLine("Stage1 exit=$cpExit")
            if (cpErr.isNotBlank()) diag.appendLine("  err: ${cpErr.take(300)}")
            diag.appendLine("  out: ${cpOut.take(600)}")

            val cpEntries = cpOut.lines()
                .filter { it.trimStart().startsWith("Row:") }
                .mapNotNull { line ->
                    val (id, name, desc) = parseContentRow(line) ?: return@mapNotNull null
                    ShortcutEntry("com.absinthe.anywhere_", id, name, desc)
                }
            diag.appendLine("Stage1 parsed: ${cpEntries.size} entries")

            if (cpEntries.isNotEmpty()) {
                return@withContext cpEntries.sortedBy { it.label.lowercase() }
            }

            // ── Stage 2: Direct SQLite fallback ──────────────────────────────────────
            // Only reached if ContentProvider returned 0 rows.
            // Anywhere's Room DB is named "anywhere_database" (no .db extension).
            diag.appendLine("Stage1 empty — trying SQLite fallback")
            var cacheDb: java.io.File? = null
            try {
                val (pmExit, pmOut, _) = runSu("pm list packages -U 2>&1 | grep anywhere")
                val anywhereUid = pmOut.trim().substringAfter("uid:", "").trim().takeIf { it.isNotBlank() }
                diag.appendLine("uid from pm: $anywhereUid")

                val srcDbPath = "/data/user/0/com.absinthe.anywhere_/databases/anywhere_database"
                val tmpPath   = "/data/local/tmp/anywhere_db_tmp"
                val lsCmd = if (anywhereUid != null)
                    "su $anywhereUid -c 'ls $srcDbPath 2>&1'" else "ls $srcDbPath 2>&1"
                val (lsExit, lsOut, _) = runSu(lsCmd)
                diag.appendLine("ls db: exit=$lsExit out='${lsOut.trim()}'")

                if (lsExit != 0) {
                    diag.appendLine("FALLBACK FAIL: DB not at expected path"); dbError = true
                    return@withContext emptyList<ShortcutEntry>()
                }

                val cpAsUid = if (anywhereUid != null)
                    "su $anywhereUid -c 'cp $srcDbPath $tmpPath' && chmod 644 $tmpPath"
                else "cp $srcDbPath $tmpPath && chmod 644 $tmpPath"
                val (cpTmpExit, _, cpTmpErr) = runSu(cpAsUid)
                diag.appendLine("cp to tmp: exit=$cpTmpExit err='${cpTmpErr.trim()}'")

                cacheDb = java.io.File(context.cacheDir, "anywhere_tmp.db")
                val (cpCacheExit, _, cpCacheErr) = runSu(
                    "cp $tmpPath '${cacheDb.absolutePath}' && chmod 644 '${cacheDb.absolutePath}' && rm -f $tmpPath"
                )
                diag.appendLine("cp to cache: exit=$cpCacheExit err='${cpCacheErr.trim()}'")

                if (cpCacheExit != 0 || !cacheDb.exists()) {
                    diag.appendLine("FALLBACK FAIL: copy to cache failed"); dbError = true
                    return@withContext emptyList<ShortcutEntry>()
                }

                val db = android.database.sqlite.SQLiteDatabase.openDatabase(
                    cacheDb.absolutePath, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY
                )
                val cur = db.rawQuery("SELECT _id, app_name, description FROM anywhere_table", null)
                val results = mutableListOf<ShortcutEntry>()
                while (cur.moveToNext()) {
                    val id   = cur.getString(0)?.trim()?.takeIf { it.isNotBlank() } ?: continue
                    val name = cur.getString(1)?.trim()?.takeIf { it.isNotBlank() } ?: continue
                    val desc = cur.getString(2)?.trim() ?: ""
                    results.add(ShortcutEntry("com.absinthe.anywhere_", id, name, desc))
                }
                cur.close(); db.close()
                diag.appendLine("Fallback rows: ${results.size}")
                if (results.isEmpty()) dbError = true
                results.sortedBy { it.label.lowercase() }
            } catch (t: Throwable) {
                diag.appendLine("EXCEPTION: $t\n${t.stackTraceToString()}"); dbError = true; emptyList()
            } finally {
                try { cacheDb?.delete() } catch (_: Throwable) {}
            }
        }
        try {
            val diagFile = java.io.File(context.getExternalFilesDir(null), "anywhere_diag.txt")
            diagFile.writeText(diag.toString())
        } catch (_: Throwable) {}
        shortcutList.addAll(entries)
        shortcutsLoaded = true
        shortcutTimedOut = dbError
    }

    if (shortcutPickerFor != null) {
        val filteredShortcuts = if (shortcutSearch.isBlank()) shortcutList
            else shortcutList.filter { it.label.contains(shortcutSearch, ignoreCase = true) }

        AlertDialog(
            onDismissRequest = { shortcutPickerFor = null; shortcutSearch = "" },
            title = { Text("Pick an Anywhere Shortcut") },
            text = {
                Column {
                    if (!shortcutsLoaded) {
                        // Loading
                        Box(modifier = Modifier.fillMaxWidth().padding(24.dp),
                            contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    } else if (shortcutTimedOut) {
                        Text(
                            "Could not read Anywhere data. Make sure Anywhere is installed and has been opened at least once.",
                            modifier = Modifier.padding(8.dp),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    } else if (shortcutList.isEmpty()) {
                        val anywhereInstalled = remember {
                            try { context.packageManager.getPackageInfo("com.absinthe.anywhere_", 0); true }
                            catch (_: Exception) { false }
                        }
                        Text(
                            if (anywhereInstalled)
                                "No cards in Anywhere yet. Create a card in Anywhere first, then come back here."
                            else
                                "Anywhere is not installed. Install it from the \"Power up with Anywhere\" card.",
                            modifier = Modifier.padding(8.dp),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    } else {
                        OutlinedTextField(
                            value = shortcutSearch,
                            onValueChange = { shortcutSearch = it },
                            label = { Text("Search") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                        )
                        LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                            items(filteredShortcuts) { sc ->
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            val actionPrefKey = shortcutPickerFor ?: return@clickable
                                            val shortcutPrefKey = actionPrefKey.replace("_action", "_shortcut")
                                            onActionChange(actionPrefKey, "launch_shortcut", null)
                                            // Store pkg::id::label so bound row can show card name
                                            onShortcutChange(shortcutPrefKey,
                                                "${sc.packageName}::${sc.shortcutId}::${sc.label}")
                                            shortcutPickerFor = null
                                            shortcutSearch = ""
                                        }
                                        .padding(vertical = 8.dp, horizontal = 4.dp)
                                ) {
                                    Text(
                                        text = sc.label,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    if (sc.appName.isNotBlank()) {
                                        Text(
                                            text = sc.appName,   // description stored in appName field
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                HorizontalDivider()
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { shortcutPickerFor = null; shortcutSearch = "" }) { Text("Cancel") }
            }
        )
    }

    // Introduction card ───────────────────────────────────────────────────────
    VaultCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Status Bar Zones", style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary)
            Text(
                "Trigger actions by tapping the empty space next to your camera cutout. " +
                "Quick access to flashlight, ringer modes, and any system shortcut — " +
                "without unlocking your phone or opening any app.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    // Anywhere Integration card ───────────────────────────────────────────────
    val anywhereInstalled = remember {
        try { context.packageManager.getPackageInfo("com.absinthe.anywhere_", 0); true }
        catch (_: Exception) { false }
    }
    val anywhereGreen = Color(0xFF4CAF50)
    VaultCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Power up with Anywhere", style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = anywhereGreen)
            Text(
                "Anywhere is a free, open-source app that lets you create shortcuts for almost any action " +
                "on your phone — toggle VPN, run shell commands, launch hidden activities, and much more. " +
                "Combined with Status Bar Zones, you can trigger any of these with a single tap.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (anywhereInstalled) {
                Text("Anywhere is installed ✓",
                    style = MaterialTheme.typography.bodySmall,
                    color = anywhereGreen)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW,
                                Uri.parse("https://github.com/zhaobozhen/Anywhere-/releases/tag/2.5.5"))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }) {
                        Text("Download APK ↗")
                    }
                    Button(onClick = {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/zhaobozhen/Anywhere-"))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }) {
                        Text("GitHub ↗")
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("How to create your first shortcut:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface)
                val steps = listOf(
                    "Open Anywhere → tap the green + button",
                    "Pick a card type: App (Activity), URL Scheme, Shell, Broadcast, etc.",
                    "Configure the card (e.g. pick WireGuard's main activity)",
                    "Tap the green checkmark to save",
                    "Return here → open any gesture dropdown",
                    "Select \"Anywhere — Launch Shortcut...\"",
                    "Pick your card from the list",
                    "Tap the zone to trigger the card — no home screen shortcut needed",
                )
                steps.forEachIndexed { i, step ->
                    Text(
                        text = "${i + 1}. $step",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
                Text(
                    "Done — tap the zone to trigger your shortcut.",
                    style = MaterialTheme.typography.bodySmall,
                    color = anywhereGreen,
                    modifier = Modifier.padding(start = 8.dp, top = 4.dp)
                )
            }
        }
    }

    // Master toggle + size adjust card ────────────────────────────────────────
    VaultCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(vertical = 4.dp)) {
            SectionLabel("Status Bar Zones", accent = true)
            ToggleRow(
                title = "Enable Status Bar Zones",
                description = "Tap zones on left and right of the camera cutout",
                checked = zonesEnabled,
                onCheckedChange = onEnabledChange
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            if (!zonesPreviewActive) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPreviewActiveChange(true) }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text("Adjust Zone Sizes", style = MaterialTheme.typography.bodyLarge)
                        Text("Live colored overlay preview on status bar",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("▶", color = MaterialTheme.colorScheme.primary)
                }
            } else {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text("Left zone: $zonesLeftPct%",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color(0xFF0078FF))
                    Slider(
                        value = zonesLeftPct.toFloat(),
                        onValueChange = { onLeftPctChange(it.toInt().coerceIn(10, 50)) },
                        onValueChangeFinished = {},
                        valueRange = 10f..50f,
                        steps = 39
                    )
                    Text("Right zone: $zonesRightPct%",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color(0xFF00BE64))
                    Slider(
                        value = zonesRightPct.toFloat(),
                        onValueChange = { onRightPctChange(it.toInt().coerceIn(10, 50)) },
                        onValueChangeFinished = {},
                        valueRange = 10f..50f,
                        steps = 39
                    )
                    Button(onClick = onPreviewDone, modifier = Modifier.fillMaxWidth()) {
                        Text("Done — Save Sizes")
                    }
                }
            }
        }
    }

    // Left Zone Actions ───────────────────────────────────────────────────────
    ZoneSideCard(
        sideLabel = "Left Zone",
        singleAction = zLSingle, doubleAction = zLDouble,
        tripleAction = zLTriple, longAction   = zLLong,
        appPkg = zLAppPkg, prefPrefix = "zones_left",
        shortcutDataMap = zLShortcuts,
        onActionChange = onActionChange,
        onPickApp = { appPickerFor = it },
        onPickShortcut = { shortcutPickerFor = it }
    )

    // Right Zone Actions ──────────────────────────────────────────────────────
    ZoneSideCard(
        sideLabel = "Right Zone",
        singleAction = zRSingle, doubleAction = zRDouble,
        tripleAction = zRTriple, longAction   = zRLong,
        appPkg = zRAppPkg, prefPrefix = "zones_right",
        shortcutDataMap = zRShortcuts,
        onActionChange = onActionChange,
        onPickApp = { appPickerFor = it },
        onPickShortcut = { shortcutPickerFor = it }
    )

    // Haptic Feedback ─────────────────────────────────────────────────────────
    var hapticEnabled by remember {
        mutableStateOf(prefs.getString("zones_haptic_enabled", "1") == "1")
    }
    SettingsCard(modifier = Modifier.fillMaxWidth()) {
        ToggleRow(
            title = "Haptic Feedback",
            description = "Vibrate when a zone action fires",
            checked = hapticEnabled,
            onCheckedChange = { v ->
                hapticEnabled = v
                val strVal = if (v) "1" else "0"
                prefs.edit().putString("zones_haptic_enabled", strVal).apply()
                MainActivity.broadcastPref(context, "zones_haptic_enabled", strVal)
                MainActivity.writePrefsFile(context)
            }
        )
    }

}

@Composable
internal fun ZoneSideCard(
    sideLabel: String,
    singleAction: String, doubleAction: String,
    tripleAction: String, longAction:   String,
    appPkg: String,
    prefPrefix: String,
    shortcutDataMap: Map<String, String>,
    onActionChange: (prefKey: String, value: String, appPkg: String?) -> Unit,
    onPickApp: (prefKey: String) -> Unit,
    onPickShortcut: (prefKey: String) -> Unit,
) {
    VaultCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(vertical = 4.dp)) {
            SectionLabel(sideLabel, accent = true)

            val gestures = listOf(
                "single_tap_action" to "Single Tap"   to singleAction,
                "double_tap_action" to "Double Tap"   to doubleAction,
                "triple_tap_action" to "Triple Tap"   to tripleAction,
                "long_press_action" to "Long Press"   to longAction,
            )

            gestures.forEachIndexed { idx, (keyLabel, currentKey) ->
                val (keySuffix, label) = keyLabel
                val prefKey = "${prefPrefix}_${keySuffix}"
                val shortcutData = shortcutDataMap[keySuffix] ?: ""
                ActionDropdownRow(
                    label        = label,
                    currentKey   = currentKey,
                    appPkg       = appPkg,
                    shortcutData = shortcutData,
                    onSelect     = { selected ->
                        when (selected) {
                            "open_app"       -> onPickApp(prefKey)
                            "launch_shortcut" -> onPickShortcut(prefKey)
                            else             -> onActionChange(prefKey, selected, null)
                        }
                    }
                )
                if (idx < gestures.size - 1) {
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                }
            }
            Spacer(modifier = Modifier.padding(bottom = 4.dp))
        }
    }
}

@Composable
internal fun ActionDropdownRow(
    label: String,
    currentKey: String,
    appPkg: String,
    shortcutData: String = "",
    onSelect: (String) -> Unit,
) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }

    // Build the right-side display text
    val displayText: String
    val displayColor: androidx.compose.ui.graphics.Color

    if (currentKey == "launch_shortcut") {
        if (shortcutData.isBlank()) {
            displayText = "Anywhere — Launch Shortcut..."
            displayColor = Color(0xFF4CAF50)
        } else {
            val parts = shortcutData.split("::", limit = 3)
            val pkg   = parts.getOrElse(0) { "" }
            val sid   = parts.getOrElse(1) { "" }
            val lbl   = parts.getOrElse(2) { "" }
            val pkgInstalled = remember(pkg) {
                try { context.packageManager.getPackageInfo(pkg, 0); true }
                catch (_: Exception) { false }
            }
            if (pkgInstalled) {
                displayText = "Anywhere — ${lbl.ifBlank { sid }}"
                displayColor = Color(0xFF4CAF50)
            } else {
                displayText = "Card unavailable — re-bind"
                displayColor = MaterialTheme.colorScheme.error
            }
        }
    } else {
        val baseName = ZoneAction.ALL.firstOrNull { it.first == currentKey }?.second ?: "No Action"
        val appLabel = if (currentKey == "open_app" && appPkg.isNotBlank()) " ($appPkg)" else ""
        displayText = baseName + appLabel
        displayColor = MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = true }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(0.4f))
        Row(
            modifier = Modifier.weight(0.6f),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = displayText,
                style = MaterialTheme.typography.bodyMedium,
                color = displayColor,
                maxLines = 1
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text("▼", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            ZoneAction.ALL.forEach { (key, name) ->
                DropdownMenuItem(
                    text = {
                        Text(name,
                            color = if (key == "launch_shortcut") Color(0xFF4CAF50)
                                    else Color.Unspecified)
                    },
                    onClick = { expanded = false; onSelect(key) }
                )
            }
        }
    }
}
