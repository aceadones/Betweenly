package app.loviedowpie.ui

import android.graphics.Bitmap
import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.loviedowpie.DrawSend
import app.loviedowpie.DrawStore
import app.loviedowpie.R
import app.loviedowpie.Store
import app.loviedowpie.ui.icons.Lu
import app.loviedowpie.ui.icons.LuIcon
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Full-screen drawing studio. Strokes stay on this phone until Send.
 * Send reuses [DrawSend] (sealed `draw` message, wake = false).
 */
@Composable
fun DrawingStudioScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext
    val model: DrawingViewModel = viewModel {
        DrawingViewModel().apply { load(DrawPrefs.read(app)) }
    }
    val storeRev by Store.changes.collectAsState()
    val drawRev by DrawStore.changes.collectAsState()
    val generation = Store.generation()
    SideEffect {
        if (storeRev >= 0) model.watchGeneration(generation)
    }
    DisposableEffect(model) {
        onDispose { DrawPrefs.write(app, model) }
    }
    if (!model.alive) {
        BackHandler(onBack = onClose)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ScreenHeader(stringResource(R.string.draw_title), onClose)
            Text(stringResource(R.string.draw_memory), style = LdType.Body, color = LdColors.colors.ink)
        }
        return
    }

    val view = LocalView.current
    val focus = LocalFocusManager.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val partner = remember(drawRev, storeRev) { DrawStore.latestFromPartner(context) }

    var sheet by remember { mutableStateOf<DrawSheet?>(null) }
    var heldSheet by remember { mutableStateOf<DrawSheet?>(null) }
    var wheel by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    var viewer by remember { mutableStateOf(false) }
    var block by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf<Busy?>(null) }
    var leaveAfterSave by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<Notice?>(null) }
    var heldNotice by remember { mutableStateOf<Notice?>(null) }
    val noticeSeq = remember { intArrayOf(0) }
    SideEffect {
        if (sheet != null) heldSheet = sheet
        if (notice != null) heldNotice = notice
    }
    val shownSheet = sheet ?: heldSheet
    val overlay = sheet != null || wheel || menu
    LaunchedEffect(overlay) {
        if (overlay) {
            block = true
        } else {
            if (Motion.enabled) delay(160)
            block = false
        }
    }
    LaunchedEffect(notice) {
        val current = notice ?: return@LaunchedEffect
        delay(if (Motion.enabled) 1600 else 900)
        if (notice == current) notice = null
    }

    var rootOrigin by remember { mutableStateOf(Offset.Zero) }
    var rootWidth by remember { mutableIntStateOf(1) }
    var rootHeight by remember { mutableIntStateOf(1) }
    var dockTopRoot by remember { mutableFloatStateOf(Float.NaN) }
    var moreBottomRoot by remember { mutableFloatStateOf(0f) }
    var moreRightRoot by remember { mutableFloatStateOf(0f) }
    var wheelAt by remember { mutableStateOf<Offset?>(null) }
    val toolAt = remember { mutableStateMapOf<DrawSheet, Offset>() }

    fun persist() {
        DrawPrefs.write(app, model)
    }
    fun showNotice(text: String, ok: Boolean) {
        noticeSeq[0] += 1
        notice = Notice(noticeSeq[0], text, ok)
    }
    fun closeWheel() {
        focus.clearFocus()
        if (wheel) {
            model.pushRecent(model.colorArgb)
            persist()
        }
        wheel = false
    }
    fun toggleSheet(next: DrawSheet) {
        focus.clearFocus()
        menu = false
        if (wheel) closeWheel()
        sheet = if (sheet == next) null else next
    }
    fun tryClose() {
        if (busy != null) return
        model.endStroke()
        focus.clearFocus()
        if (wheel) closeWheel()
        sheet = null
        menu = false
        if (model.needsSave()) confirm = true else onClose()
    }
    fun placeTool(tool: DrawSheet, topLeft: Offset, size: IntSize) {
        val center = Offset(topLeft.x + size.width / 2f, topLeft.y + size.height / 2f)
        val old = toolAt[tool]
        if (old == null || (old - center).getDistance() > 1f) toolAt[tool] = center
    }
    fun originOf(at: Offset?): TransformOrigin {
        if (at == null || rootWidth <= 1) return TransformOrigin(0.5f, 1.06f)
        val x = ((at.x - rootOrigin.x) / rootWidth.toFloat()).coerceIn(0.08f, 0.92f)
        return TransformOrigin(x, 1.06f)
    }
    fun export(send: Boolean) {
        if (busy != null) return
        if (!model.canExport) {
            leaveAfterSave = false
            return
        }
        val bmp = try {
            model.renderExport()
        } catch (_: Exception) {
            null
        } catch (_: OutOfMemoryError) {
            null
        }
        if (bmp == null) {
            leaveAfterSave = false
            showNotice(context.getString(if (send) R.string.draw_send_failed else R.string.draw_save_failed), false)
            return
        }
        val gen = model.generation()
        busy = if (send) Busy.Send else Busy.Save
        scope.launch {
            val ok = try {
                withContext(Dispatchers.IO) { writeDrawing(app, bmp, send) }
            } catch (e: CancellationException) {
                if (!bmp.isRecycled) bmp.recycle()
                throw e
            } catch (_: Exception) {
                false
            }
            if (!bmp.isRecycled) bmp.recycle()
            busy = null
            if (ok) {
                model.markSaved(gen)
                try {
                    DrawingWidget.updateAll(app)
                } catch (_: Exception) {
                    // The 30-minute widget tick tries again.
                }
                showNotice(
                    context.getString(if (send) R.string.draw_sent_chip else R.string.draw_saved_chip),
                    true,
                )
                if (leaveAfterSave) {
                    leaveAfterSave = false
                    confirm = false
                    onClose()
                }
            } else {
                leaveAfterSave = false
                showNotice(
                    context.getString(if (send) R.string.draw_send_failed else R.string.draw_save_failed),
                    false,
                )
            }
        }
    }

    val drawingOn = !block && !confirm && !viewer && busy == null
    val sheetBottom = if (dockTopRoot.isNaN()) {
        96.dp
    } else {
        with(density) { (rootHeight - (dockTopRoot - rootOrigin.y)).toDp() } + 8.dp
    }
    val scrimBottom = if (dockTopRoot.isNaN()) {
        88.dp
    } else {
        with(density) { (rootHeight - (dockTopRoot - rootOrigin.y)).toDp() }
    }
    val menuTop = with(density) { (moreBottomRoot - rootOrigin.y).toDp() + 4.dp }.coerceAtLeast(0.dp)
    val menuEnd = with(density) { (rootOrigin.x + rootWidth - moreRightRoot).toDp() }.coerceIn(8.dp, 72.dp)
    val canAct = model.canExport && busy == null

    BackHandler(enabled = !viewer) {
        when {
            busy != null -> Unit
            confirm -> {
                confirm = false
                leaveAfterSave = false
            }
            wheel -> closeWheel()
            sheet != null -> {
                focus.clearFocus()
                sheet = null
            }
            menu -> menu = false
            else -> tryClose()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { coords ->
                val p = coords.positionInRoot()
                if ((rootOrigin - p).getDistance() > 0.5f) rootOrigin = p
                val w = coords.size.width.coerceAtLeast(1)
                val h = coords.size.height.coerceAtLeast(1)
                if (rootWidth != w) rootWidth = w
                if (rootHeight != h) rootHeight = h
            },
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 16.dp)
                .padding(top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StudioBar(
                model = model,
                onBack = { tryClose() },
                onUndo = { model.undo() },
                onRedo = { model.redo() },
                onMore = { topLeft, size ->
                    moreBottomRoot = topLeft.y + size.height
                    moreRightRoot = topLeft.x + size.width
                },
                menuOpen = menu,
                onMenu = {
                    focus.clearFocus()
                    wheel = false
                    sheet = null
                    menu = !menu
                },
            )
            PaperRow(model.paper) { kind ->
                model.setPaper(kind)
                persist()
            }
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val side = minOf(maxWidth, maxHeight).coerceAtLeast(48.dp)
                    StudioPaper(
                        model = model,
                        enabled = drawingOn,
                        modifier = Modifier
                            .size(side)
                            .align(Alignment.Center),
                    )
                }
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (partner != null) {
                    PartnerChip(onClick = {
                        focus.clearFocus()
                        sheet = null
                        wheel = false
                        menu = false
                        viewer = true
                    })
                }
                DockRow(
                    model = model,
                    sheet = sheet,
                    busy = busy,
                    canAct = canAct,
                    onPlace = ::placeTool,
                    onTool = ::toggleSheet,
                    onEraser = {
                        model.setEraser(!model.eraser)
                        persist()
                    },
                    onSave = {
                        leaveAfterSave = false
                        export(send = false)
                    },
                    onSend = {
                        leaveAfterSave = false
                        export(send = true)
                    },
                    modifier = Modifier.onGloballyPositioned { coords ->
                        val top = coords.positionInRoot().y
                        if (dockTopRoot.isNaN() || kotlin.math.abs(dockTopRoot - top) > 0.5f) dockTopRoot = top
                    },
                )
            }
        }

        if (sheet != null) {
            Scrim(bottom = scrimBottom) {
                focus.clearFocus()
                if (wheel) closeWheel()
                sheet = null
            }
        }
        if (shownSheet != null) {
            val current = shownSheet!!
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = 16.dp, end = 16.dp, bottom = sheetBottom)
                    .fillMaxWidth(),
            ) {
                key(current) {
                PopIn(
                    visible = sheet == current,
                    origin = originOf(toolAt[current]),
                    rise = true,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    SheetSurface {
                        when (current) {
                            DrawSheet.Brush -> BrushSheet(model) { kind ->
                                if (kind != model.brush) {
                                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                }
                                model.setBrush(kind)
                                if (model.eraser) model.setEraser(false)
                                persist()
                            }
                            DrawSheet.Color -> ColorSheet(
                                model = model,
                                onSwatch = { color ->
                                    model.setColor(color)
                                    model.pushRecent(color)
                                    persist()
                                },
                                onWheelPlaced = { x, y ->
                                    val next = Offset(x, y)
                                    val old = wheelAt
                                    if (old == null || (old - next).getDistance() > 1f) wheelAt = next
                                },
                                onWheel = { wheel = true },
                            )
                            DrawSheet.Size -> SizeSheet(
                                model = model,
                                onChange = { model.setSize(it) },
                                onFinish = { persist() },
                            )
                            DrawSheet.Opacity -> OpacitySheet(
                                model = model,
                                onChange = { model.setOpacity(it) },
                                onFinish = { persist() },
                            )
                        }
                    }
                }
            }
            }
        }
        if (wheel) {
            Scrim(bottom = scrimBottom) { closeWheel() }
        }
        PopIn(
            visible = wheel,
            origin = originOf(wheelAt),
            rise = true,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .imePadding()
                .padding(start = 16.dp, end = 16.dp, bottom = sheetBottom)
                .fillMaxWidth(),
        ) {
            SheetSurface {
                DrawWheelBody(
                    colorArgb = model.colorArgb,
                    onColor = { model.setColor(it) },
                    onDone = { closeWheel() },
                )
            }
        }
        if (menu) {
            Scrim(bottom = 0.dp) { menu = false }
        }
        PopIn(
            visible = menu,
            origin = TransformOrigin(1f, 0f),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = menuTop, end = menuEnd),
        ) {
            ClearMenu(
                enabled = model.canExport,
                onClear = {
                    model.clear()
                    menu = false
                },
            )
        }
        if (confirm) {
            DimScrim {
                confirm = false
                leaveAfterSave = false
            }
        }
        PopIn(
            visible = confirm,
            origin = TransformOrigin(0.5f, 0.4f),
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 32.dp)
                .fillMaxWidth(),
        ) {
            UnsavedDialog(
                busy = busy != null,
                onKeep = {
                    confirm = false
                    leaveAfterSave = false
                },
                onSave = {
                    leaveAfterSave = true
                    export(send = false)
                },
                onDiscard = {
                    model.wipe()
                    confirm = false
                    leaveAfterSave = false
                    onClose()
                },
            )
        }
        PopIn(
            visible = notice != null,
            origin = TransformOrigin(0.5f, 0f),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 12.dp),
        ) {
            val chip = notice ?: heldNotice
            if (chip != null) NoticeChip(chip)
        }
        if (viewer) {
            PartnerDrawViewer(onClose = { viewer = false })
        }
    }
}

private enum class DrawSheet { Brush, Color, Size, Opacity }

private enum class Busy { Save, Send }

private data class Notice(val id: Int, val text: String, val ok: Boolean)

/**
 * DATA-FLOW for [send]: the PNG is encrypted on this phone inside [DrawSend]
 * (FileTransfer.upload). PUT /file/{fid} carries ciphertext only. The sealed
 * `draw` message (file id, key, size, time) is queued with wake = false, so
 * there is no FCM ping. The relay stores ciphertext only.
 * DATA-FLOW for a save: none. The PNG stays in filesDir/drawings/saved.
 */
private suspend fun writeDrawing(context: android.content.Context, bitmap: Bitmap, send: Boolean): Boolean {
    return if (send) {
        val png = bitmapToPng(bitmap) ?: return false
        // DATA-FLOW: encrypted file upload + sealed draw message. See [DrawSend.send].
        DrawSend.send(context, png)
    } else {
        // DATA-FLOW: none. Local file under filesDir/drawings/saved.
        DrawStore.save(context, bitmap) != null
    }
}

@Composable
private fun StudioBar(
    model: DrawingViewModel,
    onBack: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onMore: (Offset, IntSize) -> Unit,
    menuOpen: Boolean,
    onMenu: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .ldGlass(RoundedCornerShape(LdRadius.card), solid = true)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ToolButton(Lu.ChevronLeft, stringResource(R.string.back), onClick = onBack)
        Text(
            stringResource(R.string.draw_title),
            modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
            style = LdType.Title,
            color = LdColors.colors.ink,
            maxLines = 1,
        )
        ToolButton(
            icon = Lu.Undo2,
            description = stringResource(R.string.draw_undo),
            enabled = model.canUndo || model.drawing,
            onClick = onUndo,
        )
        ToolButton(
            icon = Lu.Redo2,
            description = stringResource(R.string.draw_redo),
            enabled = model.canRedo && !model.drawing,
            onClick = onRedo,
        )
        ToolButton(
            icon = Lu.EllipsisVertical,
            description = stringResource(R.string.draw_more),
            highlighted = menuOpen,
            onPlace = onMore,
            onClick = onMenu,
        )
    }
}

@Composable
private fun PaperRow(selected: PaperKind, onPick: (PaperKind) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PaperKind.entries.forEach { kind ->
            PaperChip(kind = kind, selected = kind == selected, onClick = { onPick(kind) })
        }
    }
}

@Composable
private fun PaperChip(kind: PaperKind, selected: Boolean, onClick: () -> Unit) {
    val colors = LdColors.colors
    val interaction = remember(kind) { MutableInteractionSource() }
    val shape = RoundedCornerShape(LdRadius.chip)
    val label = stringResource(
        when (kind) {
            PaperKind.White -> R.string.draw_paper_white
            PaperKind.Cream -> R.string.draw_paper_cream
            PaperKind.Dark -> R.string.draw_paper_dark
            PaperKind.Clear -> R.string.draw_paper_clear
        },
    )
    Row(
        modifier = Modifier
            .pressScale(interaction)
            .height(48.dp)
            .clip(shape)
            .background(if (selected) colors.pill else colors.glassStrong)
            .border(1.dp, if (selected) colors.pillBorder else colors.glassBorder, shape)
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (kind == PaperKind.Clear) MiniChecker()
        Text(label, style = LdType.Caption, color = if (selected) colors.onPill else colors.ink, maxLines = 1)
    }
}

@Composable
private fun MiniChecker() {
    Column(
        Modifier
            .size(16.dp)
            .clip(RoundedCornerShape(4.dp)),
    ) {
        Row(Modifier.weight(1f).fillMaxWidth()) {
            Box(Modifier.weight(1f).fillMaxSize().background(Color(0xFFF4F4F6)))
            Box(Modifier.weight(1f).fillMaxSize().background(Color(0xFFD9D9DE)))
        }
        Row(Modifier.weight(1f).fillMaxWidth()) {
            Box(Modifier.weight(1f).fillMaxSize().background(Color(0xFFD9D9DE)))
            Box(Modifier.weight(1f).fillMaxSize().background(Color(0xFFF4F4F6)))
        }
    }
}

@Composable
private fun PartnerChip(onClick: () -> Unit) {
    val colors = LdColors.colors
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(LdRadius.chip)
    Row(
        modifier = Modifier
            .appear(0)
            .pressScale(interaction)
            .height(48.dp)
            .clip(shape)
            .background(colors.glassStrong)
            .border(1.dp, colors.glassBorder, shape)
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        LuIcon(Lu.Image, contentDescription = null, tint = colors.ink, size = 20.dp)
        Text(stringResource(R.string.draw_partner), style = LdType.Caption, color = colors.ink, maxLines = 1)
    }
}

@Composable
private fun DockRow(
    model: DrawingViewModel,
    sheet: DrawSheet?,
    busy: Busy?,
    canAct: Boolean,
    onPlace: (DrawSheet, Offset, IntSize) -> Unit,
    onTool: (DrawSheet) -> Unit,
    onEraser: () -> Unit,
    onSave: () -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ink = LdColors.colors.ink
    Row(
        modifier
            .fillMaxWidth()
            .ldGlass(RoundedCornerShape(LdRadius.card), solid = true)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ToolButton(
            icon = Lu.Brush,
            description = stringResource(R.string.draw_brush),
            highlighted = sheet == DrawSheet.Brush,
            onPlace = { at, size -> onPlace(DrawSheet.Brush, at, size) },
            onClick = { onTool(DrawSheet.Brush) },
        )
        ToolButton(
            icon = Lu.Palette,
            description = stringResource(R.string.draw_color),
            highlighted = sheet == DrawSheet.Color,
            dot = Color(model.colorArgb),
            onPlace = { at, size -> onPlace(DrawSheet.Color, at, size) },
            onClick = { onTool(DrawSheet.Color) },
        )
        ToolButton(
            icon = Lu.CircleDot,
            description = stringResource(R.string.draw_size),
            highlighted = sheet == DrawSheet.Size,
            onPlace = { at, size -> onPlace(DrawSheet.Size, at, size) },
            onClick = { onTool(DrawSheet.Size) },
        )
        ToolButton(
            icon = Lu.Droplets,
            description = stringResource(R.string.draw_opacity),
            highlighted = sheet == DrawSheet.Opacity,
            onPlace = { at, size -> onPlace(DrawSheet.Opacity, at, size) },
            onClick = { onTool(DrawSheet.Opacity) },
        )
        ToolButton(
            icon = Lu.Eraser,
            description = stringResource(R.string.draw_eraser),
            highlighted = model.eraser,
            onClick = onEraser,
        )
        Spacer(Modifier.width(8.dp))
        ToolButton(
            icon = Lu.Save,
            description = stringResource(R.string.draw_save),
            enabled = canAct,
            spinning = busy == Busy.Save,
            onClick = onSave,
        )
        SendButton(enabled = canAct, spinning = busy == Busy.Send, onClick = onSend)
    }
}

@Composable
private fun ToolButton(
    icon: ImageVector,
    description: String,
    enabled: Boolean = true,
    highlighted: Boolean = false,
    spinning: Boolean = false,
    dot: Color? = null,
    onPlace: ((Offset, IntSize) -> Unit)? = null,
    onClick: () -> Unit,
) {
    val interaction = remember(description) { MutableInteractionSource() }
    val tint = when {
        !enabled -> LdColors.colors.ink.copy(alpha = 0.35f)
        highlighted -> LdColors.primary
        else -> LdColors.colors.ink
    }
    Box(
        modifier = Modifier
            .pressScale(interaction)
            .size(48.dp)
            .clip(CircleShape)
            .background(if (highlighted) LdColors.primary.copy(alpha = 0.16f) else Color.Transparent)
            .onGloballyPositioned { coords ->
                onPlace?.invoke(coords.positionInRoot(), IntSize(coords.size.width, coords.size.height))
            }
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                enabled = enabled && !spinning,
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (spinning) {
            CircularProgressIndicator(modifier = Modifier.size(22.dp), color = LdColors.primary, strokeWidth = 2.dp)
        } else {
            LuIcon(icon, contentDescription = description, tint = tint, size = 22.dp, selected = highlighted)
        }
        if (dot != null && !spinning) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 6.dp)
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(dot)
                    .border(1.dp, LdColors.colors.ink.copy(alpha = 0.35f), CircleShape),
            )
        }
    }
}

@Composable
private fun SendButton(enabled: Boolean, spinning: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .pressScale(interaction)
            .size(48.dp)
            .clip(CircleShape)
            .background(if (enabled) LdColors.primary else LdColors.primary.copy(alpha = 0.35f))
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                enabled = enabled && !spinning,
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (spinning) {
            CircularProgressIndicator(modifier = Modifier.size(22.dp), color = LdColors.white, strokeWidth = 2.dp)
        } else {
            LuIcon(
                Lu.Send,
                contentDescription = stringResource(R.string.draw_send),
                tint = LdColors.white,
                size = 22.dp,
            )
        }
    }
}

@Composable
private fun SheetSurface(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .ldGlass(RoundedCornerShape(LdRadius.card), solid = true),
    ) {
        content()
    }
}

@Composable
private fun Scrim(bottom: Dp, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier
            .fillMaxSize()
            .padding(bottom = bottom)
            .clickable(interaction, indication = null, onClick = onClick),
    )
}

@Composable
private fun DimScrim(onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.28f))
            .clickable(interaction, indication = null, onClick = onClick),
    )
}

@Composable
private fun ClearMenu(enabled: Boolean, onClear: () -> Unit) {
    val shape = RoundedCornerShape(LdRadius.tile)
    val item = RoundedCornerShape(LdRadius.inner(LdRadius.tile, 4.dp))
    val interaction = remember { MutableInteractionSource() }
    val ink = if (enabled) LdColors.colors.ink else LdColors.colors.ink.copy(alpha = 0.35f)
    Column(
        Modifier
            .widthIn(min = 200.dp, max = 260.dp)
            .shadow(12.dp, shape, clip = false, ambientColor = LdColors.colors.shadow, spotColor = LdColors.colors.shadow)
            .clip(shape)
            .background(LdColors.colors.menu)
            .border(1.dp, LdColors.colors.menuBorder, shape)
            .padding(4.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .pressScale(interaction)
                .heightIn(min = 48.dp)
                .clip(item)
                .clickable(
                    interactionSource = interaction,
                    indication = LocalIndication.current,
                    enabled = enabled,
                    role = Role.Button,
                    onClick = onClear,
                )
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LuIcon(Lu.Trash2, contentDescription = null, tint = ink, size = 20.dp)
            Text(stringResource(R.string.draw_clear), style = LdType.Title, color = ink, maxLines = 1)
        }
    }
}

@Composable
private fun NoticeChip(notice: Notice) {
    val shape = RoundedCornerShape(LdRadius.chip)
    val bg = if (notice.ok) LdColors.success else LdColors.colors.menu
    val fg = if (notice.ok) LdColors.white else LdColors.colors.ink
    Row(
        modifier = Modifier
            .shadow(8.dp, shape, clip = false)
            .clip(shape)
            .background(bg)
            .border(1.dp, if (notice.ok) Color.Transparent else LdColors.colors.menuBorder, shape)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        LuIcon(
            if (notice.ok) Lu.Check else Lu.TriangleAlert,
            contentDescription = null,
            tint = if (notice.ok) LdColors.white else LdColors.inkRed,
            size = 18.dp,
        )
        Text(notice.text, style = LdType.Caption, color = fg, maxLines = 2)
    }
}

@Composable
private fun UnsavedDialog(
    busy: Boolean,
    onKeep: () -> Unit,
    onSave: () -> Unit,
    onDiscard: () -> Unit,
) {
    val colors = LdColors.colors
    val shape = RoundedCornerShape(LdRadius.card)
    val button = RoundedCornerShape(LdRadius.inner(LdRadius.card, 16.dp))
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(16.dp, shape, clip = false, ambientColor = colors.shadow, spotColor = colors.shadow)
            .clip(shape)
            .background(colors.menu)
            .border(1.dp, colors.menuBorder, shape)
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = {})
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.draw_unsaved_title), style = LdType.Title, color = colors.ink)
        Text(stringResource(R.string.draw_unsaved_body), style = LdType.Body, color = colors.inkMuted)
        DialogButton(
            text = stringResource(R.string.draw_keep),
            background = LdColors.bubbleMine,
            content = LdColors.white,
            shape = button,
            enabled = true,
            onClick = onKeep,
        )
        DialogButton(
            text = stringResource(R.string.draw_save),
            background = colors.glassStrong,
            content = colors.ink,
            shape = button,
            enabled = !busy,
            onClick = onSave,
        )
        DialogButton(
            text = stringResource(R.string.draw_discard),
            background = Color.Transparent,
            content = LdColors.inkRed,
            shape = button,
            enabled = !busy,
            onClick = onDiscard,
        )
    }
}

@Composable
private fun DialogButton(
    text: String,
    background: Color,
    content: Color,
    shape: RoundedCornerShape,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val interaction = remember(text) { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .pressScale(interaction)
            .heightIn(min = 48.dp)
            .clip(shape)
            .background(background)
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = LdType.Title, color = if (enabled) content else content.copy(alpha = 0.4f), maxLines = 1)
    }
}

@Composable
private fun PopIn(
    visible: Boolean,
    origin: TransformOrigin,
    modifier: Modifier = Modifier,
    rise: Boolean = false,
    content: @Composable () -> Unit,
) {
    var on by remember { mutableStateOf(false) }
    LaunchedEffect(visible) { on = visible }
    Box(modifier) {
        PopupScale(visible = on, origin = origin) {
            if (rise) Rise(content) else content()
        }
    }
}

@Composable
private fun Rise(content: @Composable () -> Unit) {
    var risen by remember { mutableStateOf(!Motion.enabled) }
    LaunchedEffect(Unit) { risen = true }
    val ty by animateDpAsState(
        targetValue = if (risen) 0.dp else 16.dp,
        animationSpec = Motion.spring(visibilityThreshold = 0.5.dp),
        label = "rise",
    )
    Box(Modifier.graphicsLayer { translationY = ty.toPx() }) { content() }
}
