package com.nextlayer.betweenly.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.nextlayer.betweenly.R
import com.nextlayer.betweenly.Store
import com.nextlayer.betweenly.ui.icons.Lu
import com.nextlayer.betweenly.ui.icons.LuIcon
import kotlinx.coroutines.launch

/**
 * Shows a pairing code, or takes the partner's code, then the safety number.
 * The code is on the screen only. This screen does not log it.
 *
 * DATA-FLOW: [PairSession] calls [com.nextlayer.betweenly.Pairing], which sends the
 * SPAKE2 handshake (not the code) to the relay.
 */
@Composable
fun PairScreen(onPaired: () -> Unit) {
    val context = LocalContext.current
    val ui by PairSession.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var entering by rememberSaveable { mutableStateOf(false) }
    var typed by rememberSaveable { mutableStateOf("") }

    BackHandler(enabled = ui.phase != PairPhase.Choose || entering) {
        when {
            ui.phase == PairPhase.Safety -> {
                PairSession.acknowledge()
                onPaired()
            }
            ui.phase == PairPhase.WaitingShow || ui.phase == PairPhase.WaitingJoin -> PairSession.cancel()
            ui.phase == PairPhase.Error -> PairSession.acknowledge()
            entering -> entering = false
        }
    }

    Box(Modifier.fillMaxSize()) {
        when {
            ui.phase == PairPhase.WaitingShow || ui.phase == PairPhase.WaitingJoin -> Waiting(
                code = ui.shownCode,
                showLetters = ui.phase == PairPhase.WaitingShow,
                onCopy = {
                    // The code is not sent by this app. Copy hands it to the person.
                    copyCode(context, ui.shownCode)
                    scope.launch { snackbar.showSnackbar(context.getString(R.string.pair_copied)) }
                },
                onShare = {
                    // The code is not sent by this app. Share opens a chooser the person picks.
                    shareCode(context, ui.shownCode)
                },
                onCancel = { PairSession.cancel() },
            )
            ui.phase == PairPhase.Safety -> Safety(
                number = ui.safety,
                onMatch = {
                    PairSession.acknowledge()
                    onPaired()
                },
                onDiffer = {
                    val app = context.applicationContext
                    try {
                        Store.unpair(app)
                    } catch (_: Exception) {
                        // The session should be gone. The screen still returns to the start.
                    }
                    try {
                        PairSession.reset()
                    } catch (_: Exception) {
                        // The attempt is already finished.
                    }
                    try {
                        BetweenlyWidget.updateAll(app)
                    } catch (_: Exception) {
                        // The widget reads pairing again the next time it draws.
                    }
                    try {
                        DrawingWidget.updateAll(app)
                    } catch (_: Exception) {
                        // Drawings are already wiped inside unpair.
                    }
                    entering = false
                },
            )
            ui.phase == PairPhase.Error -> ErrorBlock(
                errorRes = ui.errorRes,
                onBack = { PairSession.acknowledge() },
            )
            entering -> EnterForm(
                typed = typed,
                onTyped = { typed = groupCode(it) },
                onJoin = {
                    if (codeAccepted(typed)) PairSession.startJoin(context, typed)
                },
                onBack = { entering = false },
            )
            else -> Choose(
                onShow = { PairSession.startShow(context) },
                onEnter = { entering = true },
            )
        }
        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding(),
        )
    }
}

@Composable
private fun Choose(onShow: () -> Unit, onEnter: () -> Unit) {
    PairFrame {
        Text(
            stringResource(R.string.pair_choice_title),
            modifier = Modifier.storyEnter(0),
            style = LdType.Lead,
            color = LdColors.colors.ink,
        )
        Text(
            stringResource(R.string.pair_choice_sub),
            modifier = Modifier.storyEnter(1),
            style = MaterialTheme.typography.bodyLarge,
            color = LdColors.colors.inkMuted,
        )
        Spacer(Modifier.height(8.dp))
        ChoiceCard(
            title = stringResource(R.string.pair_show),
            help = stringResource(R.string.pair_show_card_help),
            index = 2,
            onClick = onShow,
            art = { TicketArt(Modifier.fillMaxSize()) },
        )
        ChoiceCard(
            title = stringResource(R.string.pair_enter_card),
            help = stringResource(R.string.pair_enter_card_help),
            index = 3,
            onClick = onEnter,
            art = { InboxArt(Modifier.fillMaxSize()) },
        )
    }
}

@Composable
private fun ChoiceCard(
    title: String,
    help: String,
    index: Int,
    onClick: () -> Unit,
    art: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(LdRadius.card)
    val media = RoundedCornerShape(LdRadius.inner(LdRadius.card, 8.dp))
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 148.dp)
            .storyEnter(index)
            .pressScale(interaction)
            .ldGlass(shape, strong = true)
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(width = 112.dp, height = 132.dp)
                .clip(media),
        ) { art() }
        Column(
            Modifier
                .weight(1f)
                .padding(start = 12.dp, end = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(title, style = MaterialTheme.typography.headlineSmall, color = LdColors.colors.ink)
            Text(help, style = MaterialTheme.typography.bodyMedium, color = LdColors.colors.inkMuted)
            LuIcon(Lu.ArrowRight, contentDescription = null, tint = LdColors.colors.textPrimary, size = 20.dp)
        }
    }
}

@Composable
private fun EnterForm(
    typed: String,
    onTyped: (String) -> Unit,
    onJoin: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val accepted = codeAccepted(typed)
    val full = compactCode(typed).length >= 8
    val paste = clipboardOffer(context, typed)
    PairFrame(
        onBack = onBack,
        actions = {
            if (paste != null) {
                val code = paste
                QuietButton(stringResource(R.string.pair_paste), onClick = { onTyped(code) })
            }
            PrimaryPill(
                text = stringResource(R.string.pair_join),
                onClick = onJoin,
                enabled = accepted,
                modifier = Modifier.fillMaxWidth(),
            )
        },
    ) {
        Text(
            stringResource(R.string.pair_enter_card),
            modifier = Modifier.storyEnter(0),
            style = LdType.PageTitle,
            color = LdColors.colors.ink,
        )
        Text(
            stringResource(R.string.pair_enter_help),
            style = MaterialTheme.typography.bodyLarge,
            color = LdColors.colors.inkMuted,
        )
        CodeEntry(value = typed, onValue = onTyped, onDone = { if (accepted) onJoin() })
        if (full && !accepted) {
            Text(
                stringResource(R.string.pair_invalid_code),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun clipboardOffer(context: Context, current: String): String? {
    var offer by remember { mutableStateOf<String?>(null) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, current) {
        fun refresh() {
            val found = clipboardPairCode(context)
            offer = found?.takeIf { compactCode(it) != compactCode(current) }
        }
        refresh()
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    return offer
}

@Composable
private fun Waiting(
    code: String,
    showLetters: Boolean,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onCancel: () -> Unit,
) {
    PairFrame(
        onBack = onCancel,
        actions = {
            QuietButton(stringResource(R.string.pair_cancel), onCancel)
        },
    ) {
        if (showLetters && code.isNotEmpty()) {
            Text(
                stringResource(R.string.pair_show),
                modifier = Modifier.storyEnter(0),
                style = LdType.PageTitle,
                color = LdColors.colors.ink,
            )
            CodeShowTiles(code, Modifier.storyEnter(1))
            Text(
                stringResource(R.string.pair_show_help),
                style = MaterialTheme.typography.bodyMedium,
                color = LdColors.colors.inkMuted,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IconTextButton(
                    icon = Lu.Copy,
                    label = stringResource(R.string.pair_copy),
                    onClick = onCopy,
                    modifier = Modifier.weight(1f),
                )
                IconTextButton(
                    icon = Lu.Share2,
                    label = stringResource(R.string.pair_share),
                    onClick = onShare,
                    modifier = Modifier.weight(1f),
                )
            }
        } else if (showLetters) {
            Text(stringResource(R.string.pair_making), style = MaterialTheme.typography.bodyLarge, color = LdColors.colors.ink)
        } else {
            Text(
                stringResource(R.string.pair_enter_card),
                style = LdType.PageTitle,
                color = LdColors.colors.ink,
            )
        }
        WaitingArc(Modifier.align(Alignment.CenterHorizontally))
        Text(
            stringResource(R.string.pair_waiting),
            style = MaterialTheme.typography.titleMedium,
            color = LdColors.colors.ink,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
        Text(
            stringResource(R.string.pair_waiting_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = LdColors.colors.inkMuted,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun Safety(number: String, onMatch: () -> Unit, onDiffer: () -> Unit) {
    val context = LocalContext.current
    val revision by Store.changes.collectAsState()
    val mine = remember(revision) {
        Store.myName(context).ifBlank { context.getString(R.string.you) }
    }
    val partner = remember(revision) {
        Store.partnerName(context).ifBlank { context.getString(R.string.partner_fallback) }
    }
    var go by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { go = true }
    val shift by animateFloatAsState(
        targetValue = if (go) 0f else 1f,
        animationSpec = Motion.tween(Motion.SLOW, Motion.easeOut),
        label = "meet",
    )
    val pairs = digitPairs(number)
    PairFrame(
        actions = {
            PrimaryPill(
                text = stringResource(R.string.pair_they_match),
                onClick = onMatch,
                modifier = Modifier.fillMaxWidth(),
            )
            SecondaryPill(
                text = stringResource(R.string.pair_they_differ),
                onClick = onDiffer,
                modifier = Modifier.fillMaxWidth(),
            )
        },
    ) {
        Box(Modifier.fillMaxWidth().height(210.dp), contentAlignment = Alignment.Center) {
            SoftConfetti(Modifier.fillMaxSize(), seed = 11)
            MeetArc(Modifier.fillMaxSize())
            Row(verticalAlignment = Alignment.CenterVertically) {
                RoundAvatar(
                    revision,
                    { Store.myAvatar(context) },
                    mine,
                    84.dp,
                    Modifier.graphicsLayer { translationX = -28.dp.toPx() * shift },
                )
                Spacer(Modifier.width(28.dp))
                RoundAvatar(
                    revision,
                    { Store.partnerAvatar(context) },
                    partner,
                    84.dp,
                    Modifier.graphicsLayer { translationX = 28.dp.toPx() * shift },
                )
            }
        }
        Text(
            stringResource(R.string.pair_connected),
            style = LdType.PageTitle,
            color = LdColors.colors.ink,
        )
        Text(stringResource(R.string.pair_safety_title), style = MaterialTheme.typography.titleMedium, color = LdColors.colors.inkMuted)
        Text(
            pairs.ifBlank { stringResource(R.string.safety_missing) },
            style = LdType.Lead.copy(fontFamily = FontFamily.Monospace, letterSpacing = 2.sp),
            color = LdColors.colors.ink,
        )
        Text(stringResource(R.string.pair_safety_body), style = MaterialTheme.typography.bodyLarge, color = LdColors.colors.inkMuted)
    }
}

@Composable
private fun ErrorBlock(errorRes: Int, onBack: () -> Unit) {
    val res = if (errorRes != 0) errorRes else R.string.pair_failed
    PairFrame(onBack = onBack) {
        Text(stringResource(res), style = LdType.PageTitle, color = MaterialTheme.colorScheme.error)
        if (res == R.string.codes_did_not_match) {
            Text(stringResource(R.string.pair_mismatch_help), style = MaterialTheme.typography.bodyLarge, color = LdColors.colors.ink)
        }
        QuietButton(stringResource(R.string.back), onBack)
    }
}

@Composable
private fun PairFrame(
    onBack: (() -> Unit)? = null,
    actions: @Composable ColumnScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        if (onBack != null) {
            Row(
                Modifier
                    .statusBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            ) {
                GlassIconButton(
                    onClick = onBack,
                    icon = Lu.ChevronLeft,
                    description = stringResource(R.string.back),
                )
            }
        }
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .then(if (onBack == null) Modifier.statusBarsPadding() else Modifier)
                .padding(horizontal = 24.dp, vertical = 8.dp),
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

private fun digitPairs(raw: String): String {
    val digits = raw.filter { it.isDigit() }
    if (digits.length < 2) return raw.trim()
    return digits.chunked(2).joinToString(" ")
}

private fun clipboardPairCode(context: Context): String? {
    val manager = context.getSystemService(ClipboardManager::class.java) ?: return null
    val text = try {
        val clip = manager.primaryClip ?: return null
        if (clip.itemCount <= 0) return null
        clip.getItemAt(0).coerceToText(context)?.toString()
    } catch (_: Exception) {
        null
    } ?: return null
    val compact = text.uppercase().filter { it.isLetterOrDigit() }
    if (compact.length != 8) return null
    return if (codeAccepted(compact)) compact else null
}

private fun copyCode(context: Context, code: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    val label = context.getString(R.string.pair_code_label)
    clipboard.setPrimaryClip(ClipData.newPlainText(label, compactCode(code)))
}

private fun shareCode(context: Context, code: String) {
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_TEXT, compactCode(code))
    try {
        context.startActivity(Intent.createChooser(send, context.getString(R.string.pair_share_chooser)))
    } catch (_: Exception) {
        // No app accepted the share sheet.
    }
}
