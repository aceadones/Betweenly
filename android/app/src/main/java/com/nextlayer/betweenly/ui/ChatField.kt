@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.nextlayer.betweenly.ui

import android.content.Context
import android.net.Uri
import android.util.TypedValue
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.EditText
import androidx.compose.foundation.content.TransferableContent
import androidx.compose.foundation.content.contentReceiver
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.ContentInfoCompat
import androidx.core.view.ViewCompat
import androidx.core.view.inputmethod.EditorInfoCompat
import androidx.core.view.inputmethod.InputConnectionCompat
import androidx.core.widget.doAfterTextChanged
import java.io.ByteArrayOutputStream

/**
 * The message field. Gboard reads [EditorInfo.getContentMimeTypes] on this
 * view before it will insert a sticker or GIF. Compose's TextField does not
 * advertise those types, so this is a real EditText. [contentReceiver] covers
 * a drop onto the field. Both paths copy the bytes while the read grant is held.
 *
 * DATA-FLOW: an inserted image is a content Uri on this phone. The bytes are
 * copied here and the Uri is not kept. Nothing is uploaded. The chat preview
 * sends it later on the encrypted path.
 */
@Composable
fun ChatInputField(
    text: String,
    hint: String,
    onText: (String) -> Unit,
    onKeyboardSend: () -> Unit,
    onImage: (ByteArray, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val image = rememberUpdatedState(onImage)
    AndroidView(
        modifier = modifier
            .heightIn(min = 48.dp)
            .contentReceiver { incoming ->
                takeImages(context, incoming, image.value)
            },
        factory = { viewContext ->
            RichChatEdit(viewContext).apply {
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                background = null
                setTextColor(LdColors.onGlass.toArgb())
                setHintTextColor(LdColors.onGlassMuted.toArgb())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                gravity = Gravity.CENTER_VERTICAL or Gravity.START
                maxLines = 5
                isSingleLine = false
                imeOptions = EditorInfo.IME_ACTION_SEND
                setRawInputType(
                    EditorInfo.TYPE_CLASS_TEXT or EditorInfo.TYPE_TEXT_FLAG_CAP_SENTENCES,
                )
                setPadding(dp(12), dp(10), dp(4), dp(10))
                minimumHeight = dp(48)
                doAfterTextChanged { editable ->
                    val value = editable?.toString().orEmpty()
                    if (value != lastSent) {
                        lastSent = value
                        onTextChanged?.invoke(value)
                    }
                }
            }
        },
        update = { edit ->
            edit.onTextChanged = onText
            edit.onKeyboardSend = onKeyboardSend
            edit.onImage = image.value
            edit.hint = hint
            val shown = edit.text?.toString().orEmpty()
            if (shown != text) {
                edit.lastSent = text
                edit.setText(text)
                edit.setSelection(text.length)
            }
        },
    )
}

/**
 * Copies image items out of a clip while this callback still holds the read
 * grant. Returns null when at least one image was taken.
 */
private fun takeImages(
    context: Context,
    content: TransferableContent,
    onImage: (ByteArray, String) -> Unit,
): TransferableContent? {
    val clip = content.clipEntry.clipData
    val desc = clip.description
    var taken = false
    for (i in 0 until clip.itemCount) {
        val uri = clip.getItemAt(i).uri ?: continue
        val type = if (desc != null && i < desc.mimeTypeCount) {
            desc.getMimeType(i).orEmpty()
        } else {
            ""
        }
        if (type.isNotEmpty() && !type.startsWith("image/")) continue
        val bytes = readUri(context, uri) ?: continue
        if (!KeyboardInsert.accept(bytes)) {
            taken = true
            continue
        }
        onImage(bytes, type.ifBlank { "image/*" })
        taken = true
    }
    return if (taken) null else content
}

private fun readUri(context: Context, uri: Uri): ByteArray? {
    return try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            var total = 0
            while (total < ChatMedia.MAX_BYTES) {
                val n = input.read(buf, 0, minOf(buf.size, ChatMedia.MAX_BYTES - total))
                if (n < 0) break
                out.write(buf, 0, n)
                total += n
            }
            val extra = if (total >= ChatMedia.MAX_BYTES) input.read() else -1
            if (extra >= 0) null else out.toByteArray().takeIf { it.isNotEmpty() }
        }
    } catch (_: Exception) {
        null
    } catch (_: OutOfMemoryError) {
        null
    }
}

/** Drops a second delivery of the same insert from the EditText and the drop target. */
internal object KeyboardInsert {
    private var stamp = 0
    private var at = 0L

    fun accept(bytes: ByteArray): Boolean {
        var hash = bytes.size
        val n = minOf(bytes.size, 24)
        for (i in 0 until n) hash = hash * 31 + bytes[i].toInt()
        val now = System.currentTimeMillis()
        if (hash == stamp && now - at < 600L) return false
        stamp = hash
        at = now
        return true
    }
}

private fun android.view.View.dp(value: Int): Int =
    (value * resources.displayMetrics.density).toInt()

private class RichChatEdit(context: android.content.Context) : EditText(context) {
    var onTextChanged: ((String) -> Unit)? = null
    var onKeyboardSend: (() -> Unit)? = null
    var onImage: ((ByteArray, String) -> Unit)? = null
    var lastSent: String = ""

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                onKeyboardSend?.invoke()
                true
            } else {
                false
            }
        }
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val base = super.onCreateInputConnection(outAttrs) ?: return null
        outAttrs.contentMimeTypes = MIME
        EditorInfoCompat.setContentMimeTypes(outAttrs, MIME)
        ViewCompat.setOnReceiveContentListener(this, MIME) { _, payload ->
            onReceive(payload)
        }
        return InputConnectionCompat.createWrapper(this, base, outAttrs)
    }

    /**
     * Requests the read grant, copies the bytes, then releases it. The Uri is
     * not stored. Returning the non-image remainder tells Gboard the image was accepted.
     */
    private fun onReceive(payload: ContentInfoCompat): ContentInfoCompat? {
        val split = payload.partition { item -> item.uri != null }
        val images = split.first ?: return payload
        var taken = false
        try {
            val clip = images.clip
            val desc = clip.description
            for (i in 0 until clip.itemCount) {
                val uri = clip.getItemAt(i).uri ?: continue
                val type = if (desc != null && i < desc.mimeTypeCount) {
                    desc.getMimeType(i).orEmpty()
                } else {
                    ""
                }
                if (type.isNotEmpty() && !type.startsWith("image/")) continue
                val bytes = readUri(context, uri) ?: continue
                if (!KeyboardInsert.accept(bytes)) {
                    taken = true
                    continue
                }
                onImage?.invoke(bytes, type.ifBlank { "image/*" })
                taken = true
            }
        } catch (_: Exception) {
            // A failed read just means the image is not taken.
        }
        return if (taken) split.second else payload
    }

    private companion object {
        val MIME = arrayOf("image/png", "image/gif", "image/webp", "image/*")
    }
}
