package com.nextlayer.betweenly.ui

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.nextlayer.betweenly.DrawSend
import com.nextlayer.betweenly.DrawStore
import com.nextlayer.betweenly.R
import com.nextlayer.betweenly.SavedDrawing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/** Saved drawings, full screen from Profile. Back returns to the Profile tab. */
@Composable
fun SavedDrawingsScreen(onBack: () -> Unit) {
    SavedDrawingsBody(onBack)
}

/**
 * Same grid, drawn over the Drawing Studio so the canvas stays in memory.
 * Back closes the grid and leaves the studio open.
 */
@Composable
fun SavedDrawingsOverlay(onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier
            .fillMaxSize()
            .clickable(interaction, indication = null, onClick = {}),
    ) {
        AppBackground(Modifier.fillMaxSize())
        SavedDrawingsBody(onClose)
    }
}

@Composable
private fun SavedDrawingsBody(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val drawRev by DrawStore.changes.collectAsState()
    val drawings = remember(drawRev) { DrawStore.list(context) }
    var viewingTs by remember { mutableStateOf(-1L) }

    if (viewingTs > 0L) {
        SavedDrawingViewer(
            ts = viewingTs,
            onClose = { viewingTs = -1L },
            onDeleted = {
                viewingTs = -1L
                scope.launch { snackbar.showSnackbar(context.getString(R.string.draw_deleted)) }
            },
        )
    }

    Scaffold(
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 16.dp),
        ) {
            ScreenHeader(stringResource(R.string.draw_saved_title), onBack)
            if (drawings.isEmpty()) {
                Text(
                    stringResource(R.string.draw_none_saved),
                    modifier = Modifier.padding(top = 16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = LdColors.colors.inkMuted,
                )
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(drawings, key = { it.ts }) { drawing ->
                        SavedThumb(drawing) { viewingTs = drawing.ts }
                    }
                }
            }
        }
    }
}

@Composable
private fun SavedThumb(drawing: SavedDrawing, onClick: () -> Unit) {
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(drawing.ts) {
        bitmap = withContext(Dispatchers.IO) { DrawStore.decode(drawing.file, 360) }
    }
    Box(
        Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(LdRadius.tile))
            .background(LdColors.paper)
            .clickable(onClick = onClick),
    ) {
        val shown = bitmap
        if (shown != null && !shown.isRecycled) {
            Image(
                bitmap = shown.asImageBitmap(),
                contentDescription = stringResource(R.string.draw_open),
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
    }
}

@Composable
private fun SavedDrawingViewer(
    ts: Long,
    onClose: () -> Unit,
    onDeleted: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var missing by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    val title = remember(ts) {
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(ts))
    }
    LaunchedEffect(ts) {
        val decoded = withContext(Dispatchers.IO) {
            val file = DrawStore.list(context).firstOrNull { it.ts == ts }?.file
            if (file == null) null else DrawStore.decode(file, 1600)
        }
        if (decoded == null) missing = true else bitmap = decoded
    }
    LaunchedEffect(missing) {
        if (missing) onClose()
    }
    if (missing) return
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        PopupScale(visible = true, origin = TransformOrigin.Center) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = LdColors.colors.menu,
                contentColor = LdColors.colors.ink,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    ScreenHeader(title, onClose)
                    val shown = bitmap
                    Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        if (shown != null && !shown.isRecycled) {
                            Image(
                                bitmap = shown.asImageBitmap(),
                                contentDescription = stringResource(R.string.draw_open),
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Fit,
                            )
                        } else {
                            CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
                        }
                    }
                    note?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium)
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        LdButton(
                            text = stringResource(R.string.draw_delete),
                            onClick = {
                                scope.launch {
                                    val removed = withContext(Dispatchers.IO) {
                                        val ok = DrawStore.delete(context, ts)
                                        if (ok) DrawingWidget.updateAll(context)
                                        ok
                                    }
                                    if (removed) onDeleted()
                                }
                            },
                            style = LdButtonStyle.Tertiary,
                            enabled = !sending,
                            contentColor = MaterialTheme.colorScheme.error,
                        )
                        LdButton(
                            text = stringResource(R.string.draw_send_again),
                            loading = sending,
                            onClick = {
                                if (sending) return@LdButton
                                sending = true
                                note = null
                                scope.launch {
                                    val ok = try {
                                        withContext(Dispatchers.IO) {
                                            val file = DrawStore.list(context).firstOrNull { it.ts == ts }?.file
                                            if (file == null || !file.isFile || file.length() <= 0L || file.length() > 24L * 1024 * 1024) {
                                                false
                                            } else {
                                                // DATA-FLOW: saved PNG, encrypted inside DrawSend, ciphertext to the relay.
                                                DrawSend.send(context, file.readBytes())
                                            }
                                        }
                                    } catch (e: CancellationException) {
                                        sending = false
                                        throw e
                                    } catch (_: Exception) {
                                        false
                                    }
                                    sending = false
                                    note = context.getString(if (ok) R.string.draw_sent else R.string.draw_send_failed)
                                }
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}
