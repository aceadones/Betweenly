package com.nextlayer.betweenly.ui

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import com.nextlayer.betweenly.DrawStore
import com.nextlayer.betweenly.MainActivity
import com.nextlayer.betweenly.R
import kotlin.math.min

/**
 * Home-screen widget of the shared page: paper plus both layers, fitted to the
 * widget's current size. Before a shared page exists, it falls back to the
 * partner's latest drawing, then this phone's newest save, then a short hint.
 * RemoteViews only, no Glance.
 *
 * Tapping opens the Drawing Studio (`open` = `draw`).
 * The provider period is 30 minutes. [updateAll] runs again when either layer changes.
 * Nothing here polls.
 *
 * DATA-FLOW: none. The bitmap is read from app-private storage.
 */
class DrawingWidget : AppWidgetProvider() {
    override fun onEnabled(context: Context) {
        updateAll(context)
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        updateAll(context)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle,
    ) {
        updateAll(context)
    }

    companion object {
        private const val REQUEST_OPEN = 21

        /** Pixel budget for the bitmap handed to the launcher. About 1 MB of ARGB. */
        private const val MAX_BITMAP_BYTES = 1_000_000

        /**
         * Bitmaps handed to the launcher on the last [updateAll]. RemoteViews may share
         * their pixels until the next update, so they are recycled only then.
         */
        private val held = ArrayList<Bitmap>(4)
        private val gate = Any()

        fun updateAll(context: Context) {
            synchronized(gate) {
                try {
                    val app = context.applicationContext
                    val manager = AppWidgetManager.getInstance(app)
                    val ids = manager.getAppWidgetIds(ComponentName(app, DrawingWidget::class.java))
                    if (ids.isEmpty()) {
                        for (bitmap in held) if (!bitmap.isRecycled) bitmap.recycle()
                        held.clear()
                        return
                    }
                    val previous = held.toList()
                    held.clear()
                    try {
                        for (id in ids) {
                            val options = try {
                                manager.getAppWidgetOptions(id)
                            } catch (_: Exception) {
                                Bundle()
                            }
                            push(app, manager, id, options)
                        }
                    } finally {
                        if (held.isEmpty()) {
                            held.addAll(previous)
                        } else {
                            for (bitmap in previous) {
                                if (bitmap !in held && !bitmap.isRecycled) bitmap.recycle()
                            }
                        }
                    }
                } catch (_: Exception) {
                    // The launcher was busy. The next drawing, or the 30-minute tick, tries again.
                }
            }
        }

        private fun push(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
            val views = RemoteViews(context.packageName, R.layout.widget_drawing)
            val (width, height, radius) = pixelSize(context, options)
            val bitmap = try {
                loadRounded(context, width, height, radius)
            } catch (_: Exception) {
                null
            } catch (_: OutOfMemoryError) {
                null
            }
            var handed = false
            try {
                if (bitmap != null) {
                    views.setImageViewBitmap(R.id.widget_drawing, bitmap)
                    views.setViewVisibility(R.id.widget_drawing, View.VISIBLE)
                    views.setViewVisibility(R.id.widget_drawing_empty, View.GONE)
                } else {
                    views.setViewVisibility(R.id.widget_drawing, View.GONE)
                    views.setViewVisibility(R.id.widget_drawing_empty, View.VISIBLE)
                }
                views.setOnClickPendingIntent(R.id.widget_drawing_root, openActivity(context))
                manager.updateAppWidget(id, views)
                handed = bitmap != null
                if (handed && bitmap != null) held.add(bitmap)
                try {
                    manager.notifyAppWidgetViewDataChanged(id, R.id.widget_drawing)
                } catch (_: Exception) {
                    // This view is not a collection. The updateAppWidget call above is the one that paints.
                }
            } catch (_: Exception) {
                // Binder limit or a busy launcher. Leave the previous frame in place.
            } finally {
                if (!handed && bitmap != null && !bitmap.isRecycled) bitmap.recycle()
            }
        }

        private fun pixelSize(context: Context, options: Bundle): Triple<Int, Int, Float> {
            val density = context.resources.displayMetrics.density.coerceAtLeast(1f)
            fun bound(minKey: String, maxKey: String): Int {
                val minDp = options.getInt(minKey, 0).takeIf { it > 0 } ?: 110
                val maxDp = options.getInt(maxKey, 0).takeIf { it > 0 } ?: minDp
                return maxOf(minDp, maxDp).coerceIn(110, 500)
            }
            // The image sits inside a 10dp margin, so the bitmap matches that box.
            val wDp = (bound(
                AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH,
                AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH,
            ) - 20).coerceAtLeast(90)
            val hDp = (bound(
                AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT,
                AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT,
            ) - 20).coerceAtLeast(90)
            var width = (wDp * density).toInt().coerceAtLeast(48)
            var height = (hDp * density).toInt().coerceAtLeast(48)
            while (width.toLong() * height.toLong() * 4L > MAX_BITMAP_BYTES && (width > 48 || height > 48)) {
                width = (width * 9 / 10).coerceAtLeast(48)
                height = (height * 9 / 10).coerceAtLeast(48)
            }
            val fullW = (wDp * density).coerceAtLeast(1f)
            val radius = 22f * density * (width / fullW)
            return Triple(width, height, radius)
        }

        private fun loadRounded(context: Context, width: Int, height: Int, radius: Float): Bitmap? {
            val edge = min(width, height).coerceAtLeast(1)
            val decoded = DrawStore.loadPreview(context, edge) ?: return null
            if (decoded.width <= 0 || decoded.height <= 0) {
                if (!decoded.isRecycled) decoded.recycle()
                return null
            }
            var out: Bitmap? = null
            return try {
                val created = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                out = created
                val canvas = Canvas(created)
                val scale = min(width.toFloat() / decoded.width, height.toFloat() / decoded.height)
                val dw = decoded.width * scale
                val dh = decoded.height * scale
                val left = (width - dw) / 2f
                val top = (height - dh) / 2f
                val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
                canvas.drawBitmap(decoded, null, RectF(left, top, left + dw, top + dh), paint)
                round(canvas, width, height, radius)
                created
            } catch (_: Exception) {
                out?.recycle()
                null
            } catch (_: OutOfMemoryError) {
                out?.recycle()
                null
            } finally {
                if (!decoded.isRecycled) decoded.recycle()
            }
        }

        /** Keeps the pixels inside a rounded rect and clears the square corners. */
        private fun round(canvas: Canvas, width: Int, height: Int, radius: Float) {
            val mask = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            try {
                val maskCanvas = Canvas(mask)
                val fill = Paint(Paint.ANTI_ALIAS_FLAG)
                maskCanvas.drawRoundRect(
                    RectF(0f, 0f, width.toFloat(), height.toFloat()),
                    radius,
                    radius,
                    fill,
                )
                val xfer = Paint(Paint.ANTI_ALIAS_FLAG)
                xfer.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
                canvas.drawBitmap(mask, 0f, 0f, xfer)
            } finally {
                mask.recycle()
            }
        }

        private fun openActivity(context: Context): PendingIntent {
            val intent = Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_OPEN, "draw")
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP,
                )
            return PendingIntent.getActivity(
                context,
                REQUEST_OPEN,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
    }
}
