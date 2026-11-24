package app.loviedowpie.ui

import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.loviedowpie.R
import app.loviedowpie.UpdateInfo
import app.loviedowpie.Updater
import kotlinx.coroutines.launch
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

/**
 * "Update available" with Download and Install. The APK is checked by [Updater]
 * before Install is enabled.
 *
 * DATA-FLOW: the download is the published APK from the relay, not encrypted, no user data.
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
                color = LdColors.colors.ink,
            )
            if (info.notes.isNotBlank()) {
                Text(
                    info.notes,
                    style = MaterialTheme.typography.bodySmall,
                    color = LdColors.colors.inkMuted,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (downloading) {
                LinearProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            error?.let { message ->
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = !downloading,
                    onClick = {
                        if (downloading) return@Button
                        downloading = true
                        error = null
                        progress = 0f
                        scope.launch {
                            var last = 0L
                            val file = try {
                                // DATA-FLOW: APK bytes and the published hash, from the relay, not encrypted, no user data.
                                Updater.downloadAndVerify(context) { value ->
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
                ) {
                    Text(stringResource(R.string.update_download))
                }
                OutlinedButton(
                    enabled = apk != null && !downloading,
                    onClick = {
                        val file = apk ?: return@OutlinedButton
                        val opened = Updater.install(context, file)
                        error = if (opened) null else context.getString(R.string.update_allow_install)
                    },
                ) {
                    Text(stringResource(R.string.update_install))
                }
            }
    }
}
