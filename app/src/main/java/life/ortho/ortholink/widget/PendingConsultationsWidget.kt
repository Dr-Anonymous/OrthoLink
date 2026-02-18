package life.ortho.ortholink.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.RemoteViews
import life.ortho.ortholink.MainActivity
import life.ortho.ortholink.R

class PendingConsultationsWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        // There may be multiple widgets active, so update all of them
        for (appWidgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val appWidgetManager = AppWidgetManager.getInstance(context)
        val componentName = ComponentName(context, PendingConsultationsWidget::class.java)
        val appWidgetIds = appWidgetManager.getAppWidgetIds(componentName)

        when (intent.action) {
            ACTION_REFRESH -> {
                // Show loader on all widgets and disable clicks
                for (appWidgetId in appWidgetIds) {
                    val views = RemoteViews(context.packageName, R.layout.widget_pending_consultations)
                    
                    // Show full screen loader
                    views.setViewVisibility(R.id.widget_loading_overlay, android.view.View.VISIBLE)
                    
                    // Disable clicks to prevent double-click or interaction while loading
                    views.setOnClickPendingIntent(R.id.widget_title_bar, null)
                    views.setOnClickPendingIntent(R.id.widget_title, null)
                    views.setPendingIntentTemplate(R.id.widget_list, null)

                    // Full update to ensure intents are cleared
                    appWidgetManager.updateAppWidget(appWidgetId, views)
                }

                // Trigger data refresh
                // We use notifyAppWidgetViewDataChanged which triggers onDataSetChanged in the service
                appWidgetManager.notifyAppWidgetViewDataChanged(appWidgetIds, R.id.widget_list)
                Log.d("Widget", "Refresh triggered")
            }
            ACTION_REFRESH_COMPLETE -> {
                 // Hide loader and restore clicks on all widgets
                for (appWidgetId in appWidgetIds) {
                    updateAppWidget(context, appWidgetManager, appWidgetId)
                }
                Log.d("Widget", "Refresh complete")
            }
        }
    }

    companion object {
        const val ACTION_REFRESH = "life.ortho.ortholink.widget.REFRESH"
        const val ACTION_REFRESH_COMPLETE = "life.ortho.ortholink.widget.REFRESH_COMPLETE"

        fun updateAppWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
            val views = RemoteViews(context.packageName, R.layout.widget_pending_consultations)

            // Ensure loader is hidden by default on regular updates
            views.setViewVisibility(R.id.widget_loading_overlay, android.view.View.GONE)

            // 1. Bind the RemoteViewsService (the list adapter)
            val intent = Intent(context, WidgetRemoteViewsService::class.java)
            views.setRemoteAdapter(R.id.widget_list, intent)
            views.setEmptyView(R.id.widget_list, R.id.widget_empty_view)

            // 2. Title/Background Click -> Refresh
            // We set it on the title bar container so it's easy to hit
            val refreshIntent = Intent(context, PendingConsultationsWidget::class.java).apply {
                action = ACTION_REFRESH
            }
            val refreshPendingIntent = PendingIntent.getBroadcast(
                context, 
                0, 
                refreshIntent, 
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_title_bar, refreshPendingIntent)
            
            // Also set on the title text itself just in case
            views.setOnClickPendingIntent(R.id.widget_title, refreshPendingIntent)

            // Set template for the list items so clicking them also refreshes
            views.setPendingIntentTemplate(R.id.widget_list, refreshPendingIntent)

            // Instruct the widget manager to update the widget
            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
}
