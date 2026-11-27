package com.nextlayer.betweenly.ui

import android.graphics.Bitmap
import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nextlayer.betweenly.AppScope
import com.nextlayer.betweenly.DrawSend
import com.nextlayer.betweenly.DrawStore
import com.nextlayer.betweenly.R
import com.nextlayer.betweenly.Store
import com.nextlayer.betweenly.ui.icons.Lu
import com.nextlayer.betweenly.ui.icons.LuIcon
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
    val name = remember(storeRev) { Store.partnerName(context).trim() }
    val arrivals by DrawStore.partnerArrivals.collectAsState()

    var mode by remember { mutableStateOf(CanvasMode.Shared) }
    var page by remember { mutableIntStateOf(0) }
    var hydrated by remember { mutableStateOf(false) }
    var flushOn by remember { mutableStateOf(true) }
    var switching by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var drewNow by remember { mutableStateOf(false) }
    var drewToken by remember { mutableIntStateOf(0) }
    val partnerSlot = remember { arrayOfNulls<Bitmap>(1) }
    var partnerBmp by remember { mutableStateOf<Bitmap?>(null) }
    var seenStamp by remember { mutableStateOf(-1L) }
    var keepFile by remember { mutableStateOf(false) }
    val keepNow = rememberUpdatedState(keepFile)
    val localJump = remember { intArrayOf(0) }
    val modeNow = rememberUpdatedState(mode)
    val flushNow = rememberUpdatedState(flushOn)
    val hydratedNow = rememberUpdatedState(hydrated)

    var sheet by remember { mutableStateOf<DrawSheet?>(null) }
    var heldSheet by remember { mutableStateOf<DrawSheet?>(null) }
    var wheel by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var savedOpen by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    var viewer by remember { mutableStateOf(false) }
    var block by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf<Busy?>(null) }
    var leaveAfterSave by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<Notice?>(null) }
    var heldNotice by remember { mutableStateOf<Notice?>(null) }
    val noticeSeq = remember { intArrayOf(0) }
    val shared = mode == CanvasMode.Shared
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
        if (busy != null || switching) return
        val can = if (send) model.canExport else model.canExport || (shared && partnerBmp != null)
        if (!can) {
            leaveAfterSave = false
            return
        }
        val bmp = try {
            when {
                send && shared -> model.exportLayer()
                shared -> model.renderComposite(partnerBmp)
                else -> model.renderExport()
            }
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
        val sendPage = page.coerceAtLeast(1)
        busy = if (send) Busy.Send else Busy.Save
        scope.launch {
            val ok = try {
                withContext(Dispatchers.IO) { writeDrawing(app, bmp, send, shared, sendPage) }
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
    fun switchMode(next: CanvasMode) {
        if (next == mode || busy != null || switching || !hydrated) return
        flushOn = false
        switching = true
        model.endStroke()
        focus.clearFocus()
        sheet = null
        wheel = false
        menu = false
        val leavingShared = mode == CanvasMode.Shared
        val bmp = try {
            model.exportLayer()
        } catch (_: Exception) {
            null
        } catch (_: OutOfMemoryError) {
            null
        }
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    if (bmp == null) DrawStore.deleteMine(app, leavingShared)
                    else DrawStore.writeMine(app, leavingShared, bmp)
                }
                val loaded = withContext(Dispatchers.IO) {
                    val file = DrawStore.mineFile(app, next == CanvasMode.Shared)
                    if (file == null) {
                        null
                    } else {
                        try {
                            DrawStore.decode(file, model.side)
                        } catch (_: Exception) {
                            null
                        }
                    }
                }
                model.wipe()
                model.installInk(loaded)
                if (loaded != null && !loaded.isRecycled) loaded.recycle()
                mode = next
                DrawPrefs.writeMode(app, next)
                showNotice(
                    context.getString(
                        if (next == CanvasMode.Shared) R.string.draw_mode_shared_on else R.string.draw_mode_separate_on,
                    ),
                    true,
                )
                try {
                    DrawingWidget.updateAll(app)
                } catch (_: Exception) {
                    // The 30-minute widget tick tries again.
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                val restore = try {
                    withContext(Dispatchers.IO) {
                        val file = DrawStore.mineFile(app, leavingShared)
                        if (file == null) null else DrawStore.decode(file, model.side)
                    }
                } catch (_: Exception) {
                    null
                }
                model.installInk(restore)
                if (restore != null && !restore.isRecycled) restore.recycle()
            } finally {
                if (bmp != null && !bmp.isRecycled) bmp.recycle()
                flushOn = true
                switching = false
            }
        }
    }
    fun newPage() {
        if (busy != null || switching || !shared || !hydrated) return
        model.endStroke()
        focus.clearFocus()
        sheet = null
        wheel = false
        menu = false
        val next = (page + 1).coerceAtMost(1_000_000)
        if (next <= page) {
            showNotice(context.getString(R.string.draw_new_page_failed), false)
            return
        }
        val composite = try {
            model.renderComposite(partnerBmp)
        } catch (_: Exception) {
            null
        } catch (_: OutOfMemoryError) {
            null
        }
        flushOn = false
        busy = Busy.Page
        scope.launch {
            try {
                // DATA-FLOW: sealed new-page message, no file. See [DrawSend.sendNewPage].
                val sent = DrawSend.sendNewPage(app, next)
                if (!sent) {
                    showNotice(context.getString(R.string.draw_new_page_failed), false)
                    return@launch
                }
                localJump[0] = next
                val committed = withContext(Dispatchers.IO) {
                    DrawStore.commitNewPage(app, next, composite)
                }
                if (!committed) {
                    showNotice(context.getString(R.string.draw_new_page_failed), false)
                    return@launch
                }
                page = next
                model.wipe()
                val previous = partnerSlot[0]
                partnerSlot[0] = null
                partnerBmp = null
                if (previous != null) recycleLater(view, previous)
                showNotice(context.getString(R.string.draw_new_page_done), true)
                try {
                    DrawingWidget.updateAll(app)
                } catch (_: Exception) {
                    // The 30-minute widget tick tries again.
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                showNotice(context.getString(R.string.draw_new_page_failed), false)
            } finally {
                if (composite != null && !composite.isRecycled) composite.recycle()
                flushOn = true
                busy = null
            }
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    var resumeTick by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) resumeTick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    DisposableEffect(model) {
        onDispose {
            partnerSlot[0]?.let { recycleLater(view, it) }
            if (!hydratedNow.value || !flushNow.value) return@onDispose
            val keepShared = modeNow.value == CanvasMode.Shared
            val bmp = try {
                model.exportLayer()
            } catch (_: Exception) {
                null
            } catch (_: OutOfMemoryError) {
                null
            }
            AppScope.scope.launch {
                try {
                    if (bmp == null) {
                        if (!keepNow.value) DrawStore.deleteMine(app, keepShared)
                    } else {
                        DrawStore.writeMine(app, keepShared, bmp)
                    }
                    DrawingWidget.updateAll(app)
                } catch (_: Exception) {
                    // The layer file keeps the previous stroke. The widget tick tries again.
                } finally {
                    if (bmp != null && !bmp.isRecycled) bmp.recycle()
                }
            }
        }
    }
    LaunchedEffect(Unit) {
        val savedMode = withContext(Dispatchers.IO) { DrawPrefs.readMode(app) }
        val savedPage = withContext(Dispatchers.IO) { DrawStore.page(app) }
        val (loaded, failed) = withContext(Dispatchers.IO) {
            val file = DrawStore.mineFile(app, savedMode == CanvasMode.Shared)
            if (file == null) {
                null to false
            } else {
                val bmp = try {
                    DrawStore.decode(file, model.side)
                } catch (_: Exception) {
                    null
                }
                if (bmp != null) bmp to false else null to true
            }
        }
        if (failed) keepFile = true
        mode = savedMode
        page = savedPage
        model.installInk(loaded)
        if (loaded != null && !loaded.isRecycled) loaded.recycle()
        hydrated = true
    }
    LaunchedEffect(model.frame, mode, hydrated, flushOn) {
        if (!hydrated || !flushOn || model.drawing) return@LaunchedEffect
        val keepShared = mode == CanvasMode.Shared
        val retain = keepFile
        val bmp = try {
            model.exportLayer()
        } catch (_: Exception) {
            null
        } catch (_: OutOfMemoryError) {
            null
        }
        try {
            withContext(Dispatchers.IO) {
                if (bmp == null) {
                    if (!retain) DrawStore.deleteMine(app, keepShared)
                } else {
                    DrawStore.writeMine(app, keepShared, bmp)
                }
            }
            if (bmp != null) keepFile = false
            try {
                DrawingWidget.updateAll(app)
            } catch (_: Exception) {
                // The 30-minute widget tick tries again.
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // The next stroke writes again.
        } finally {
            if (bmp != null && !bmp.isRecycled) bmp.recycle()
        }
    }
    LaunchedEffect(arrivals, resumeTick, mode, hydrated) {
        if (!hydrated || mode != CanvasMode.Shared) return@LaunchedEffect
        val stamp = withContext(Dispatchers.IO) { DrawStore.partnerStamp(app) }
        val file = withContext(Dispatchers.IO) { DrawStore.sharedPartner(app) }
        if (file == null) {
            val previous = partnerSlot[0]
            partnerSlot[0] = null
            partnerBmp = null
            if (previous != null) recycleLater(view, previous)
            if (seenStamp < 0L) seenStamp = stamp
            return@LaunchedEffect
        }
        if (stamp == seenStamp && partnerSlot[0] != null && seenStamp >= 0L) return@LaunchedEffect
        val decoded = withContext(Dispatchers.IO) {
            try {
                DrawStore.decode(file, model.side)
            } catch (_: Exception) {
                null
            }
        }
        if (decoded == null) return@LaunchedEffect
        val previous = partnerSlot[0]
        partnerSlot[0] = decoded
        partnerBmp = decoded
        if (previous != null && previous !== decoded) recycleLater(view, previous)
        if (seenStamp >= 0L && stamp > seenStamp) {
            drewToken += 1
            drewNow = true
        }
        seenStamp = stamp
    }
    LaunchedEffect(drewToken) {
        if (!drewNow) return@LaunchedEffect
        delay(if (Motion.enabled) 2200 else 1400)
        drewNow = false
    }
    LaunchedEffect(drawRev, hydrated, page, mode) {
        if (!hydrated || page <= 0 || mode != CanvasMode.Shared) return@LaunchedEffect
        val disk = withContext(Dispatchers.IO) { DrawStore.page(app) }
        if (disk > page) {
            val remote = disk != localJump[0]
            page = disk
            model.wipe()
            if (remote) showNotice(context.getString(R.string.draw_new_page_done), true)
        }
    }

    val drawingOn = hydrated && !block && !confirm && !confirmClear && !viewer && busy == null && !switching
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
    val canSend = model.canExport && busy == null && !switching
    val canSave = busy == null && !switching && (model.canExport || (shared && partnerBmp != null))

    BackHandler(enabled = !viewer && !savedOpen) {
        when {
            busy != null -> Unit
            confirm -> {
                confirm = false
                leaveAfterSave = false
            }
            confirmClear -> confirmClear = false
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
                mode = mode,
                page = page,
                onBack = { tryClose() },
                onUndo = { model.undo() },
                onRedo = { model.redo() },
                onMode = { switchMode(it) },
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
                if (shared) {
                    scope.launch {
                        delay(80)
                        try {
                            DrawingWidget.updateAll(app)
                        } catch (_: Exception) {
                            // The next layer write updates the widget.
                        }
                    }
                }
            }
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val side = minOf(maxWidth, maxHeight).coerceAtLeast(48.dp)
                    val who = name.ifBlank { stringResource(R.string.partner_fallback) }
                    Box(
                        Modifier
                            .size(side)
                            .align(Alignment.Center),
                    ) {
                        StudioPaper(
                            model = model,
                            partner = if (shared) partnerBmp else null,
                            enabled = drawingOn,
                            modifier = Modifier.fillMaxSize(),
                        )
                        DrewChipFade(
                            visible = shared && drewNow,
                            name = who,
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = 12.dp),
                        )
                    }
                }
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (!shared && partner != null) {
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
                    canSave = canSave,
                    canSend = canSend,
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
                clearEnabled = model.hasLayer(),
                shared = shared,
                onSaved = {
                    menu = false
                    savedOpen = true
                },
                onNewPage = { newPage() },
                onClear = {
                    menu = false
                    if (shared) {
                        confirmClear = true
                    } else {
                        keepFile = false
                        model.clear()
                    }
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
                    val dropShared = shared
                    flushOn = false
                    model.wipe()
                    scope.launch(Dispatchers.IO) {
                        try {
                            DrawStore.deleteMine(app, dropShared)
                            DrawingWidget.updateAll(app)
                        } catch (_: Exception) {
                            // Closing still leaves the canvas. The file is removed on the next open if this failed.
                        }
                    }
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
        if (confirmClear) {
            DimScrim { confirmClear = false }
        }
        PopIn(
            visible = confirmClear,
            origin = TransformOrigin(0.5f, 0.4f),
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 32.dp)
                .fillMaxWidth(),
        ) {
            ClearLayerDialog(
                onCancel = { confirmClear = false },
                onClear = {
                    confirmClear = false
                    keepFile = false
                    model.clear()
                },
            )
        }
        if (viewer) {
            PartnerDrawViewer(onClose = { viewer = false })
        }
        if (savedOpen) {
            SavedDrawingsOverlay(onClose = { savedOpen = false })
        }
    }
}

private enum class DrawSheet { Brush, Color, Size, Opacity }

private enum class Busy { Save, Send, Page }

private data class Notice(val id: Int, val text: String, val ok: Boolean)

/**
 * DATA-FLOW for a shared send: only this phone's transparent layer is encrypted
 * inside [DrawSend.sendLayer]. PUT /file/{fid} carries ciphertext only, kept 7 days.
 * The sealed `draw` message adds `layer` = shared and `page`. wake is false.
 * The composite is not uploaded.
 * DATA-FLOW for a separate send: the full page, same path, no `layer` field.
 * See [DrawSend.send].
 * DATA-FLOW for a save: none. The PNG stays in filesDir/drawings/saved.
 * A shared save is paper plus both layers.
 */
private suspend fun writeDrawing(
    context: android.content.Context,
    bitmap: Bitmap,
    send: Boolean,
    shared: Boolean,
    page: Int,
): Boolean {
    return if (send && shared) {
        // Keep the on-disk layer in step with the bytes we upload, for the widget.
        DrawStore.writeMine(context, true, bitmap)
        val png = bitmapToPng(bitmap) ?: return false
        // DATA-FLOW: encrypted layer upload + sealed draw message (layer, page). See [DrawSend.sendLayer].
        DrawSend.sendLayer(context, png, page)
    } else if (send) {
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
    mode: CanvasMode,
    page: Int,
    onBack: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onMode: (CanvasMode) -> Unit,
    onMore: (Offset, IntSize) -> Unit,
    menuOpen: Boolean,
    onMenu: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .ldGlass(RoundedCornerShape(LdRadius.card), solid = true)
            .padding(horizontal = 4.dp, vertical = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ToolButton(Lu.ChevronLeft, stringResource(R.string.back), onClick = onBack)
            Text(
                stringResource(R.string.draw_title),
                modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
                style = LdType.Title,
                color = LdColors.onGlass,
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
        ModeRow(mode = mode, page = page, onMode = onMode)
    }
}

@Composable
private fun ModeRow(mode: CanvasMode, page: Int, onMode: (CanvasMode) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier
                .height(40.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.14f))
                .border(1.dp, Color.White.copy(alpha = 0.18f), CircleShape)
                .padding(3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ModeSegment(
                label = stringResource(R.string.draw_mode_shared),
                selected = mode == CanvasMode.Shared,
                onClick = { onMode(CanvasMode.Shared) },
            )
            ModeSegment(
                label = stringResource(R.string.draw_mode_separate),
                selected = mode == CanvasMode.Separate,
                onClick = { onMode(CanvasMode.Separate) },
            )
        }
        if (mode == CanvasMode.Shared && page > 0) {
            Text(
                stringResource(R.string.draw_page, page),
                style = LdType.Caption.copy(fontFeatureSettings = "tnum"),
                color = LdColors.onGlassMuted,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun ModeSegment(label: String, selected: Boolean, onClick: () -> Unit) {
    val interaction = remember(label) { MutableInteractionSource() }
    val shape = CircleShape
    Box(
        modifier = Modifier
            .pressScale(interaction)
            .height(34.dp)
            .clip(shape)
            .background(if (selected) LdColors.white.copy(alpha = 0.92f) else Color.Transparent)
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = LdType.Caption,
            color = if (selected) LdColors.navy else LdColors.onGlass,
            maxLines = 1,
        )
    }
}

@Composable
private fun DrewChipFade(visible: Boolean, name: String, modifier: Modifier = Modifier) {
    // Own composable: the studio Column would otherwise bind the column AnimatedVisibility.
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(Motion.tween<Float>(180)),
        exit = fadeOut(Motion.tween<Float>(140)),
        modifier = modifier,
    ) {
        DrewChip(name)
    }
}

@Composable
private fun DrewChip(name: String) {
    val shape = RoundedCornerShape(LdRadius.chip)
    Text(
        stringResource(R.string.draw_partner_drew, name),
        modifier = Modifier
            .darkGlass(shape)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        style = LdType.Caption,
        color = LdColors.onGlass,
        maxLines = 1,
    )
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
            .then(
                if (selected) {
                    Modifier.clip(shape).background(LdColors.white.copy(alpha = 0.92f))
                } else {
                    Modifier.darkGlass(shape)
                },
            )
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
        Text(label, style = LdType.Caption, color = if (selected) LdColors.navy else LdColors.onGlass, maxLines = 1)
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
            Box(Modifier.weight(1f).fillMaxSize().background(LdColors.checkerLight))
            Box(Modifier.weight(1f).fillMaxSize().background(LdColors.checkerDark))
        }
        Row(Modifier.weight(1f).fillMaxWidth()) {
            Box(Modifier.weight(1f).fillMaxSize().background(LdColors.checkerDark))
            Box(Modifier.weight(1f).fillMaxSize().background(LdColors.checkerLight))
        }
    }
}

@Composable
private fun PartnerChip(onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(LdRadius.chip)
    Row(
        modifier = Modifier
            .appear(0)
            .pressScale(interaction)
            .height(48.dp)
            .darkGlass(shape)
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
        LuIcon(Lu.Image, contentDescription = null, tint = LdColors.onGlass, size = 20.dp)
        Text(stringResource(R.string.draw_partner), style = LdType.Caption, color = LdColors.onGlass, maxLines = 1)
    }
}

@Composable
private fun DockRow(
    model: DrawingViewModel,
    sheet: DrawSheet?,
    busy: Busy?,
    canSave: Boolean,
    canSend: Boolean,
    onPlace: (DrawSheet, Offset, IntSize) -> Unit,
    onTool: (DrawSheet) -> Unit,
    onEraser: () -> Unit,
    onSave: () -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
) {
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
            enabled = canSave,
            spinning = busy == Busy.Save,
            onClick = onSave,
        )
        SendButton(enabled = canSend, spinning = busy == Busy.Send, onClick = onSend)
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
        !enabled -> LdColors.onGlass.copy(alpha = 0.35f)
        highlighted -> LdColors.navy
        else -> LdColors.onGlass
    }
    Box(
        modifier = Modifier
            .pressScale(interaction)
            .size(48.dp)
            .clip(CircleShape)
            .background(if (highlighted) LdColors.white.copy(alpha = 0.92f) else Color.Transparent)
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
            CircularProgressIndicator(modifier = Modifier.size(22.dp), color = LdColors.onGlass, strokeWidth = 2.dp)
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
                    .border(1.dp, Color.White.copy(alpha = 0.35f), CircleShape),
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
    CompositionLocalProvider(LocalOnGlass provides true) {
        Column(
            Modifier
                .fillMaxWidth()
                .ldGlass(RoundedCornerShape(LdRadius.card), solid = true),
        ) {
            content()
        }
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
private fun ClearMenu(
    clearEnabled: Boolean,
    shared: Boolean,
    onSaved: () -> Unit,
    onNewPage: () -> Unit,
    onClear: () -> Unit,
) {
    val shape = RoundedCornerShape(LdRadius.tile)
    Column(
        Modifier
            .widthIn(min = 200.dp, max = 260.dp)
            .darkGlass(shape)
            .padding(4.dp),
    ) {
        MenuRow(icon = Lu.Image, label = stringResource(R.string.draw_saved_title), enabled = true, onClick = onSaved)
        if (shared) {
            MenuRow(icon = Lu.Plus, label = stringResource(R.string.draw_new_page), enabled = true, onClick = onNewPage)
        }
        MenuRow(icon = Lu.Trash2, label = stringResource(R.string.draw_clear), enabled = clearEnabled, onClick = onClear)
    }
}

@Composable
private fun MenuRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val item = RoundedCornerShape(LdRadius.inner(LdRadius.tile, 4.dp))
    val interaction = remember(label) { MutableInteractionSource() }
    val ink = if (enabled) LdColors.onGlass else LdColors.onGlass.copy(alpha = 0.35f)
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
                onClick = onClick,
            )
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        LuIcon(icon, contentDescription = null, tint = ink, size = 20.dp)
        Text(label, style = LdType.Title, color = ink, maxLines = 1)
    }
}

@Composable
private fun ClearLayerDialog(onCancel: () -> Unit, onClear: () -> Unit) {
    val shape = RoundedCornerShape(LdRadius.card)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .darkGlass(shape)
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = {})
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.draw_clear_title), style = LdType.Title, color = LdColors.onGlass)
        Text(stringResource(R.string.draw_clear_body), style = LdType.Body, color = LdColors.onGlassMuted)
        DialogButton(
            text = stringResource(R.string.draw_cancel),
            style = LdButtonStyle.Primary,
            enabled = true,
            onClick = onCancel,
        )
        DialogButton(
            text = stringResource(R.string.draw_clear),
            style = LdButtonStyle.Tertiary,
            enabled = true,
            contentColor = Color(0xFFFFB4AB),
            onClick = onClear,
        )
    }
}

@Composable
private fun NoticeChip(notice: Notice) {
    val shape = RoundedCornerShape(LdRadius.chip)
    val fg = if (notice.ok) LdColors.white else LdColors.onGlass
    val plate = if (notice.ok) {
        Modifier.clip(shape).background(LdColors.success)
    } else {
        Modifier.darkGlass(shape)
    }
    Row(
        modifier = Modifier
            .then(plate)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        LuIcon(
            if (notice.ok) Lu.Check else Lu.TriangleAlert,
            contentDescription = null,
            tint = if (notice.ok) LdColors.white else Color(0xFFFFB4AB),
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
    val shape = RoundedCornerShape(LdRadius.card)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .darkGlass(shape)
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = {})
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.draw_unsaved_title), style = LdType.Title, color = LdColors.onGlass)
        Text(stringResource(R.string.draw_unsaved_body), style = LdType.Body, color = LdColors.onGlassMuted)
        DialogButton(
            text = stringResource(R.string.draw_keep),
            style = LdButtonStyle.Primary,
            enabled = true,
            onClick = onKeep,
        )
        DialogButton(
            text = stringResource(R.string.draw_save),
            style = LdButtonStyle.Secondary,
            enabled = !busy,
            loading = busy,
            onClick = onSave,
        )
        DialogButton(
            text = stringResource(R.string.draw_discard),
            style = LdButtonStyle.Tertiary,
            enabled = !busy,
            contentColor = Color(0xFFFFB4AB),
            onClick = onDiscard,
        )
    }
}

@Composable
private fun DialogButton(
    text: String,
    style: LdButtonStyle,
    enabled: Boolean,
    loading: Boolean = false,
    contentColor: Color? = null,
    onClick: () -> Unit,
) {
    LdButton(
        text = text,
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        style = style,
        enabled = enabled,
        loading = loading,
        contentColor = contentColor,
    )
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
