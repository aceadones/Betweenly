package com.nextlayer.betweenly.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nextlayer.betweenly.NotifEvent
import com.nextlayer.betweenly.NotifMode
import com.nextlayer.betweenly.R
import com.nextlayer.betweenly.Store
import com.nextlayer.betweenly.clipNotifDraft
import com.nextlayer.betweenly.notificationText
import com.nextlayer.betweenly.ui.icons.Lu
import kotlinx.coroutines.launch

/**
 * Profile → Notifications. The message control is On / Off / Custom.
 * The drawing switch stays. Alarm wording is a separate disguise on this phone.
 *
 * DATA-FLOW: nothing leaves the phone. The preview is built locally.
 */
@Composable
fun NotificationSettings(snackbar: SnackbarHostState, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val revision by Store.changes.collectAsState()
    val mode = remember(revision) { Store.notifMode(context) }
    val savedCustom = remember(revision) { Store.notifCustom(context) }
    val savedAlarm = remember(revision) { Store.notifAlarmText(context) }
    val notifyDrawings = remember(revision) { Store.notifyDrawings(context) }
    var customDraft by rememberSaveable { mutableStateOf(savedCustom) }
    var alarmDraft by rememberSaveable { mutableStateOf(savedAlarm) }
    val sample = stringResource(R.string.notif_preview_sample)
    val messagePreview = notificationText(
        context,
        event = NotifEvent.Message,
        detail = sample,
        mode = mode,
        custom = customDraft,
    )
    val alarmPreview = notificationText(
        context,
        event = NotifEvent.CallRing,
        alarm = alarmDraft,
    )
    val customClean = clipNotifDraft(customDraft).trim()
    val alarmClean = clipNotifDraft(alarmDraft).trim()
    val customDirty = customClean != savedCustom
    val alarmDirty = alarmClean != savedAlarm
    val help = when (mode) {
        NotifMode.On -> stringResource(R.string.notif_mode_on_help)
        NotifMode.Off -> stringResource(R.string.notif_mode_off_help)
        NotifMode.Custom -> stringResource(R.string.notif_mode_custom_help)
    }

    fun saveCustom() {
        if (!customDirty) return
        customDraft = customClean
        Store.setNotifCustom(context, customClean)
        scope.launch { snackbar.showSnackbar(context.getString(R.string.notif_saved)) }
    }

    fun saveAlarm() {
        if (!alarmDirty) return
        alarmDraft = alarmClean
        Store.setNotifAlarmText(context, alarmClean)
        scope.launch { snackbar.showSnackbar(context.getString(R.string.notif_saved)) }
    }

    GlassGroup(modifier) {
        SectionLabel(Lu.Bell, stringResource(R.string.notif_section))
        Text(
            stringResource(R.string.notif_mode_title),
            modifier = Modifier.padding(top = 6.dp),
            style = MaterialTheme.typography.bodyLarge,
            color = LdColors.colors.ink,
        )
        ModePicker(mode) { next ->
            if (next != mode) Store.setNotifMode(context, next)
        }
        Text(
            help,
            style = MaterialTheme.typography.bodySmall,
            color = LdColors.colors.inkMuted,
        )
        NoticePreview(stringResource(R.string.notif_preview), messagePreview.title, messagePreview.text)
        AnimatedVisibility(
            visible = mode == NotifMode.Custom,
            enter = fadeIn(Motion.tween(160, Motion.easeOut)) + scaleIn(
                animationSpec = Motion.tween(160, Motion.easeOut),
                initialScale = 0.96f,
                transformOrigin = TransformOrigin(0.5f, 0f),
            ),
            exit = fadeOut(Motion.tween(110, Motion.easeOut)) + scaleOut(
                animationSpec = Motion.tween(110, Motion.easeOut),
                targetScale = 0.96f,
                transformOrigin = TransformOrigin(0.5f, 0f),
            ),
        ) {
            Column(Modifier.padding(top = 6.dp)) {
                Text(
                    stringResource(R.string.notif_custom_label),
                    style = MaterialTheme.typography.bodySmall,
                    color = LdColors.colors.inkMuted,
                )
                DisguiseField(
                    value = customDraft,
                    onValue = { customDraft = clipNotifDraft(it) },
                    placeholder = stringResource(R.string.notif_custom_hint),
                    onDone = { saveCustom() },
                    modifier = Modifier.padding(top = 6.dp),
                )
                SecondaryPill(
                    text = stringResource(R.string.notif_save),
                    onClick = { saveCustom() },
                    enabled = customDirty,
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .fillMaxWidth(),
                )
            }
        }
        Text(
            stringResource(R.string.notif_alarm_label),
            modifier = Modifier.padding(top = 12.dp),
            style = MaterialTheme.typography.bodyLarge,
            color = LdColors.colors.ink,
        )
        Text(
            stringResource(R.string.notif_alarm_help),
            style = MaterialTheme.typography.bodySmall,
            color = LdColors.colors.inkMuted,
        )
        DisguiseField(
            value = alarmDraft,
            onValue = { alarmDraft = clipNotifDraft(it) },
            placeholder = stringResource(R.string.notif_alarm_title),
            onDone = { saveAlarm() },
        )
        NoticePreview(stringResource(R.string.notif_preview), alarmPreview.title, alarmPreview.text)
        SecondaryPill(
            text = stringResource(R.string.notif_save),
            onClick = { saveAlarm() },
            enabled = alarmDirty,
            modifier = Modifier.fillMaxWidth(),
        )
        ToggleRow(
            title = stringResource(R.string.notif_drawings),
            help = stringResource(R.string.notif_drawings_help_v2),
            checked = notifyDrawings,
            enabled = true,
            icon = Lu.Image,
            onCheckedChange = { Store.setNotifyDrawings(context, it) },
        )
        SecondaryPill(
            text = stringResource(R.string.notif_system),
            onClick = {
                if (!openNotificationSettings(context)) {
                    scope.launch {
                        snackbar.showSnackbar(context.getString(R.string.notif_system_failed))
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ModePicker(mode: NotifMode, onSelect: (NotifMode) -> Unit) {
    val options = listOf(
        NotifMode.On to stringResource(R.string.notif_mode_on),
        NotifMode.Off to stringResource(R.string.notif_mode_off),
        NotifMode.Custom to stringResource(R.string.notif_mode_custom),
    )
    val current = options.indexOfFirst { it.first == mode }.let { if (it < 0) 1 else it }
    var innerPx by remember { mutableIntStateOf(0) }
    var placed by remember { mutableStateOf(false) }
    val shift by animateFloatAsState(
        targetValue = if (innerPx <= 0) 0f else innerPx / options.size.toFloat() * current,
        animationSpec = if (placed) Motion.spring<Float>() else snap<Float>(),
        label = "mode",
    )
    LaunchedEffect(innerPx) {
        if (innerPx > 0) placed = true
    }
    val track = RoundedCornerShape(LdRadius.inner(LdRadius.card, 16.dp))
    val thumb = RoundedCornerShape(LdRadius.inner(LdRadius.inner(LdRadius.card, 16.dp), 4.dp))
    Box(
        Modifier
            .padding(top = 8.dp)
            .fillMaxWidth()
            .height(56.dp)
            .clip(track)
            .background(LdColors.colors.ink.copy(alpha = 0.06f))
            .border(1.dp, LdColors.colors.ink.copy(alpha = 0.12f), track)
            .padding(4.dp)
            .onSizeChanged { innerPx = it.width },
    ) {
        if (innerPx > 0) {
            Box(
                Modifier
                    .graphicsLayer { translationX = shift }
                    .fillMaxHeight()
                    .fillMaxWidth(1f / options.size)
                    .clip(thumb)
                    .background(LdColors.primary),
            )
        }
        Row(Modifier.fillMaxSize()) {
            options.forEachIndexed { index, (value, label) ->
                val interaction = remember(value) { MutableInteractionSource() }
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .pressScale(interaction)
                        .clickable(
                            interactionSource = interaction,
                            indication = LocalIndication.current,
                            role = Role.RadioButton,
                            onClick = { onSelect(value) },
                        )
                        .semantics { selected = index == current },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        color = if (index == current) LdColors.white else LdColors.colors.ink,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun DisguiseField(
    value: String,
    onValue: (String) -> Unit,
    placeholder: String,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(LdRadius.inner(LdRadius.card, 16.dp))
    val style = MaterialTheme.typography.bodyLarge.copy(color = LdColors.colors.ink)
    Box(
        modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(shape)
            .background(LdColors.colors.ink.copy(alpha = 0.06f))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.isEmpty()) {
            Text(
                placeholder,
                style = MaterialTheme.typography.bodyLarge,
                color = LdColors.colors.inkMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onValue,
            modifier = Modifier.fillMaxWidth(),
            textStyle = style,
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onDone() }),
            cursorBrush = SolidColor(LdColors.primary),
        )
    }
}

@Composable
private fun NoticePreview(label: String, title: String, text: String) {
    val shape = RoundedCornerShape(LdRadius.inner(LdRadius.card, 16.dp))
    Column(
        Modifier
            .padding(top = 8.dp)
            .fillMaxWidth()
            .clip(shape)
            .background(LdColors.colors.ink.copy(alpha = 0.06f))
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = LdColors.colors.inkMuted,
            maxLines = 1,
        )
        Text(
            title,
            modifier = Modifier.padding(top = 2.dp),
            style = MaterialTheme.typography.titleSmall,
            color = LdColors.colors.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (text.isNotEmpty()) {
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                color = LdColors.colors.inkMuted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Opens this app's system notification settings. False when neither screen can open. */
private fun openNotificationSettings(context: Context): Boolean {
    val pkg = context.packageName
    val primary = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, pkg)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(primary)
        return true
    } catch (_: Exception) {
        // This Android build has no notification-settings screen. The app page is next.
    }
    return try {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:$pkg"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        true
    } catch (_: Exception) {
        false
    }
}
