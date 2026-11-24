package app.loviedowpie.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import app.loviedowpie.ChatStore
import app.loviedowpie.R
import app.loviedowpie.Store
import kotlinx.coroutines.launch

/** Wallpaper and the automatic gallery-save switch. */
@Composable
fun ChatSettings(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val revision by Store.changes.collectAsState()
    val wallpaper = remember(revision) { Store.chatWallpaper(context) }
    val autoSave = remember(revision) { Store.chatAutoSaveMedia(context) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        failed = false
        scope.launch {
            val jpeg = try {
                ChatMedia.wallpaperJpeg(context, uri)
            } catch (_: Exception) {
                null
            }
            if (jpeg == null || !ChatStore.saveWallpaper(context, jpeg)) {
                failed = true
            } else {
                Store.setChatWallpaper(context, "image")
            }
            busy = false
        }
    }
    BackHandler(onBack = onBack)
    Scaffold(
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets.safeDrawing,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ScreenHeader(stringResource(R.string.chat_settings), onBack)
            Text(stringResource(R.string.chat_wallpaper), style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ChatTheme.wallpapers.forEachIndexed { index, color ->
                    val selected = wallpaper == "color:$index"
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(ChatTheme.swatch)
                            .background(color)
                            .border(
                                width = if (selected) 2.dp else 0.dp,
                                color = MaterialTheme.colorScheme.primary,
                                shape = ChatTheme.swatch,
                            )
                            .clickable { Store.setChatWallpaper(context, "color:$index") },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (selected) {
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = stringResource(R.string.chat_wallpaper_color, index + 1),
                                tint = ChatTheme.onWallpaper(color),
                            )
                        }
                    }
                }
            }
            TextButton(onClick = { Store.setChatWallpaper(context, "default") }) {
                Text(stringResource(R.string.chat_wallpaper_default))
            }
            TextButton(
                enabled = !busy,
                onClick = {
                    failed = false
                    try {
                        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    } catch (_: Exception) {
                        failed = true
                    }
                },
            ) {
                Text(stringResource(R.string.chat_wallpaper_photo))
            }
            if (busy) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            }
            if (failed) {
                Text(
                    stringResource(R.string.chat_wallpaper_failed),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (wallpaper == "default") {
                Text(
                    stringResource(R.string.chat_wallpaper_using_default),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(stringResource(R.string.chat_autosave), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        stringResource(R.string.chat_autosave_help),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = autoSave,
                    onCheckedChange = { Store.setChatAutoSaveMedia(context, it) },
                )
            }
        }
    }
}
