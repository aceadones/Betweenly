package app.loviedowpie.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.loviedowpie.DrawSend
import app.loviedowpie.DrawStore
import app.loviedowpie.R
import app.loviedowpie.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * Activity tab: a drawing canvas, the partner's latest drawing, and send/save.
 * The shell already applied the status-bar and navigation insets.
 *
 * DATA-FLOW: sending calls [DrawSend]. Saving stays on this phone ([DrawStore]).
 */
@Composable
fun ActivityScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val model: DrawingViewModel = viewModel(key = "draw-${Store.generation()}")
    var colorArgb by rememberSaveable { mutableIntStateOf(DrawInk.swatches.last().toArgb()) }
    var brushIndex by rememberSaveable { mutableIntStateOf(1) }
    var erase by rememberSaveable { mutableStateOf(false) }
    var whitePaper by rememberSaveable { mutableStateOf(true) }
    var wheel by rememberSaveable { mutableStateOf(false) }
    var canvasPx by remember { mutableStateOf(IntSize.Zero) }
    var busy by remember { mutableStateOf(false) }
    val widthDp = DrawInk.brushSizesDp[brushIndex.coerceIn(0, DrawInk.brushSizesDp.lastIndex)]
    val empty = model.strokes.isEmpty()

    if (wheel) {
        DrawColorDialog(
            initialArgb = colorArgb,
            onChange = { next ->
                colorArgb = next
                erase = false
            },
            onDismiss = { wheel = false },
        )
    }

    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0.dp, 0.dp, 0.dp, 0.dp),
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
        val short = maxHeight < 560.dp
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (short) Modifier.verticalScroll(rememberScrollState()) else Modifier.fillMaxSize()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PartnerDrawingCard()
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (short) Modifier.height(280.dp) else Modifier.weight(1f))
                    .onSizeChanged { canvasPx = it },
            ) {
                DrawCanvas(
                    model = model,
                    colorArgb = colorArgb,
                    widthDp = widthDp,
                    erase = erase,
                    whitePaper = whitePaper,
                    contentDescription = stringResource(R.string.draw_canvas_cd),
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DrawInk.swatches.forEach { swatch ->
                    val argb = swatch.toArgb()
                    Swatch(
                        color = swatch,
                        selected = !erase && colorArgb == argb,
                        description = stringResource(R.string.draw_swatch_cd),
                        onClick = {
                            colorArgb = argb
                            erase = false
                        },
                    )
                }
                TextButton(onClick = { wheel = true }) {
                    Text(stringResource(R.string.draw_picker))
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DrawInk.brushSizesDp.forEachIndexed { index, size ->
                    BrushSize(
                        diameter = size,
                        selected = brushIndex == index,
                        onClick = { brushIndex = index },
                        description = stringResource(R.string.draw_brush_cd, index + 1),
                    )
                }
                Spacer(Modifier.weight(1f))
                ToolChip(
                    label = stringResource(R.string.draw_eraser),
                    selected = erase,
                    onClick = { erase = !erase },
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(stringResource(R.string.draw_paper), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        stringResource(R.string.draw_paper_help),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = whitePaper, onCheckedChange = { whitePaper = it })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { model.undo() }, enabled = model.canUndo && !busy) {
                    Text(stringResource(R.string.draw_undo))
                }
                TextButton(onClick = { model.clear() }, enabled = !empty && !busy) {
                    Text(stringResource(R.string.draw_clear))
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = {
                        if (busy || empty) return@OutlinedButton
                        val size = exportPixels(canvasPx.width, canvasPx.height)
                        if (size.first <= 1) {
                            scope.launch { snackbar.showSnackbar(context.getString(R.string.draw_save_failed)) }
                            return@OutlinedButton
                        }
                        busy = true
                        val strokes = model.strokes
                        val paper = whitePaper
                        scope.launch {
                            val ok = try {
                                withContext(Dispatchers.IO) {
                                    val bmp = renderDrawing(strokes, size.first, size.second, paper) ?: return@withContext false
                                    try {
                                        val saved = DrawStore.save(context, bmp) != null
                                        if (saved) DrawingWidget.updateAll(context)
                                        saved
                                    } finally {
                                        if (!bmp.isRecycled) bmp.recycle()
                                    }
                                }
                            } catch (e: CancellationException) {
                                busy = false
                                throw e
                            } catch (_: Exception) {
                                false
                            }
                            busy = false
                            snackbar.showSnackbar(
                                context.getString(if (ok) R.string.draw_saved else R.string.draw_save_failed),
                            )
                        }
                    },
                    enabled = !empty && !busy,
                    modifier = Modifier.weight(1f).height(52.dp),
                ) {
                    Text(stringResource(R.string.draw_save))
                }
                Button(
                    onClick = {
                        if (busy || empty) return@Button
                        val size = exportPixels(canvasPx.width, canvasPx.height)
                        if (size.first <= 1) {
                            scope.launch { snackbar.showSnackbar(context.getString(R.string.draw_send_failed)) }
                            return@Button
                        }
                        busy = true
                        val strokes = model.strokes
                        val paper = whitePaper
                        scope.launch {
                            val ok = try {
                                withContext(Dispatchers.IO) {
                                    val bmp = renderDrawing(strokes, size.first, size.second, paper) ?: return@withContext false
                                    try {
                                        val png = bitmapToPng(bmp) ?: return@withContext false
                                        DrawSend.send(context, png)
                                    } finally {
                                        if (!bmp.isRecycled) bmp.recycle()
                                    }
                                }
                            } catch (e: CancellationException) {
                                busy = false
                                throw e
                            } catch (_: Exception) {
                                false
                            }
                            busy = false
                            snackbar.showSnackbar(
                                context.getString(if (ok) R.string.draw_sent else R.string.draw_send_failed),
                            )
                        }
                    },
                    enabled = !empty && !busy,
                    modifier = Modifier.weight(1f).height(52.dp),
                ) {
                    Text(stringResource(R.string.draw_send))
                }
            }
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp).align(Alignment.CenterHorizontally),
                    strokeWidth = 2.dp,
                )
            }
        }
        }
    }
}

@Composable
private fun PartnerDrawingCard() {
    val context = LocalContext.current
    val drawRev by DrawStore.changes.collectAsState()
    val storeRev by Store.changes.collectAsState()
    val name = remember(storeRev) {
        Store.partnerName(context).ifBlank { context.getString(R.string.partner_fallback) }
    }
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(drawRev) {
        bitmap = withContext(Dispatchers.IO) {
            val file = DrawStore.latestFromPartner(context) ?: return@withContext null
            DrawStore.decode(file, 480)
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth().ldGlass(MaterialTheme.shapes.large).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
            val shown = bitmap
            if (shown != null && !shown.isRecycled) {
                Image(
                    bitmap = shown.asImageBitmap(),
                    contentDescription = stringResource(R.string.draw_from, name),
                    modifier = Modifier.size(72.dp).clip(MaterialTheme.shapes.medium),
                    contentScale = ContentScale.Crop,
                )
                Spacer(Modifier.width(12.dp))
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    stringResource(R.string.draw_from, name),
                    style = MaterialTheme.typography.titleMedium,
                )
                if (shown == null || shown.isRecycled) {
                    Text(
                        stringResource(R.string.draw_none_yet),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
    }
}

@Composable
private fun Swatch(
    color: androidx.compose.ui.graphics.Color,
    selected: Boolean,
    description: String,
    onClick: () -> Unit,
) {
    val outline = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    Surface(
        modifier = Modifier
            .size(32.dp)
            .semantics { contentDescription = description },
        shape = CircleShape,
        color = color,
        border = androidx.compose.foundation.BorderStroke(if (selected) 3.dp else 1.dp, outline),
        onClick = onClick,
        shadowElevation = 0.dp,
    ) {}
}

@Composable
private fun BrushSize(
    diameter: Float,
    selected: Boolean,
    description: String,
    onClick: () -> Unit,
) {
    val outline = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(diameter.dp.coerceAtLeast(4.dp).coerceAtMost(28.dp))
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.onSurface)
                .then(if (selected) Modifier.border(2.dp, outline, CircleShape) else Modifier),
        )
    }
}

@Composable
private fun ToolChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val c = LdColors.colors
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = if (selected) c.chipOn else c.glass,
        border = androidx.compose.foundation.BorderStroke(1.dp, c.glassBorder),
        shadowElevation = 0.dp,
        tonalElevation = 0.dp,
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) c.chipOnInk else c.ink,
        )
    }
}
