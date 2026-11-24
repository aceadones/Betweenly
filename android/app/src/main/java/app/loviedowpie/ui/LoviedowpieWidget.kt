package app.loviedowpie.ui

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import app.loviedowpie.AppScope
import app.loviedowpie.MainActivity
import app.loviedowpie.R
import app.loviedowpie.Store
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * 2×2 home-screen widget: track, ring, live, settings. RemoteViews only.
 * Clicks go to [MainActivity] or [WidgetActionReceiver]. Refreshed when
 * [Store] changes and on the 30-minute system period.
 */
class LoviedowpieWidget : AppWidgetProvider() {
    override fun onEnabled(context: Context) {
        WidgetRefresh.ensure(context)
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        WidgetRefresh.ensure(context)
        updateAll(context)
    }

    companion object {
        fun updateAll(context: Context) {
            try {
                val app = context.applicationContext
                val manager = AppWidgetManager.getInstance(app)
                val ids = manager.getAppWidgetIds(ComponentName(app, LoviedowpieWidget::class.java))
                if (ids.isEmpty()) return
                val views = render(app)
                for (id in ids) manager.updateAppWidget(id, views)
            } catch (_: Exception) {
                // The launcher was busy. The next store change tries again.
            }
        }

        private fun render(context: Context): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget)
            val live = Store.liveOn(context)
            views.setImageViewResource(R.id.widget_track, R.drawable.ic_widget_location)
            views.setImageViewResource(R.id.widget_ring, R.drawable.ic_widget_bell)
            views.setImageViewResource(R.id.widget_live, R.drawable.ic_widget_live)
            views.setImageViewResource(R.id.widget_settings, R.drawable.ic_widget_settings)
            views.setInt(R.id.widget_track, "setBackgroundResource", R.drawable.widget_tile)
            views.setInt(R.id.widget_ring, "setBackgroundResource", R.drawable.widget_tile)
            views.setInt(
                R.id.widget_live,
                "setBackgroundResource",
                if (live) R.drawable.widget_tile_active else R.drawable.widget_tile,
            )
            views.setInt(R.id.widget_settings, "setBackgroundResource", R.drawable.widget_tile)
            views.setContentDescription(R.id.widget_track, context.getString(R.string.widget_track))
            views.setContentDescription(R.id.widget_ring, context.getString(R.string.widget_ring))
            views.setContentDescription(
                R.id.widget_live,
                context.getString(if (live) R.string.widget_live_on else R.string.widget_live_off),
            )
            views.setContentDescription(R.id.widget_settings, context.getString(R.string.widget_settings))
            views.setOnClickPendingIntent(R.id.widget_track, openApp(context, "track", 1))
            views.setOnClickPendingIntent(R.id.widget_ring, broadcast(context, WidgetActionReceiver.ACTION_RING, 2))
            views.setOnClickPendingIntent(R.id.widget_live, broadcast(context, WidgetActionReceiver.ACTION_LIVE, 3))
            views.setOnClickPendingIntent(R.id.widget_settings, openApp(context, "settings", 4))
            return views
        }

        private fun openApp(context: Context, open: String, request: Int): PendingIntent {
            val intent = Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_OPEN, open)
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP,
                )
            return PendingIntent.getActivity(context, request, intent, FLAGS)
        }

        private fun broadcast(context: Context, action: String, request: Int): PendingIntent {
            val intent = Intent(context, WidgetActionReceiver::class.java).setAction(action)
            return PendingIntent.getBroadcast(context, request, intent, FLAGS)
        }

        private const val FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    }
}

/**
 * Keeps the widget in step with [Store] while this process is alive.
 * The 30-minute [updatePeriodMillis] covers the time the process is dead.
 *
 * DATA-FLOW: none. Reading [Store] does not use the network.
 */
object WidgetRefresh {
    private val started = java.util.concurrent.atomic.AtomicBoolean(false)

    fun ensure(context: Context) {
        val app = context.applicationContext
        if (!started.compareAndSet(false, true)) return
        AppScope.scope.launch {
            Store.changes.collect {
                try {
                    LoviedowpieWidget.updateAll(app)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Try again on the next write.
                }
            }
        }
    }
}
