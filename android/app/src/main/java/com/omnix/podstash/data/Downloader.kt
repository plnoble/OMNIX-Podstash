package com.omnix.podstash.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.core.app.NotificationCompat
import com.omnix.podstash.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile

class Downloader(private val context: Context, private val store: Store) {
    private val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        nm.createNotificationChannel(
            NotificationChannel("downloads", "下载", NotificationManager.IMPORTANCE_LOW),
        )
    }

    fun onWifi(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    suspend fun download(show: Show, episode: Episode, force: Boolean = false): Episode =
        withContext(Dispatchers.IO) {
            val dest = store.destFile(show, episode)
            if (!force && dest.exists() && dest.length() >= Store.MIN_COMPLETE) {
                val ep = episode.copy(localPath = dest.absolutePath, downloaded = true)
                store.rememberFile(show, ep, dest)
                return@withContext ep
            }
            if (store.wifiOnly && !onWifi() && !force) {
                error("已开启仅 Wi-Fi 下载")
            }
            notify(episode.title, "下载中…", 0, 0)
            var existing = if (dest.exists()) dest.length() else 0L
            if (existing in 1 until Store.MIN_COMPLETE) existing = 0L
            val req = Request.Builder()
                .url(episode.audioUrl)
                .header("User-Agent", Http.UA)
                .header("Accept", "*/*")
                .apply { if (existing > 0) header("Range", "bytes=$existing-") }
                .build()
            Http.client.newCall(req).execute().use { resp ->
                var offset = existing
                if (resp.code == 200 && existing > 0) {
                    if (dest.length() >= Store.MIN_COMPLETE) {
                        notifyDone(episode.title, true)
                        val ep = episode.copy(localPath = dest.absolutePath, downloaded = true)
                        store.rememberFile(show, ep, dest)
                        return@withContext ep
                    }
                    offset = 0
                }
                if (resp.code == 416 && existing > 0) {
                    notifyDone(episode.title, true)
                    val ep = episode.copy(localPath = dest.absolutePath, downloaded = true)
                    store.rememberFile(show, ep, dest)
                    return@withContext ep
                }
                if (resp.code !in listOf(200, 206)) error("HTTP ${resp.code}")
                val cl = resp.header("Content-Length")?.toLongOrNull() ?: 0L
                val total = if (resp.code == 206) cl + offset else cl
                val body = resp.body ?: error("empty body")
                if (offset == 0L && dest.exists()) dest.delete()
                dest.parentFile?.mkdirs()
                RandomAccessFile(dest, "rw").use { raf ->
                    if (offset > 0) raf.seek(offset) else raf.setLength(0)
                    val buf = ByteArray(64 * 1024)
                    var done = offset
                    body.byteStream().use { input ->
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            raf.write(buf, 0, n)
                            done += n
                            if (total > 0) notify(episode.title, "下载中", done, total)
                        }
                    }
                }
            }
            if (!dest.exists() || dest.length() <= 0) error("下载结果为空")
            val ep = episode.copy(localPath = dest.absolutePath, downloaded = dest.length() >= Store.MIN_COMPLETE)
            store.rememberFile(show, ep, dest)
            notifyDone(episode.title, ep.downloaded)
            ep
        }

    private fun notify(title: String, text: String, done: Long, total: Long) {
        val b = NotificationCompat.Builder(context, "downloads")
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
        if (total > 0) b.setProgress(1000, ((done * 1000) / total).toInt(), false)
        else b.setProgress(0, 0, true)
        nm.notify(title.hashCode(), b.build())
    }

    private fun notifyDone(title: String, ok: Boolean) {
        nm.notify(
            title.hashCode(),
            NotificationCompat.Builder(context, "downloads")
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle(title)
                .setContentText(if (ok) "已保存到库" else "下载失败")
                .setOngoing(false)
                .build(),
        )
    }
}
