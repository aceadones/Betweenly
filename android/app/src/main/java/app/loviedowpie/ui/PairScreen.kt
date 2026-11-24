package app.loviedowpie.ui

import androidx.compose.runtime.collectAsState
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import app.loviedowpie.Core
import app.loviedowpie.R
import kotlinx.coroutines.launch

/**
 * Shows a pairing code, or takes the partner's code, then the safety number.
 * The code is on the screen only. This screen does not log it.
 *
 * DATA-FLOW: [PairSession] calls [app.loviedowpie.Pairing], which sends the
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

    Scaffold(
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets.safeDrawing,
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.pair_title), style = MaterialTheme.typography.headlineLarge)
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
                    onContinue = {
                        PairSession.acknowledge()
                        onPaired()
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
        }
    }
}

@Composable
private fun Choose(onShow: () -> Unit, onEnter: () -> Unit) {
    Text(
        stringResource(R.string.pair_show_help),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    PrimaryPill(
        text = stringResource(R.string.pair_show),
        onClick = onShow,
        modifier = Modifier.fillMaxWidth(),
    )
    Text(
        stringResource(R.string.pair_enter_help),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    SecondaryPill(
        text = stringResource(R.string.pair_enter),
        onClick = onEnter,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun EnterForm(
    typed: String,
    onTyped: (String) -> Unit,
    onJoin: () -> Unit,
    onBack: () -> Unit,
) {
    val accepted = codeAccepted(typed)
    val full = typed.count { it.isLetterOrDigit() } >= 8
    Text(
        stringResource(R.string.pair_enter_help),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedTextField(
        value = typed,
        onValueChange = onTyped,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(stringResource(R.string.pair_code_hint)) },
        singleLine = true,
        textStyle = MaterialTheme.typography.headlineSmall.copy(fontFamily = FontFamily.Monospace),
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Characters,
            keyboardType = KeyboardType.Ascii,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = { if (accepted) onJoin() }),
    )
    if (full && !accepted) {
        Text(
            stringResource(R.string.pair_invalid_code),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    PrimaryPill(
        text = stringResource(R.string.pair_join),
        onClick = onJoin,
        enabled = accepted,
        modifier = Modifier.fillMaxWidth(),
    )
    TextButton(onClick = onBack) { Text(stringResource(R.string.back)) }
}

@Composable
private fun Waiting(
    code: String,
    showLetters: Boolean,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onCancel: () -> Unit,
) {
    if (showLetters && code.isNotEmpty()) {
        Text(
            groupCode(code),
            style = MaterialTheme.typography.displaySmall.copy(fontFamily = FontFamily.Monospace),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryPill(text = stringResource(R.string.pair_copy), onClick = onCopy)
            SecondaryPill(text = stringResource(R.string.pair_share), onClick = onShare)
        }
    } else if (showLetters) {
        Text(stringResource(R.string.pair_making), style = MaterialTheme.typography.bodyLarge)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(12.dp))
        Text(stringResource(R.string.pair_waiting), style = MaterialTheme.typography.bodyLarge)
    }
    Text(
        stringResource(R.string.pair_waiting_hint),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    TextButton(onClick = onCancel) { Text(stringResource(R.string.pair_cancel)) }
}

@Composable
private fun Safety(number: String, onContinue: () -> Unit) {
    Text(stringResource(R.string.pair_safety_title), style = MaterialTheme.typography.titleLarge)
    Text(
        number.ifBlank { stringResource(R.string.safety_missing) },
        style = MaterialTheme.typography.displaySmall.copy(fontFamily = FontFamily.Monospace),
    )
    Text(stringResource(R.string.pair_safety_body), style = MaterialTheme.typography.bodyLarge)
    PrimaryPill(
        text = stringResource(R.string.pair_continue),
        onClick = onContinue,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ErrorBlock(errorRes: Int, onBack: () -> Unit) {
    val res = if (errorRes != 0) errorRes else R.string.pair_failed
    Text(stringResource(res), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
    if (res == R.string.codes_did_not_match) {
        Text(stringResource(R.string.pair_mismatch_help), style = MaterialTheme.typography.bodyLarge)
    }
    TextButton(onClick = onBack) { Text(stringResource(R.string.back)) }
}

/** Groups 8 characters as "ABCD EFGH" for the screen. Does not log the code. */
private fun groupCode(raw: String): String {
    val chars = raw.uppercase().filter { it.isLetterOrDigit() }.take(8)
    return if (chars.length <= 4) chars else chars.substring(0, 4) + " " + chars.substring(4)
}

private fun codeAccepted(raw: String): Boolean = try {
    Core.normalizeCode(raw).isNotEmpty()
} catch (_: Exception) {
    false
}

private fun compact(code: String): String = code.filter { it.isLetterOrDigit() }

private fun copyCode(context: android.content.Context, code: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    val label = context.getString(R.string.pair_code_label)
    clipboard.setPrimaryClip(ClipData.newPlainText(label, compact(code)))
}

private fun shareCode(context: android.content.Context, code: String) {
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_TEXT, compact(code))
    try {
        context.startActivity(Intent.createChooser(send, context.getString(R.string.pair_share_chooser)))
    } catch (_: Exception) {
        // No app accepted the share sheet.
    }
}
