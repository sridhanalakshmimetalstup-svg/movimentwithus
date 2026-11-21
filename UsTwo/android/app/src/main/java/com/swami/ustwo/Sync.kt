package com.swami.ustwo

import android.annotation.SuppressLint
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.TimeUnit

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Notify.createChannel(this)
        if (Store(this).isConfigured) RefreshWorker.schedule(this)
    }
}

object Sync {
    private val lock = Any()

    /**
     * Blocking. Pulls feed.json, downloads the newest photo if it changed, redraws widgets.
     * Returns the new post if it came from your partner (so we can notify), else null.
     */
    fun refresh(ctx: Context): Post? {
        synchronized(lock) {
            return refreshLocked(ctx)
        }
    }

    private fun refreshLocked(ctx: Context): Post? {
        val store = Store(ctx)
        val cfg = store.config ?: return null
        val (feed, _) = GitHub(cfg).readFeed()
        store.feed = feed
        val newest = feed.firstOrNull()
        if (newest == null) {
            UsWidget.updateAll(ctx)
            return null
        }
        val previous = store.latestPath
        if (newest.path != previous || !Cache.latestFile(ctx).exists()) {
            val bytes = Cache.read(ctx, newest.path)
                ?: GitHub(cfg).download(newest.path).also { Cache.put(ctx, newest.path, it) }
            Cache.setLatest(ctx, bytes)
            store.latestPath = newest.path
        }
        UsWidget.updateAll(ctx)
        return if (previous != null && newest.path != previous && newest.by != cfg.name) newest else null
    }
}

/** Polls GitHub every 15 minutes (Android's minimum for background work). */
class RefreshWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            Sync.refresh(applicationContext)?.let { Notify.newPhoto(applicationContext, it) }
            Result.success()
        } catch (e: GitHubException) {
            UsWidget.updateAll(applicationContext)
            if (e.code == 429 || e.code >= 500) Result.retry() else Result.success()
        } catch (e: IOException) {
            UsWidget.updateAll(applicationContext)
            Result.retry()
        }
    }

    companion object {
        private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<RefreshWorker>(15, TimeUnit.MINUTES)
                .setConstraints(online)
                .build()
            WorkManager.getInstance(ctx)
                .enqueueUniquePeriodicWork("ustwo-refresh", ExistingPeriodicWorkPolicy.KEEP, req)
        }

        fun runNow(ctx: Context) {
            val req = OneTimeWorkRequestBuilder<RefreshWorker>().setConstraints(online).build()
            WorkManager.getInstance(ctx)
                .enqueueUniqueWork("ustwo-refresh-now", ExistingWorkPolicy.REPLACE, req)
        }

        fun cancelAll(ctx: Context) {
            WorkManager.getInstance(ctx).cancelAllWork()
        }
    }
}

object Notify {
    private const val CHANNEL = "photos"

    fun createChannel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "New photos", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    @SuppressLint("MissingPermission")
    fun newPhoto(ctx: Context, post: Post) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val intent = Intent(ctx, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_OPEN_LATEST, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = PendingIntent.getActivity(
            ctx, 1, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        // Caption deliberately not shown — notifications are visible on the lock screen.
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_heart)
            .setContentTitle("${post.by} sent you a photo 💌")
            .setContentText("Tap to see it")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(1, n)
    }
}
