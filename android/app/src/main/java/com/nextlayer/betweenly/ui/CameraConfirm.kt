package com.nextlayer.betweenly.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.VideoView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.nextlayer.betweenly.R
import com.nextlayer.betweenly.ui.icons.Lu
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Photo, or a looping video, with Retake and Send.
 * The video sits in its own area so the buttons are not punched out by the surface.
 */
@Composable
internal fun CameraConfirm(
    path: String,
    isVideo: Boolean,
    sendEnabled: Boolean,
    onRetake: () -> Unit,
    onSend: () -> Unit,
    onClose: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(CameraChrome.background)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            RoundIconButton(
                onClick = onClose,
                description = stringResource(R.string.camera_close),
                icon = Lu.X,
            )
        }
        Box(
            Modifier.weight(1f).fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            if (isVideo) {
                LoopingVideo(path)
            } else {
                StillPreview(path)
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LdButton(
                text = stringResource(R.string.camera_retake),
                onClick = onRetake,
                modifier = Modifier.weight(1f),
                style = LdButtonStyle.Secondary,
                enabled = sendEnabled,
            )
            Spacer(Modifier.width(12.dp))
            LdButton(
                text = stringResource(R.string.camera_send),
                onClick = onSend,
                modifier = Modifier.weight(1f),
                enabled = sendEnabled,
            )
        }
    }
}

@Composable
private fun StillPreview(path: String) {
    var bitmap by remember(path) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(path) { mutableStateOf(false) }
    LaunchedEffect(path) {
        val decoded = withContext(Dispatchers.IO) { decodeStill(path) }
        if (decoded == null) failed = true else bitmap = decoded
    }
    val shown = bitmap
    when {
        shown != null -> androidx.compose.foundation.Image(
            bitmap = shown.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit,
        )
        failed -> Text(
            stringResource(R.string.camera_capture_failed),
            color = CameraChrome.content,
            style = MaterialTheme.typography.bodyLarge,
        )
        else -> CircularProgressIndicator(color = CameraChrome.content)
    }
}

@Composable
private fun LoopingVideo(path: String) {
    val context = LocalContext.current
    var failed by remember(path) { mutableStateOf(false) }
    val container = remember(path) {
        buildPlayer(context, path) { failed = true }
    }
    DisposableEffect(container) {
        onDispose {
            val video = container.getChildAt(0) as? VideoView
            try {
                video?.stopPlayback()
            } catch (_: Exception) {
                // The surface is already gone.
            }
        }
    }
    if (failed) {
        Text(
            stringResource(R.string.camera_preview_failed),
            color = CameraChrome.content,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(24.dp),
        )
    }
    AndroidView(factory = { container }, modifier = Modifier.fillMaxSize())
}

private fun buildPlayer(context: android.content.Context, path: String, onError: () -> Unit): FrameLayout {
    val container = FrameLayout(context)
    container.setBackgroundColor(LdColors.cameraBg.toArgb())
    val video = VideoView(context)
    container.addView(
        video,
        FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
            Gravity.CENTER,
        ),
    )
    video.setOnPreparedListener { player ->
        player.isLooping = true
        video.start()
    }
    video.setOnCompletionListener { video.start() }
    video.setOnErrorListener { _, _, _ ->
        video.post { onError() }
        true
    }
    try {
        video.setVideoPath(path)
    } catch (_: Exception) {
        video.post { onError() }
    }
    return container
}

private fun decodeStill(path: String): Bitmap? {
    return try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while ((bounds.outWidth / sample > 2048 || bounds.outHeight / sample > 2048) && sample < 64) {
            sample *= 2
        }
        BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
    } catch (_: Throwable) {
        null
    }
}
