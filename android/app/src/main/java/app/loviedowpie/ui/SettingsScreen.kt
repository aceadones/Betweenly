package app.loviedowpie.ui

import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import app.loviedowpie.BuildConfig
import app.loviedowpie.Outbox
import app.loviedowpie.R
import app.loviedowpie.Store
import app.loviedowpie.UpdateInfo
import app.loviedowpie.Updater
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/**
 * Name, photo, the same sharing switches as home, update check, safety number,
 * and unpair. A changed name or photo is sealed and sent to the partner.
 */
@Composable
fun SettingsScreen(onBack: () -> Unit, onPrivacy: () -> Unit, onUnpaired: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val revision by Store.changes.collectAsState()
    var name by rememberSaveable { mutableStateOf(Store.myName(context)) }
    var confirm by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var update by remember { mutableStateOf<UpdateInfo?>(null) }
    val safety = remember(revision) { Store.safetyNumber(context) }

    fun sendProfile() {
        scope.launch {
            val ok = try {
                // DATA-FLOW: my name and photo, encrypted, to my partner, with an empty wake ping.
                Outbox.sendProfile(context, wake = true)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
            snackbar.showSnackbar(
                context.getString(if (ok) R.string.profile_sent else R.string.profile_failed),
            )
        }
    }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(R.string.unpair_title)) },
            text = { Text(stringResource(R.string.unpair_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirm = false
                        Store.unpair(context)
                        PairSession.reset()
                        LoviedowpieWidget.updateAll(context)
                        onUnpaired()
                    },
                ) { Text(stringResource(R.string.unpair_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.pair_cancel)) }
            },
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing,
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ScreenHeader(stringResource(R.string.settings), onBack)
            PhotoPicker(name = name.ifBlank { stringResource(R.string.partner_fallback) }) { jpeg ->
                val clean = name.trim()
                if (clean.isNotEmpty()) Store.setMyName(context, clean)
                Store.setMyAvatar(context, jpeg)
                sendProfile()
            }
            OutlinedTextField(
                value = name,
                onValueChange = { value ->
                    if (value.length <= 80) name = value.replace("\n", " ")
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.name_label)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
            )
            Button(
                onClick = {
                    val clean = name.trim()
                    if (clean.isEmpty()) return@Button
                    Store.setMyName(context, clean)
                    sendProfile()
                },
                enabled = name.trim().isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.save_name))
            }
            ShareToggles(snackbar)
            Button(
                onClick = {
                    if (checking) return@Button
                    checking = true
                    scope.launch {
                        val info = try {
                            // DATA-FLOW: update metadata, from the relay, not encrypted, no user data.
                            Updater.check(context, force = true)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            null
                        }
                        update = info
                        status = if (info == null) context.getString(R.string.update_none) else null
                        checking = false
                    }
                },
                enabled = !checking,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.check_updates))
            }
            if (checking) {
                CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
            }
            status?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            update?.let { UpdateBanner(it) }
            Text(stringResource(R.string.pair_safety_title), style = MaterialTheme.typography.titleMedium)
            Text(
                safety.ifBlank { stringResource(R.string.safety_missing) },
                style = MaterialTheme.typography.headlineMedium.copy(fontFamily = FontFamily.Monospace),
            )
            Text(
                stringResource(R.string.safety_help),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = { confirm = true }) {
                Text(stringResource(R.string.unpair), color = MaterialTheme.colorScheme.error)
            }
            Text(stringResource(R.string.about_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.about_body), style = MaterialTheme.typography.bodyMedium)
            Text(
                stringResource(R.string.version_line, BuildConfig.VERSION_NAME),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.privacy_link),
                color = MaterialTheme.colorScheme.primary,
                textDecoration = TextDecoration.Underline,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.clickable(onClick = onPrivacy),
            )
        }
    }
}
