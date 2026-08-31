package com.omnix.podstash.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.omnix.podstash.PodstashApp
import com.omnix.podstash.R
import com.omnix.podstash.data.Catalog
import com.omnix.podstash.data.Downloader

class AutoScanWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as? PodstashApp ?: return Result.failure()
        val store = app.store
        val once = inputData.getBoolean("once", false)
        if (!once && !store.autoScan) return Result.success()
        val downloader = Downloader(app, store)
        if (store.wifiOnly && !downloader.onWifi()) return Result.retry()
        setForeground(foreground("正在扫描关注的节目…"))
        val subs = store.subscribed()
        if (subs.isEmpty()) {
            store.setLastAutoScan("没有关注的节目")
            return Result.success()
        }
        var downloaded = 0
        var existed = 0
        val failures = mutableListOf<String>()
        val limit = store.autoScanLimit
        for (show in subs) {
            try {
                val (fresh, eps) = Catalog.fetchRss(show)
                val marked = store.scanAndMark(fresh.copy(subscribed = true), eps)
                existed += marked.count { it.downloaded }
                val pending = marked.filter { !it.downloaded && it.audioUrl.isNotBlank() }
                val take = if (limit <= 0) pending else pending.take(limit)
                val merged = fresh.copy(subscribed = true)
                for (ep in take) {
                    try {
                        val done = downloader.download(merged, ep)
                        if (done.downloaded) downloaded += 1
                    } catch (e: Downloader.Paused) {
                        store.setLastAutoScan("扫描被暂停")
                        return Result.retry()
                    } catch (e: Exception) {
                        failures += "${ep.title.take(20)}：${e.message ?: "失败"}"
                    }
                }
                val newest = marked.firstOrNull()?.guid.orEmpty()
                if (newest.isNotBlank()) store.setLastSeen(merged, newest)
            } catch (e: Exception) {
                failures += "${show.name}：${e.message ?: "RSS 失败"}"
            }
        }
        val msg = buildString {
            append("扫描 ${subs.size} 档")
            if (downloaded > 0) append(" · 新下 $downloaded")
            if (existed > 0) append(" · 本地已有 $existed")
            if (failures.isNotEmpty()) append(" · 失败 ${failures.size}")
        }
        store.setLastAutoScan(msg)
        return Result.success()
    }

    private fun foreground(text: String): ForegroundInfo {
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel("autoscan", "定期扫描", NotificationManager.IMPORTANCE_LOW))
        val notif = NotificationCompat.Builder(applicationContext, "autoscan")
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("OMNIX-Podstash")
            .setContentText(text)
            .setOngoing(true)
            .build()
        return if (Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(42, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(42, notif)
        }
    }
}
