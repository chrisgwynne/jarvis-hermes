package com.jarvis.hermes.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.jarvis.hermes.R
import com.jarvis.hermes.service.VoiceService

class JarvisWidget : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (appWidgetId in appWidgetIds) {
            updateWidget(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_TOGGLE -> {
                val prefs = context.getSharedPreferences("jarvis_hermes", Context.MODE_PRIVATE)
                val isActive = prefs.getBoolean("conversation_active", false)
                val serviceIntent = Intent(context, VoiceService::class.java)
                if (isActive) {
                    serviceIntent.action = "END"
                } else {
                    serviceIntent.action = "START"
                }
                context.startForegroundService(serviceIntent)
            }
            ACTION_UPDATE_STATE -> {
                val appWidgetManager = AppWidgetManager.getInstance(context)
                val appWidgetIds = intent.getIntArrayExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS)
                appWidgetIds?.forEach { appWidgetId ->
                    updateWidget(context, appWidgetManager, appWidgetId)
                }
            }
        }
    }

    companion object {
        const val ACTION_TOGGLE = "com.jarvis.hermes.WIDGET_TOGGLE"
        const val ACTION_UPDATE_STATE = "com.jarvis.hermes.WIDGET_UPDATE_STATE"

        fun updateWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
            val prefs = context.getSharedPreferences("jarvis_hermes", Context.MODE_PRIVATE)
            val isActive = prefs.getBoolean("conversation_active", false)
            val connectionState = prefs.getString("connection_state", "disconnected") ?: "disconnected"

            val views = RemoteViews(context.packageName, R.layout.widget)

            val (statusText, micTint) = when {
                !isActive -> "Tap to start" to "#8B949E"
                connectionState == "reconnecting" -> "Reconnecting..." to "#F0A500"
                else -> "Listening..." to "#00D4FF"
            }

            views.setTextViewText(R.id.widgetStatusText, statusText)
            try {
                views.setInt(R.id.widgetMicBtn, "setColorFilter", android.graphics.Color.parseColor(micTint))
            } catch (e: Exception) { /* fallback — leave default tint */ }

            val toggleIntent = Intent(context, JarvisWidget::class.java).apply {
                action = ACTION_TOGGLE
            }
            val togglePendingIntent = PendingIntent.getBroadcast(
                context,
                0,
                toggleIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            views.setOnClickPendingIntent(R.id.widgetMicBtn, togglePendingIntent)

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }

        fun broadcastStateUpdate(context: Context) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val appWidgetIds = appWidgetManager.getAppWidgetIds(
                android.content.ComponentName(context, JarvisWidget::class.java)
            )
            if (appWidgetIds.isNotEmpty()) {
                val intent = Intent(context, JarvisWidget::class.java).apply {
                    action = ACTION_UPDATE_STATE
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, appWidgetIds)
                }
                context.sendBroadcast(intent)
            }
        }
    }
}