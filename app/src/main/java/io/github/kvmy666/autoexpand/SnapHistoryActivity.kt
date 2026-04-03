package io.github.kvmy666.autoexpand

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SnapHistoryActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val scheme = if (isSystemInDarkTheme())
                dynamicDarkColorScheme(this) else dynamicLightColorScheme(this)
            MaterialTheme(colorScheme = scheme) {
                SnapHistoryScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SnapHistoryScreen() {
    val context = LocalContext.current
    val db      = remember { SnapHistoryDb(context) }
    var entries by remember { mutableStateOf(db.getAll()) }
    var showClearConfirm by remember { mutableStateOf(false) }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title   = { Text("Clear all snaps?") },
            text    = { Text("This will permanently delete all ${entries.size} saved snaps from storage.") },
            confirmButton = {
                Button(onClick = {
                    showClearConfirm = false
                    entries.forEach { File(it.filePath).delete() }
                    db.deleteAll()
                    entries = emptyList()
                }) { Text("Delete all") }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) { Text("Cancel") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Snap History") },
                actions = {
                    if (entries.isNotEmpty()) {
                        TextButton(onClick = { showClearConfirm = true }) {
                            Text("Clear all")
                        }
                    }
                }
            )
        }
    ) { padding ->
        if (entries.isEmpty()) {
            Box(
                modifier         = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text("No snaps saved yet", style = MaterialTheme.typography.bodyLarge)
            }
        } else {
            LazyVerticalGrid(
                columns                  = GridCells.Fixed(2),
                modifier                 = Modifier.fillMaxSize().padding(padding).padding(4.dp),
                horizontalArrangement    = Arrangement.spacedBy(4.dp),
                verticalArrangement      = Arrangement.spacedBy(4.dp)
            ) {
                items(items = entries, key = { it.id }) { entry ->
                    SnapHistoryItem(
                        entry         = entry,
                        onDelete      = {
                            File(entry.filePath).delete()
                            db.delete(entry.id)
                            entries = db.getAll()
                        },
                        onShare       = {
                            val file = File(entry.filePath)
                            if (file.exists()) {
                                val uri = FileProvider.getUriForFile(
                                    context,
                                    "io.github.kvmy666.autoexpand.fileprovider",
                                    file
                                )
                                context.startActivity(
                                    Intent.createChooser(
                                        Intent(Intent.ACTION_SEND).apply {
                                            type = "image/png"
                                            putExtra(Intent.EXTRA_STREAM, uri)
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }, "Share snap"
                                    ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                                )
                            }
                        },
                        onFloat       = {
                            // Re-open this snap as a floating overlay via SnapperService
                            val svc = Intent(context, SnapperService::class.java).apply {
                                action = SnapperService.ACTION_FLOAT_SNAP
                                putExtra(SnapperService.EXTRA_SNAP_PATH, entry.filePath)
                            }
                            context.startForegroundService(svc)
                            Toast.makeText(context, "Snap pinned as overlay", Toast.LENGTH_SHORT).show()
                        },
                        onOpenGallery = {
                            val file = File(entry.filePath)
                            if (file.exists()) {
                                val uri = FileProvider.getUriForFile(
                                    context,
                                    "io.github.kvmy666.autoexpand.fileprovider",
                                    file
                                )
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW).apply {
                                        setDataAndType(uri, "image/png")
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                                 Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                )
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun SnapHistoryItem(
    entry         : SnapHistoryDb.Entry,
    onDelete      : () -> Unit,
    onShare       : () -> Unit,
    onFloat       : () -> Unit,
    onOpenGallery : () -> Unit,
) {
    val context  = LocalContext.current
    val scope    = rememberCoroutineScope()
    var bitmap   by remember { mutableStateOf<Bitmap?>(null) }
    var showMenu by remember { mutableStateOf(false) }

    val dateStr = remember(entry.timestamp) {
        SimpleDateFormat("MMM d · HH:mm", Locale.getDefault()).format(Date(entry.timestamp))
    }

    LaunchedEffect(entry.filePath) {
        bitmap = withContext(Dispatchers.IO) {
            val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
            BitmapFactory.decodeFile(entry.filePath, opts)
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { showMenu = true }
    ) {
        Box {
            if (bitmap != null) {
                Image(
                    bitmap             = bitmap!!.asImageBitmap(),
                    contentDescription = dateStr,
                    modifier           = Modifier.fillMaxWidth(),
                    contentScale       = ContentScale.Crop
                )
            } else {
                Box(
                    modifier         = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                }
            }

            Text(
                text      = dateStr,
                style     = MaterialTheme.typography.labelSmall,
                color     = Color.White,
                modifier  = Modifier
                    .align(Alignment.BottomCenter)
                    .background(Color(0x99000000))
                    .fillMaxWidth()
                    .padding(4.dp),
                textAlign = TextAlign.Center
            )

            DropdownMenu(
                expanded         = showMenu,
                onDismissRequest = { showMenu = false }
            ) {
                DropdownMenuItem(
                    text    = { Text("Float / Pin") },
                    onClick = { showMenu = false; onFloat() }
                )
                DropdownMenuItem(
                    text    = { Text("Open in Gallery") },
                    onClick = { showMenu = false; onOpenGallery() }
                )
                DropdownMenuItem(
                    text    = { Text("Save to Gallery") },
                    onClick = {
                        showMenu = false
                        scope.launch {
                            val saved = withContext(Dispatchers.IO) {
                                saveEntryToGallery(context, entry.filePath)
                            }
                            Toast.makeText(
                                context,
                                if (saved) "Saved to gallery" else "Save failed",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                )
                DropdownMenuItem(
                    text    = { Text("Share") },
                    onClick = { showMenu = false; onShare() }
                )
                DropdownMenuItem(
                    text    = { Text("Delete") },
                    onClick = { showMenu = false; onDelete() }
                )
            }
        }
    }
}

/** Copies the snap file into the system MediaStore (Pictures/Snapper). */
private fun saveEntryToGallery(context: android.content.Context, filePath: String): Boolean {
    return try {
        val bmp = BitmapFactory.decodeFile(filePath) ?: return false
        val cv  = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "Snap_${System.currentTimeMillis()}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Snapper")
        }
        val uri: Uri? = context.contentResolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)
        uri?.let { context.contentResolver.openOutputStream(it)?.use { s ->
            bmp.compress(Bitmap.CompressFormat.PNG, 100, s)
        }}
        uri != null
    } catch (_: Exception) { false }
}
