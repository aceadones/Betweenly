package com.nextlayer.betweenly.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.pager.PagerState
import com.nextlayer.betweenly.R
import com.nextlayer.betweenly.Store
import com.nextlayer.betweenly.ui.icons.Lu
import com.nextlayer.betweenly.ui.icons.LuIcon
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** One idea per page. Background location is inserted by the screen on Android 10+. */
enum class StoryPage {
    Welcome,
    Privacy,
    You,
    Notify,
    Location,
    Background,
    Battery,
    Ready,
}

/** Illustration lags the page so the type moves ahead of the drawing. */
fun Modifier.storyParallax(state: PagerState, page: Int): Modifier = graphicsLayer {
    val fraction = ((state.currentPage - page) + state.currentPageOffsetFraction).coerceIn(-1f, 1f)
    translationX = -fraction * size.width * 0.4f
}

/** Fade and a 6dp rise. Stagger is 35ms, and only the first 8 move. */
@Composable
fun Modifier.storyEnter(index: Int = 0): Modifier {
    val on = Motion.enabled
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val delayMs = if (on) index.coerceIn(0, 7) * 35 else 0
    val alpha by animateFloatAsState(
        targetValue = if (!on || shown) 1f else 0f,
        animationSpec = Motion.tween(Motion.MED, Motion.easeOut, delayMs = delayMs),
        label = "storyA",
    )
    val dy by animateDpAsState(
        targetValue = if (!on || shown) 0.dp else 6.dp,
        animationSpec = Motion.tween(Motion.MED, Motion.easeOut, delayMs = delayMs),
        label = "storyY",
    )
    if (!on) return this
    return graphicsLayer {
        this.alpha = alpha
        translationY = dy.toPx()
    }
}

@Composable
fun StoryChrome(
    page: Int,
    count: Int,
    canBack: Boolean,
    canSkip: Boolean,
    onBack: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val step = stringResource(R.string.onb_progress, page + 1, count)
    val sky = LdColors.colors.skyTop
    // Opaque plate, then a short fade into the page. The pager is laid out under
    // this whole block, so a headline cannot scroll or swipe under Back, the dots, or Skip.
    Column(modifier.fillMaxWidth()) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(sky)
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (canBack) {
                    GlassIconButton(
                        onClick = onBack,
                        icon = Lu.ChevronLeft,
                        description = stringResource(R.string.back),
                    )
                } else {
                    Spacer(Modifier.size(48.dp))
                }
                StoryProgress(
                    count = count,
                    page = page,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 10.dp)
                        .semantics { contentDescription = step },
                )
                if (canSkip) {
                    Box(
                        Modifier
                            .heightIn(min = 48.dp)
                            .clip(RoundedCornerShape(LdRadius.chip))
                            .clickable(role = Role.Button, onClick = onSkip)
                            .padding(horizontal = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            stringResource(R.string.onb_skip),
                            style = MaterialTheme.typography.titleSmall,
                            color = LdColors.colors.ink,
                        )
                    }
                } else {
                    Spacer(Modifier.width(64.dp))
                }
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(16.dp)
                .background(Brush.verticalGradient(listOf(sky, sky.copy(alpha = 0f)))),
        )
    }
}

@Composable
private fun StoryProgress(count: Int, page: Int, modifier: Modifier = Modifier) {
    Row(
        modifier.height(48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        repeat(count) { index ->
            val target = if (index <= page) 1f else 0.22f
            val fill by animateFloatAsState(
                targetValue = target,
                animationSpec = Motion.tween(Motion.MED, Motion.easeOut),
                label = "seg",
            )
            Box(
                Modifier
                    .weight(1f)
                    .height(3.dp)
                    .clip(RoundedCornerShape(LdRadius.chip))
                    .background(LdColors.colors.ink.copy(alpha = 0.18f)),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(fill.coerceIn(0.08f, 1f))
                        .height(3.dp)
                        .background(LdColors.colors.ink),
                )
            }
        }
    }
}

/**
 * Largest square that fits this slot, centred. The bitmap is fitted inside,
 * so a 1:1 scene is never cropped. Margins stay empty and show the page.
 */
@Composable
private fun StoryArtFrame(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    BoxWithConstraints(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val side = if (maxHeight.value.isFinite()) minOf(maxWidth, maxHeight) else maxWidth
        Box(Modifier.size(side), content = content)
    }
}

@Composable
fun StoryBody(
    art: @Composable () -> Unit,
    actions: @Composable ColumnScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        StoryArtFrame(Modifier.weight(1.15f)) {
            art()
        }
        Column(Modifier.weight(1f).fillMaxWidth()) {
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                content = content,
            )
            Column(
                Modifier
                    .fillMaxWidth()
                    .imePadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 24.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                content = actions,
            )
        }
    }
}

/**
 * Blush in light, wine in dark. The fade ends before the type, and the plate
 * under the words is 94% of that surface.
 *
 * Body ink on that plate stays above 4.5:1 in both themes, including when the
 * art behind the scrim is white (dark) or black (light).
 */
private fun Modifier.heroScrim(surface: Color): Modifier = drawBehind {
    val fade = 48.dp.toPx()
    val edge = if (size.height <= 1f) 1f else (fade / size.height).coerceIn(0f, 1f)
    val solid = surface.copy(alpha = 0.94f)
    drawRect(
        brush = Brush.verticalGradient(
            0f to surface.copy(alpha = 0f),
            edge to solid,
            1f to solid,
        ),
    )
}

@Composable
fun WelcomePage(pager: PagerState, page: Int, onStart: () -> Unit) {
    var risen by remember { mutableStateOf(!Motion.enabled) }
    LaunchedEffect(Unit) { risen = true }
    val rise by animateDpAsState(
        targetValue = if (risen) 0.dp else 36.dp,
        animationSpec = Motion.tween(Motion.SLOW, Motion.easeOut),
        label = "sheet",
    )
    val fade by animateFloatAsState(
        targetValue = if (risen) 1f else 0f,
        animationSpec = Motion.tween(Motion.SLOW, Motion.easeOut),
        label = "sheetA",
    )
    val palette = LdColors.colors
    val surface = if (isSystemInDarkTheme()) palette.skyBottom else palette.skyTop
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val boxMaxHeight = this.maxHeight
        Column(Modifier.fillMaxSize()) {
            StoryArtFrame(Modifier.weight(1f)) {
                WelcomeScene(
                    Modifier
                        .fillMaxSize()
                        .storyParallax(pager, page),
                )
                BrandMark(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 8.dp)
                        .storyEnter(0),
                    size = 56.dp,
                )
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = boxMaxHeight * 0.58f)
                    .graphicsLayer {
                        translationY = rise.toPx()
                        alpha = fade
                    }
                    .heroScrim(surface)
                    .navigationBarsPadding()
                    .padding(start = 24.dp, end = 24.dp, top = 64.dp, bottom = 12.dp),
            ) {
                // Padding sits outside the scroll, so the type stays on the solid part of the scrim.
                Column(
                    Modifier
                        .weight(1f, fill = false)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(
                        stringResource(R.string.onb_welcome_title),
                        modifier = Modifier.storyEnter(1),
                        style = LdType.Hero,
                        color = palette.ink,
                    )
                    Text(
                        stringResource(R.string.onb_welcome_sub),
                        modifier = Modifier.storyEnter(2),
                        style = MaterialTheme.typography.bodyLarge,
                        color = palette.inkMuted,
                    )
                }
                PrimaryPill(
                    text = stringResource(R.string.onb_get_started),
                    onClick = onStart,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                )
            }
        }
    }
}

@Composable
fun PrivacyPage(onOpen: () -> Unit, onNext: () -> Unit) {
    StoryBody(
        art = {
            StoryAsset(
                res = R.drawable.onb_privacy,
                modifier = Modifier.fillMaxSize(),
                scale = ContentScale.Fit,
            ) {
                IllustrationTile(Lu.ShieldCheck, Modifier.fillMaxSize())
            }
        },
        actions = {
            PrimaryPill(
                text = stringResource(R.string.onboarding_continue),
                onClick = onNext,
                modifier = Modifier.fillMaxWidth(),
            )
        },
    ) {
        Text(
            stringResource(R.string.onb_privacy_title),
            modifier = Modifier.storyEnter(0),
            style = LdType.Lead,
            color = LdColors.colors.ink,
        )
        Text(
            stringResource(R.string.onboarding_intro),
            modifier = Modifier.storyEnter(1),
            style = MaterialTheme.typography.bodyLarge,
            color = LdColors.colors.inkMuted,
        )
        PromiseTile(Lu.ShieldCheck, R.string.onb_privacy_e2e, R.string.onb_privacy_e2e_body, 2)
        PromiseTile(Lu.EyeOff, R.string.onb_privacy_none, R.string.onb_privacy_none_body, 3)
        PromiseTile(Lu.Lock, R.string.onb_privacy_share, R.string.onb_privacy_share_body, 4)
        Text(
            stringResource(R.string.onb_where),
            modifier = Modifier
                .storyEnter(5)
                .heightIn(min = 48.dp)
                .clickable(role = Role.Button, onClick = onOpen)
                .padding(vertical = 12.dp),
            color = LdColors.colors.textPrimary,
            textDecoration = TextDecoration.Underline,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

@Composable
private fun PromiseTile(icon: ImageVector, title: Int, body: Int, index: Int) {
    val well = RoundedCornerShape(LdRadius.inner(LdRadius.card, 12.dp))
    Row(
        Modifier
            .fillMaxWidth()
            .storyEnter(index)
            .ldGlass()
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(64.dp)
                .clip(well)
                .background(LdColors.primary.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            LuIcon(icon, contentDescription = null, tint = LdColors.colors.textPrimary, size = 28.dp)
        }
        Column(Modifier.padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleMedium, color = LdColors.colors.ink)
            Text(stringResource(body), style = MaterialTheme.typography.bodyMedium, color = LdColors.colors.inkMuted)
        }
    }
}

@Composable
fun YouPage(name: String, onName: (String) -> Unit, onNext: () -> Unit) {
    val ready = name.trim().isNotEmpty()
    StoryBody(
        art = {
            StoryAsset(
                res = R.drawable.onb_name,
                modifier = Modifier.fillMaxSize(),
                scale = ContentScale.Fit,
            ) {
                IllustrationTile(Lu.User, Modifier.fillMaxSize())
            }
        },
        actions = {
            PrimaryPill(
                text = stringResource(R.string.onboarding_continue),
                onClick = onNext,
                enabled = ready,
                modifier = Modifier.fillMaxWidth(),
            )
        },
    ) {
        Text(
            stringResource(R.string.onb_you_title),
            modifier = Modifier.storyEnter(0),
            style = LdType.PageTitle,
            color = LdColors.colors.ink,
        )
        Text(
            stringResource(R.string.onb_you_hint),
            modifier = Modifier.storyEnter(1),
            style = MaterialTheme.typography.bodyLarge,
            color = LdColors.colors.inkMuted,
        )
        HeroPhoto(
            name = name.ifBlank { stringResource(R.string.partner_fallback) },
            modifier = Modifier
                .storyEnter(2)
                .align(Alignment.CenterHorizontally),
        )
        Text(
            stringResource(R.string.photo_help),
            style = MaterialTheme.typography.bodySmall,
            color = LdColors.colors.inkMuted,
        )
        UnderlineNameField(
            value = name,
            onValue = onName,
            label = stringResource(R.string.name_label),
            onDone = { if (ready) onNext() },
        )
        Text(
            stringResource(R.string.name_help),
            style = MaterialTheme.typography.bodySmall,
            color = LdColors.colors.inkMuted,
        )
    }
}

@Composable
private fun HeroPhoto(name: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val revision by Store.changes.collectAsState()
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        failed = false
        scope.launch {
            val jpeg = AvatarJpeg.fromUri(context, uri)
            busy = false
            if (jpeg == null) {
                failed = true
            } else {
                // DATA-FLOW: none. The photo stays on this phone until pairing sends it encrypted.
                Store.setMyAvatar(context, jpeg)
            }
        }
    }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(156.dp)
                .clickable(
                    enabled = !busy,
                    onClickLabel = stringResource(R.string.photo_choose),
                    role = Role.Button,
                    onClick = {
                        try {
                            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        } catch (_: Exception) {
                            failed = true
                        }
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            RoundAvatar(revision, { Store.myAvatar(context) }, name, 148.dp)
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .size(40.dp)
                    .shadow(6.dp, CircleShape)
                    .clip(CircleShape)
                    .background(LdColors.colors.pill),
                contentAlignment = Alignment.Center,
            ) {
                LuIcon(Lu.Camera, contentDescription = null, tint = LdColors.colors.onPill, size = 20.dp)
            }
        }
        if (busy) {
            Spacer(Modifier.height(8.dp))
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        }
        if (failed) {
            Text(
                stringResource(R.string.photo_failed),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
fun PermissionPage(
    title: String,
    line: String,
    icon: ImageVector,
    art: Int = 0,
    granted: Boolean,
    onAllow: (() -> Unit)?,
    onContinue: () -> Unit,
    onLater: () -> Unit,
    onSettings: () -> Unit,
    showFootnote: Boolean,
) {
    StoryBody(
        art = {
            if (art != 0) {
                StoryAsset(
                    res = art,
                    modifier = Modifier.fillMaxSize(),
                    scale = ContentScale.Fit,
                ) {
                    IllustrationTile(icon, Modifier.fillMaxSize())
                }
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    IllustrationTile(icon, Modifier.size(220.dp))
                }
            }
        },
        actions = {
            if (granted || onAllow == null) {
                PrimaryPill(
                    text = stringResource(R.string.onboarding_continue),
                    onClick = onContinue,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                PrimaryPill(
                    text = stringResource(R.string.perm_allow),
                    onClick = onAllow,
                    modifier = Modifier.fillMaxWidth(),
                )
                QuietButton(stringResource(R.string.onb_maybe_later), onLater)
                QuietButton(stringResource(R.string.perm_settings), onSettings)
            }
            if (showFootnote) {
                Text(
                    stringResource(R.string.onboarding_footnote),
                    style = MaterialTheme.typography.bodySmall,
                    color = LdColors.colors.inkMuted,
                )
            }
        },
    ) {
        Text(title, modifier = Modifier.storyEnter(0), style = LdType.PageTitle, color = LdColors.colors.ink)
        Text(
            line,
            modifier = Modifier.storyEnter(1),
            style = MaterialTheme.typography.bodyLarge,
            color = LdColors.colors.inkMuted,
        )
        Row(
            Modifier.storyEnter(2).heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GrantMark(granted)
            Spacer(Modifier.width(10.dp))
            Text(
                stringResource(if (granted) R.string.onb_done else R.string.perm_not_allowed),
                style = MaterialTheme.typography.titleMedium,
                color = if (granted) LdColors.success else LdColors.colors.inkMuted,
            )
        }
    }
}

@Composable
fun ReadyPage(onNext: () -> Unit) {
    StoryBody(
        art = {
            Box(Modifier.fillMaxSize()) {
                StoryAsset(
                    res = R.drawable.onb_allset,
                    modifier = Modifier.fillMaxSize(),
                    scale = ContentScale.Fit,
                ) {
                    Box(Modifier.fillMaxSize())
                }
                SoftConfetti(Modifier.fillMaxSize(), seed = 3)
            }
        },
        actions = {
            PrimaryPill(
                text = stringResource(R.string.onb_connect),
                onClick = onNext,
                modifier = Modifier.fillMaxWidth(),
            )
        },
    ) {
        Text(
            stringResource(R.string.onb_all_title),
            modifier = Modifier.storyEnter(0),
            style = LdType.Lead,
            color = LdColors.colors.ink,
        )
        Text(
            stringResource(R.string.onb_all_sub),
            modifier = Modifier.storyEnter(1),
            style = MaterialTheme.typography.bodyLarge,
            color = LdColors.colors.inkMuted,
        )
    }
}

/**
 * After a fresh grant, wait a moment so the check is visible, then move on.
 * Landing on a page that is already granted does not advance.
 */
@Composable
fun AdvanceAfterGrant(granted: Boolean, active: Boolean, onGranted: () -> Unit) {
    var seen by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(granted, active) {
        val previous = seen
        seen = granted
        if (active && previous == false && granted) {
            delay(480)
            onGranted()
        }
    }
}

@Composable
fun GrantMark(granted: Boolean, modifier: Modifier = Modifier) {
    val p by animateFloatAsState(
        targetValue = if (granted) 1f else 0f,
        animationSpec = Motion.tween(240, Motion.easeOut),
        label = "mark",
    )
    val label = stringResource(if (granted) R.string.onb_done else R.string.perm_not_allowed)
    // LdColors.colors is composable; read it before the draw scope. Alpha still tracks p.
    val muted = LdColors.colors.inkMuted
    Canvas(
        modifier
            .size(28.dp)
            .semantics { contentDescription = label },
    ) {
        val stroke = 2.dp.toPx()
        if (p < 0.98f) {
            drawCircle(
                color = muted.copy(alpha = 1f - p),
                radius = size.minDimension / 2f - stroke,
                style = Stroke(width = stroke),
            )
        }
        if (p > 0f) {
            drawCircle(color = LdColors.success, radius = (size.minDimension / 2f) * p)
            val t = ((p - 0.25f) / 0.75f).coerceIn(0f, 1f)
            if (t > 0f) {
                val path = Path()
                val a = Offset(size.width * 0.28f, size.height * 0.52f)
                val b = Offset(size.width * 0.44f, size.height * 0.68f)
                val c = Offset(size.width * 0.74f, size.height * 0.34f)
                path.moveTo(a.x, a.y)
                if (t < 0.45f) {
                    val u = t / 0.45f
                    path.lineTo(a.x + (b.x - a.x) * u, a.y + (b.y - a.y) * u)
                } else {
                    path.lineTo(b.x, b.y)
                    val u = ((t - 0.45f) / 0.55f).coerceIn(0f, 1f)
                    path.lineTo(b.x + (c.x - b.x) * u, b.y + (c.y - b.y) * u)
                }
                drawPath(
                    path,
                    Color.White,
                    style = Stroke(width = 2.4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
            }
        }
    }
}
