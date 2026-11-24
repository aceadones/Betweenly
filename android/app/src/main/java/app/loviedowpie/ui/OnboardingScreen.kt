package app.loviedowpie.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.loviedowpie.LocationSharing
import app.loviedowpie.R
import app.loviedowpie.Store

/**
 * First-run screen. Explains each permission in plain words, asks for a name
 * and an optional photo, then hands off to pairing. Sharing stays off.
 *
 * DATA-FLOW: nothing here is sent. The name and photo are saved on this phone.
 * Pairing sends the profile later, encrypted.
 */
@Composable
fun OnboardingScreen(onDone: () -> Unit, onPrivacy: () -> Unit) {
    val context = LocalContext.current
    var name by rememberSaveable { mutableStateOf(Store.myName(context)) }
    var tick by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) tick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        tick++
    }
    val foreground = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        tick++
    }
    val background = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        tick++
    }
    val settings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        tick++
    }

    val notificationsOk = remember(tick) {
        Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }
    val locationOk = remember(tick) { LocationSharing.hasLocationPermission(context) }
    val backgroundOk = remember(tick) { LocationSharing.hasBackgroundLocation(context) }
    val batteryOk = remember(tick) { LocationSharing.batteryOptimizationsIgnored(context) }
    val canContinue = name.trim().isNotEmpty()

    fun openSettings() {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null),
        )
        try {
            settings.launch(intent)
        } catch (_: Exception) {
            // This device has no app-details screen. The status line stays as it is.
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.onboarding_title), style = MaterialTheme.typography.headlineMedium)
            Text(stringResource(R.string.onboarding_intro), style = MaterialTheme.typography.bodyLarge)
            OutlinedTextField(
                value = name,
                onValueChange = { value ->
                    if (value.length <= 80) name = value.replace("\n", " ")
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.name_label)) },
                supportingText = { Text(stringResource(R.string.name_help)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
            )
            Text(
                stringResource(R.string.photo_help),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            PhotoPicker(name = name.ifBlank { stringResource(R.string.partner_fallback) }) { jpeg ->
                // DATA-FLOW: none. The photo stays on this phone until pairing sends it encrypted.
                Store.setMyAvatar(context, jpeg)
            }
            PermissionRow(
                title = stringResource(R.string.perm_notifications_title),
                body = stringResource(R.string.perm_notifications_body),
                allowed = notificationsOk,
                onAllow = if (Build.VERSION.SDK_INT >= 33) {
                    {
                        try {
                            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } catch (_: Exception) {
                            openSettings()
                        }
                    }
                } else {
                    null
                },
                onSettings = ::openSettings,
            )
            PermissionRow(
                title = stringResource(R.string.perm_location_title),
                body = stringResource(R.string.perm_location_body),
                allowed = locationOk,
                onAllow = {
                    try {
                        foreground.launch(
                            arrayOf(
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION,
                            ),
                        )
                    } catch (_: Exception) {
                        openSettings()
                    }
                },
                onSettings = ::openSettings,
            )
            if (locationOk && Build.VERSION.SDK_INT >= 29) {
                PermissionRow(
                    title = stringResource(R.string.perm_background_title),
                    body = stringResource(R.string.perm_background_body),
                    allowed = backgroundOk,
                    onAllow = {
                        try {
                            background.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                        } catch (_: Exception) {
                            openSettings()
                        }
                    },
                    onSettings = ::openSettings,
                )
            }
            PermissionRow(
                title = stringResource(R.string.perm_battery_title),
                body = stringResource(R.string.perm_battery_body),
                allowed = batteryOk,
                onAllow = { LocationSharing.requestUnrestrictedBattery(context) },
                onSettings = ::openSettings,
            )
            Text(
                stringResource(R.string.privacy_see),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = stringResource(R.string.privacy_link),
                color = MaterialTheme.colorScheme.primary,
                textDecoration = TextDecoration.Underline,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.clickable(onClick = onPrivacy),
            )
            Text(
                stringResource(R.string.onboarding_footnote),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = {
                    Store.setMyName(context, name.trim())
                    UiFlags.setOnboardingDone(context)
                    onDone()
                },
                enabled = canContinue,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.onboarding_continue))
            }
        }
    }
}

@Composable
private fun PermissionRow(
    title: String,
    body: String,
    allowed: Boolean,
    onAllow: (() -> Unit)?,
    onSettings: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            stringResource(if (allowed) R.string.perm_allowed else R.string.perm_not_allowed),
            style = MaterialTheme.typography.labelLarge,
            color = if (allowed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!allowed && onAllow != null) {
            Button(onClick = onAllow, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.perm_allow))
            }
        }
        if (!allowed) {
            TextButton(onClick = onSettings) {
                Text(stringResource(R.string.perm_settings))
            }
        }
    }
}
