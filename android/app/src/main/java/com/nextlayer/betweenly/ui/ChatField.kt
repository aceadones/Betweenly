package com.nextlayer.betweenly.ui

import android.net.Uri
import android.util.TypedValue
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.EditText
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.ViewCompat
import androidx.core.view.inputmethod.EditorInfoCompat
import androidx.core.view.inputmethod.InputConnectionCompat
import androidx.core.widget.doAfterTextChanged

/**
 * The message field. Gboard reads [EditorInfo.getContentMimeTypes] on this
 * view before it will insert a sticker or GIF. Compose's TextField does not
 * advertise those types, so this is a real EditText.
 *
 * DATA-FLOW: an inserted image is a content Uri on this phone. Nothing is
 * uploaded here. The chat preview sends it later on the encrypted path.
 */
@Composable
fun ChatInputField(
    text: String,
    hint: String,
    onText: (String) -> Unit,
    onKeyboardSend: () -> Unit,
    onImage: (Uri, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        modifier = modifier.heightIn(min = 48.dp),
        factory = { context ->
            RichChatEdit(context).apply {
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                background = null
                setTextColor(LdColors.onGlass.toArgb())
                setHintTextColor(LdColors.onGlassMuted.toArgb())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                gravity = Gravity.CENTER_VERTICAL
                maxLines = 5
                isSingleLine = false
                imeOptions = EditorInfo.IME_ACTION_SEND
                setRawInputType(
                    EditorInfo.TYPE_CLASS_TEXT or EditorInfo.TYPE_TEXT_FLAG_CAP_SENTENCES,
                )
                setPadding(dp(8), dp(8), dp(8), dp(8))
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
            edit.onImage = onImage
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

private fun android.view.View.dp(value: Int): Int =
    (value * resources.displayMetrics.density).toInt()

private class RichChatEdit(context: android.content.Context) : EditText(context) {
    var onTextChanged: ((String) -> Unit)? = null
    var onKeyboardSend: (() -> Unit)? = null
    var onImage: ((Uri, String) -> Unit)? = null
    var lastSent: String = ""

    init {
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
            val split = payload.partition { item -> item.uri != null }
            val images = split.first
            if (images != null) {
                val clip = images.clip
                for (i in 0 until clip.itemCount) {
                    val uri = clip.getItemAt(i).uri ?: continue
                    val type = clip.description?.getMimeType(i).orEmpty().ifBlank { "image/*" }
                    onImage?.invoke(uri, type)
                }
            }
            split.second
        }
        return InputConnectionCompat.createWrapper(this, base, outAttrs)
    }

    private companion object {
        val MIME = arrayOf("image/png", "image/gif", "image/webp", "image/*")
    }
}
