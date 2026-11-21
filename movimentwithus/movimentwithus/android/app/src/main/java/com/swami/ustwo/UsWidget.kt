package com.swami.ustwo

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.text.format.DateUtils
import android.view.View
import android.widget.RemoteViews

class UsWidget : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        render(ctx, mgr, ids)
        RefreshWorker.runNow(ctx)
    }

    override fun onEnabled(ctx: Context) {
        RefreshWorker.schedule(ctx)
    }

    companion object {
        fun updateAll(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, UsWidget::class.java))
            if (ids.isNotEmpty()) render(ctx, mgr, ids)
        }

        private fun render(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
            val views = RemoteViews(ctx.packageName, R.layout.widget_us)
            val store = Store(ctx)
            // Keep the bitmap small: RemoteViews bitmaps are copied across processes.
            val bmp = runCatching { Images.decodeFile(Cache.latestFile(ctx), 720) }.getOrNull()
            val post = store.latestPost

            if (bmp != null && post != null) {
                views.setImageViewBitmap(R.id.photo, bmp)
                views.setViewVisibility(R.id.photo, View.VISIBLE)
                views.setViewVisibility(R.id.empty, View.GONE)
                views.setViewVisibility(R.id.label_bar, View.VISIBLE)
                val ago = DateUtils.getRelativeTimeSpanString(
                    post.ts, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS
                )
                views.setTextViewText(R.id.label_title, "${post.by} · $ago")
                views.setTextViewText(R.id.label_caption, post.caption)
                views.setViewVisibility(
                    R.id.label_caption, if (post.caption.isBlank()) View.GONE else View.VISIBLE
                )
            } else {
                views.setViewVisibility(R.id.photo, View.GONE)
                views.setViewVisibility(R.id.label_bar, View.GONE)
                views.setViewVisibility(R.id.empty, View.VISIBLE)
                views.setTextViewText(
                    R.id.empty,
                    if (store.isConfigured) ctx.getString(R.string.widget_empty) else "Tap to set up UsTwo"
                )
            }

            val intent = Intent(ctx, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_OPEN_LATEST, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            val pi = PendingIntent.getActivity(
                ctx, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            views.setOnClickPendingIntent(android.R.id.background, pi)
            mgr.updateAppWidget(ids, views)
        }
    }
}
