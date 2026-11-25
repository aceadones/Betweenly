package app.loviedowpie.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import app.loviedowpie.CameraCaps
import app.loviedowpie.R
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Black viewfinder chrome. The app theme stays on dialogs and the send button. */
internal object CameraChrome {
    val background = LdColors.cameraBg
    val content = LdColors.cameraContent
    val scrim = LdColors.cameraScrim
    val faint = LdColors.cameraFaint
}

@Composable
internal fun CameraOverlay(
    session: CameraSession,
    videoMode: Boolean,
    showSwitch: Boolean,
    qualityLabel: String?,
    elapsedMs: Long,
    failed: Boolean,
    silent: Boolean,
    onClose: () -> Unit,
    onFlip: () -> Unit,
    onShutter: () -> Unit,
    onFlash: () -> Unit,
    onPhotoMode: () -> Unit,
    onVideoMode: () -> Unit,
    onQuality: () -> Unit,
) {
    val controlsLocked = session.recording || session.stopping
    Box(
        Modifier
            .fillMaxSize()
            .zIndex(6f)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(CameraChrome.scrim)
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RoundIconButton(
                onClick = onClose,
                description = stringResource(R.string.camera_close),
                icon = Icons.Filled.Close,
            )
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                if (!qualityLabel.isNullOrEmpty()) {
                    TextButton(onClick = onQuality, enabled = !controlsLocked) {
                        Text(qualityLabel, color = if (controlsLocked) CameraChrome.faint else CameraChrome.content)
                    }
                }
            }
            if (session.hasFlash) {
                FlashButton(session, videoMode, enabled = !session.stopping, onClick = onFlash)
            } else {
                Spacer(Modifier.size(48.dp))
            }
        }
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(CameraChrome.scrim)
                .padding(top = 8.dp, bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (session.recording) {
                RecordingClock(elapsedMs)
                Spacer(Modifier.height(8.dp))
            }
            if (session.zoomMax > session.zoomMin + 0.05f) {
                Text(
                    stringResource(R.string.camera_zoom, session.zoomRatio),
                    color = CameraChrome.content,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            ExposureSlider(session)
            if (failed) {
                Text(
                    stringResource(R.string.camera_capture_failed),
                    color = LdColors.cameraError,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            if (videoMode) {
                Text(
                    stringResource(R.string.camera_video_hint),
                    color = CameraChrome.content,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
                if (silent) {
                    Text(
                        stringResource(R.string.camera_silent),
                        color = CameraChrome.content,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            ModeSwitch(
                video = videoMode,
                enabled = !controlsLocked,
                onPhoto = onPhotoMode,
                onVideo = onVideoMode,
            )
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Same slot in photo and video. Hidden only after a check proves one camera.
                if (showSwitch) {
                    RoundIconButton(
                        onClick = onFlip,
                        enabled = !controlsLocked,
                        description = stringResource(R.string.camera_switch),
                        icon = FlipIcon,
                    )
                } else {
                    Spacer(Modifier.size(48.dp))
                }
                Spacer(Modifier.width(28.dp))
                ShutterButton(
                    videoMode = videoMode,
                    recording = session.recording,
                    enabled = session.recording || (session.ready && !session.busy && !session.stopping),
                    onClick = onShutter,
                )
                Spacer(Modifier.width(28.dp))
                Spacer(Modifier.size(48.dp))
            }
        }
    }
}

@Composable
private fun ExposureSlider(session: CameraSession) {
    val min = session.exposureMin
    val max = session.exposureMax
    if (!session.exposureSupported || max <= min) return
    val value = session.exposureIndex.toFloat().coerceIn(min.toFloat(), max.toFloat())
    val ev = stringResource(R.string.camera_ev, formatEv(session.exposureIndex, session.exposureStep))
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            ev,
            color = CameraChrome.content,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.width(84.dp),
        )
        Slider(
            value = value,
            onValueChange = { session.changeExposure(it.roundToInt()) },
            valueRange = min.toFloat()..max.toFloat(),
            steps = (max - min - 1).coerceAtLeast(0),
            colors = SliderDefaults.colors(
                thumbColor = CameraChrome.content,
                activeTrackColor = CameraChrome.content,
                inactiveTrackColor = CameraChrome.faint,
            ),
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun RecordingClock(elapsedMs: Long) {
    val clock = formatClock(elapsedMs)
    val spoken = stringResource(R.string.camera_recording) + " " + clock
    Row(
        modifier = Modifier.semantics { contentDescription = spoken },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(LdColors.cameraError),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            clock,
            color = LdColors.cameraError,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.clearAndSetSemantics {},
        )
    }
}

@Composable
private fun ModeSwitch(video: Boolean, enabled: Boolean, onPhoto: () -> Unit, onVideo: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .border(1.dp, if (enabled) CameraChrome.content else CameraChrome.faint, RoundedCornerShape(50)),
    ) {
        ModeChip(stringResource(R.string.camera_photo), selected = !video, enabled = enabled, onClick = onPhoto)
        ModeChip(stringResource(R.string.camera_video), selected = video, enabled = enabled, onClick = onVideo)
    }
}

@Composable
private fun ModeChip(text: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Text(
        text,
        color = if (selected) CameraChrome.background else CameraChrome.content,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) CameraChrome.content else LdColors.clear)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 8.dp)
            .alpha(if (enabled || selected) 1f else 0.4f),
    )
}

@Composable
private fun ShutterButton(videoMode: Boolean, recording: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val description = stringResource(
        when {
            recording -> R.string.camera_stop_video
            videoMode -> R.string.camera_start_video
            else -> R.string.camera_take_photo
        },
    )
    Box(
        modifier = Modifier
            .size(78.dp)
            .alpha(if (enabled) 1f else 0.4f)
            .border(4.dp, CameraChrome.content, CircleShape)
            .padding(6.dp)
            .clip(CircleShape)
            .background(CameraChrome.content)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        if (recording || videoMode) {
            Box(
                Modifier
                    .size(if (recording) 64.dp else 22.dp)
                    .clip(CircleShape)
                    .background(LdColors.heart),
            )
        }
    }
}

@Composable
private fun FlashButton(session: CameraSession, videoMode: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val auto = !videoMode && session.flashMode == androidx.camera.core.ImageCapture.FLASH_MODE_AUTO
    val active = if (videoMode) session.torchOn else session.flashMode != androidx.camera.core.ImageCapture.FLASH_MODE_OFF
    val description = stringResource(
        when {
            videoMode && session.torchOn -> R.string.camera_light_on
            videoMode -> R.string.camera_light_off
            session.flashMode == androidx.camera.core.ImageCapture.FLASH_MODE_ON -> R.string.camera_flash_on
            auto -> R.string.camera_flash_auto
            else -> R.string.camera_flash_off
        },
    )
    RoundIconButton(
        onClick = onClick,
        enabled = enabled,
        description = description,
        icon = FlashIcon,
        tint = if (active) CameraChrome.content else CameraChrome.faint,
        badge = if (auto) stringResource(R.string.camera_flash_auto_mark) else null,
    )
}

@Composable
internal fun RoundIconButton(
    onClick: () -> Unit,
    description: String,
    icon: ImageVector,
    enabled: Boolean = true,
    tint: Color = CameraChrome.content,
    badge: String? = null,
) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(CameraChrome.scrim)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = tint)
        if (badge != null) {
            Text(
                badge,
                modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp).clearAndSetSemantics {},
                style = MaterialTheme.typography.labelSmall,
                color = CameraChrome.content,
            )
        }
    }
}

@Composable
internal fun QualityDialog(
    videoMode: Boolean,
    photos: List<CameraCaps.PhotoChoice>,
    videos: List<CameraCaps.VideoChoice>,
    photo: CameraCaps.PhotoChoice?,
    video: CameraCaps.VideoChoice?,
    onPhoto: (CameraCaps.PhotoChoice) -> Unit,
    onVideo: (CameraCaps.VideoChoice) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.ldPopupFrame(),
        containerColor = LdColors.colors.menu,
        tonalElevation = 0.dp,
        titleContentColor = LdColors.colors.ink,
        textContentColor = LdColors.colors.ink,
        title = { Text(stringResource(R.string.camera_quality)) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                if (videoMode) {
                    Text(
                        stringResource(R.string.camera_video_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    videos.forEach { choice ->
                        QualityRow(choice.label, selected = video != null && video.quality == choice.quality && video.fps == choice.fps) {
                            onVideo(choice)
                        }
                    }
                } else {
                    photos.forEach { choice ->
                        QualityRow(
                            choice.label,
                            selected = photo != null && photo.width == choice.width && photo.height == choice.height,
                        ) { onPhoto(choice) }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.camera_done)) }
        },
    )
}

@Composable
private fun QualityRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
internal fun FocusRing(point: Offset) {
    Box(
        Modifier
            .offset {
                IntOffset(
                    (point.x - 36.dp.toPx()).roundToInt(),
                    (point.y - 36.dp.toPx()).roundToInt(),
                )
            }
            .size(72.dp)
            .border(2.dp, CameraChrome.content, CircleShape),
    )
}

internal suspend fun PointerInputScope.previewGestures(
    onTap: (Offset) -> Unit,
    onZoom: (Float) -> Unit,
) {
    val slop = viewConfiguration.touchSlop
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        var zooming = false
        var moved = false
        var lastSpan = 0f
        val origin = down.position
        var tapAt = down.position
        while (true) {
            val event = awaitPointerEvent()
            val pressed = event.changes.filter { it.pressed }
            if (pressed.size >= 2) {
                zooming = true
                val span = pressed[0].position.distanceTo(pressed[1].position).coerceAtLeast(1f)
                if (lastSpan > 0f) {
                    val factor = span / lastSpan
                    if (factor.isFinite() && factor in 0.25f..4f) onZoom(factor)
                }
                lastSpan = span
                event.changes.forEach { it.consume() }
            } else if (pressed.isNotEmpty()) {
                tapAt = pressed[0].position
                if (pressed[0].position.distanceTo(origin) > slop) moved = true
            }
            if (pressed.isEmpty()) {
                if (!zooming && !moved) onTap(tapAt)
                break
            }
        }
    }
}

private fun Offset.distanceTo(other: Offset): Float {
    val dx = x - other.x
    val dy = y - other.y
    return sqrt(dx * dx + dy * dy)
}

internal fun formatEv(index: Int, step: Float): String {
    val ev = if (step.isFinite()) index * step else 0f
    return String.format(Locale.US, "%+.1f", ev)
}

internal fun formatClock(ms: Long): String {
    val total = (ms / 1000L).coerceAtLeast(0L)
    val minutes = total / 60L
    val seconds = total % 60L
    return String.format(Locale.US, "%d:%02d", minutes, seconds)
}

private val FlashIcon: ImageVector = ImageVector.Builder(
    name = "Flash",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply {
    path(fill = SolidColor(LdColors.cameraContent)) {
        moveTo(7f, 2f)
        verticalLineToRelative(11f)
        horizontalLineToRelative(3f)
        verticalLineToRelative(9f)
        lineToRelative(7f, -12f)
        horizontalLineToRelative(-4f)
        lineToRelative(4f, -8f)
        close()
    }
}.build()

private val FlipIcon: ImageVector = ImageVector.Builder(
    name = "FlipCamera",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply {
    path(fill = SolidColor(LdColors.cameraContent)) {
        moveTo(17.65f, 6.35f)
        curveTo(16.2f, 4.9f, 14.21f, 4f, 12f, 4f)
        curveToRelative(-4.42f, 0f, -7.99f, 3.58f, -7.99f, 8f)
        reflectiveCurveToRelative(3.57f, 8f, 7.99f, 8f)
        curveToRelative(3.73f, 0f, 6.84f, -2.55f, 7.73f, -6f)
        horizontalLineToRelative(-2.08f)
        curveToRelative(-0.82f, 2.33f, -3.04f, 4f, -5.65f, 4f)
        curveToRelative(-3.31f, 0f, -6f, -2.69f, -6f, -6f)
        reflectiveCurveToRelative(2.69f, -6f, 6f, -6f)
        curveToRelative(1.66f, 0f, 3.14f, 0.69f, 4.22f, 1.78f)
        lineTo(13f, 11f)
        horizontalLineToRelative(7f)
        verticalLineTo(4f)
        lineTo(17.65f, 6.35f)
        close()
    }
}.build()
