package app.loviedowpie.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import app.loviedowpie.ChatStore
import app.loviedowpie.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Full-screen photo with pinch zoom. Back or the close button dismisses it. */
@Composable
fun PhotoViewer(id: String, onClose: () -> Unit) {
    val context = LocalContext.current
    var bitmap by remember(id) { mutableStateOf<Bitmap?>(null) }
    var scale by remember(id) { mutableFloatStateOf(1f) }
    var panX by remember(id) { mutableFloatStateOf(0f) }
    var panY by remember(id) { mutableFloatStateOf(0f) }
    LaunchedEffect(id) {
        bitmap = withContext(Dispatchers.IO) {
            val bytes = ChatStore.readAttachment(context, id) ?: return@withContext null
            ChatMedia.decodeForDisplay(bytes, ChatMedia.MAX_EDGE)
        }
    }
    BackHandler(onBack = onClose)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ChatTheme.viewerScrim)
            .pointerInput(id) {
                detectTransformGestures { _, pan, zoom, _ ->
                    val next = (scale * zoom).coerceIn(1f, 6f)
                    if (next <= 1f) {
                        scale = 1f
                        panX = 0f
                        panY = 0f
                    } else {
                        scale = next
                        panX += pan.x
                        panY += pan.y
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        val shown = bitmap
        if (shown == null) {
            CircularProgressIndicator(color = ChatTheme.viewerContent)
        } else {
            Image(
                bitmap = shown.asImageBitmap(),
                contentDescription = stringResource(R.string.chat_photo_cd),
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = panX
                        translationY = panY
                    },
            )
        }
        IconButton(
            onClick = onClose,
            modifier = Modifier.align(Alignment.TopStart).statusBarsPadding(),
        ) {
            Icon(
                Icons.Filled.Close,
                contentDescription = stringResource(R.string.chat_close),
                tint = ChatTheme.viewerContent,
            )
        }
    }
}
