package com.nextlayer.betweenly

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import androidx.core.app.Person
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat

/**
 * Makes the chat a real Android "conversation": a long-lived shortcut for the
 * partner plus a [Person] with a round avatar. With these, a long-press on a
 * message notice offers Priority, and the shade shows the partner's picture.
 *
 * DATA-FLOW: nothing leaves the phone. The name and avatar come from [Store]
 * and go only to the system shortcut service on this phone.
 */
object ConversationShortcut {
    /** Shortcut id, also the notification's shortcut id. */
    const val ID = "partner"

    /** The avatar cropped to a circle, or null when there is no picture. */
    fun roundAvatar(context: Context): Bitmap? {
        val bytes = Store.partnerAvatar(context) ?: return null
        val src = try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (_: Exception) {
            null
        } ?: return null
        val size = minOf(src.width, src.height)
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(src, (size - src.width) / 2f, (size - src.height) / 2f, paint)
        return out
    }

    /** The partner as a notification [Person]. */
    fun person(context: Context): Person {
        val name = Store.partnerName(context).ifBlank { context.getString(R.string.app_name) }
        val builder = Person.Builder().setName(name).setKey(ID).setImportant(true)
        roundAvatar(context)?.let { builder.setIcon(IconCompat.createWithBitmap(it)) }
        return builder.build()
    }

    /** Publishes or refreshes the conversation shortcut. Safe to call often. */
    fun publish(context: Context) {
        val app = context.applicationContext
        try {
            val open = Intent(app, MainActivity::class.java)
                .setAction(Intent.ACTION_VIEW)
                .putExtra(MainActivity.EXTRA_OPEN, "chat")
            val name = Store.partnerName(app).ifBlank { app.getString(R.string.app_name) }
            val builder = ShortcutInfoCompat.Builder(app, ID)
                .setShortLabel(name)
                .setLongLived(true)
                .setIntent(open)
                .setPerson(person(app))
                .setCategories(setOf("com.nextlayer.betweenly.category.CHAT"))
            roundAvatar(app)?.let { builder.setIcon(IconCompat.createWithAdaptiveBitmap(it)) }
                ?: builder.setIcon(IconCompat.createWithResource(app, R.mipmap.ic_launcher))
            ShortcutManagerCompat.pushDynamicShortcut(app, builder.build())
        } catch (_: Exception) {
            // A launcher without shortcut support. The notice still posts.
        }
    }

    /** Removes the shortcut when the pair is removed. */
    fun remove(context: Context) {
        try {
            ShortcutManagerCompat.removeAllDynamicShortcuts(context.applicationContext)
        } catch (_: Exception) {
        }
    }
}
