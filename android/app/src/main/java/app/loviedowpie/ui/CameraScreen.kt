package app.loviedowpie.ui

import android.net.Uri
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

/** STUB, replaced by the camera task. onResult gets a content/file Uri of the captured photo or video. */
@Composable
fun CameraScreen(onResult: (uri: Uri, isVideo: Boolean) -> Unit, onClose: () -> Unit) { Text("Camera") }
