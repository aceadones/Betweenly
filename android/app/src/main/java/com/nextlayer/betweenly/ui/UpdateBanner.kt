package com.nextlayer.betweenly.ui

import android.os.SystemClock
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.nextlayer.betweenly.R
import com.nextlayer.betweenly.UpdateInfo
import com.nextlayer.betweenly.Updater
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

/**
 * "Update available" with Download and Install. The APK is checked by [Updater]
 * before Install is enabled.
 *
 * DATA-FLOW: the download is the published APK from the relay or a public GitHub release, not encrypted, no user data.
 */
@Composable
fun UpdateBanner(info: UpdateInfo, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var downloading by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var path by rememberSaveable { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val apk = path?.let { File(it) }?.takeIf { it.isFile }
    val label = info.versionName.ifBlank { info.versionCode.toString() }

    Column(
        modifier = modifier.fillMaxWidth().ldGlass().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
            Text(
                stringResource(R.string.update_available, label),
                style = MaterialTheme.typography.titleMedium,
                color = LdColors.onGlass,
            )
            if (info.notes.isNotBlank()) {
                Text(
                    info.notes,
                    style = MaterialTheme.typography.bodySmall,
                    color = LdColors.onGlassMuted,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (downloading) {
                LinearProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                    color = LdColors.onGlass,
                    trackColor = Color.White.copy(alpha = 0.22f),
                )
            }
            error?.let { message ->
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFFFB4AB),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LdButton(
                    text = stringResource(R.string.update_download),
                    loading = downloading,
                    onClick = {
                        if (downloading) return@LdButton
                        downloading = true
                        error = null
                        progress = 0f
                        scope.launch {
                            var last = 0L
                            val file = try {
                                // DATA-FLOW: APK bytes and the published hash, from the relay, not encrypted, no user data.
                                Updater.downloadAndVerify(context, info) { value ->
                                    val now = SystemClock.uptimeMillis()
                                    if (now - last > 80L || value >= 1f) {
                                        last = now
                                        val shown = value.coerceIn(0f, 1f)
                                        scope.launch { progress = shown }
                                    }
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Exception) {
                                null
                            }
                            downloading = false
                            if (file == null) {
                                path = null
                                error = context.getString(R.string.update_failed)
                            } else {
                                path = file.absolutePath
                                progress = 1f
                            }
                        }
                    },
                )
                LdButton(
                    text = stringResource(R.string.update_install),
                    onClick = {
                        val file = apk ?: return@LdButton
                        val opened = Updater.install(context, file)
                        error = if (opened) null else context.getString(R.string.update_allow_install)
                    },
                style = LdButtonStyle.Secondary,
                enabled = apk != null && !downloading,
            )
        }
    }
}

/**
 * Dark-glass "Update available" dialog. [onLater] hides it. The APK is checked
 * by [Updater] before the system installer opens.
 *
 * DATA-FLOW: the download is the published APK from the relay or a public GitHub
 * release, not encrypted, no user data.
 */
@Composable
fun UpdateAvailableDialog(info: UpdateInfo, onLater: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var job by remember { mutableStateOf<Job?>(null) }
    var downloading by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var path by rememberSaveable { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val apk = path?.let { File(it) }?.takeIf { it.isFile }
    val quiet = remember { MutableInteractionSource() }

    fun dismiss() {
        job?.cancel()
        job = null
        onLater()
    }

    fun install(file: File) {
        val opened = Updater.install(context, file)
        if (opened) {
            dismiss()
        } else {
            error = context.getString(R.string.update_allow_install_again)
        }
    }

    Dialog(
        onDismissRequest = { dismiss() },
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        val dialogView = LocalView.current
        SideEffect {
            (dialogView.parent as? DialogWindowProvider)?.window?.apply {
                setDimAmount(0f)
                setLayout(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                )
            }
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.5f))
                .clickable(interactionSource = quiet, indication = null) { dismiss() },
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .padding(horizontal = 24.dp)
                    .fillMaxWidth()
                    .darkGlass(RoundedCornerShape(LdRadius.card))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    stringResource(R.string.update_dialog_title, info.label()),
                    style = MaterialTheme.typography.titleMedium,
                    color = LdColors.onGlass,
                )
                if (info.notes.isNotBlank()) {
                    Text(
                        info.notes,
                        style = MaterialTheme.typography.bodyMedium,
                        color = LdColors.onGlassMuted,
                        maxLines = 8,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (downloading) {
                    LinearProgressIndicator(
                        progress = { progress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                        color = LdColors.onGlass,
                        trackColor = Color.White.copy(alpha = 0.22f),
                    )
                }
                error?.let { message ->
                    Text(
                        message,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFFFB4AB),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    LdButton(
                        text = stringResource(R.string.update_later),
                        onClick = { dismiss() },
                        style = LdButtonStyle.Secondary,
                        modifier = Modifier.weight(1f),
                    )
                    LdButton(
                        text = stringResource(R.string.update_now),
                        loading = downloading,
                        onClick = {
                            if (downloading) return@LdButton
                            val ready = apk
                            if (ready != null) {
                                install(ready)
                                return@LdButton
                            }
                            downloading = true
                            error = null
                            progress = 0f
                            job = scope.launch {
                                var last = 0L
                                val file = try {
                                    // DATA-FLOW: APK bytes and the published hash, from the relay or GitHub, not encrypted, no user data.
                                    Updater.downloadAndVerify(context, info) { value ->
                                        val now = SystemClock.uptimeMillis()
                                        if (now - last > 80L || value >= 1f) {
                                            last = now
                                            val shown = value.coerceIn(0f, 1f)
                                            scope.launch { progress = shown }
                                        }
                                    }
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (_: Exception) {
                                    null
                                }
                                downloading = false
                                job = null
                                if (file == null) {
                                    path = null
                                    error = context.getString(R.string.update_failed)
                                } else {
                                    path = file.absolutePath
                                    progress = 1f
                                    install(file)
                                }
                            }
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}
