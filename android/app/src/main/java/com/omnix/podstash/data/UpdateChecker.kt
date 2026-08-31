package com.omnix.podstash.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File

object UpdateChecker {
    private const val API = "https://api.github.com/repos/plnoble/OMNIX-Podstash/releases/latest"
    private const val RAW = "https://raw.githubusercontent.com/plnoble/OMNIX-Podstash/main/version.json"

    fun check(currentCode: Int): UpdateInfo? {
        val info = try {
            fromRelease()
        } catch (_: Exception) {
            fromRaw()
        } ?: return null
        return if (info.versionCode > currentCode) info else null
    }

    private fun fromRelease(): UpdateInfo? {
        val o = Http.getJson(API)
        val tag = o.optString("tag_name").removePrefix("v")
        val notes = o.optString("body")
        val assets = o.optJSONArray("assets") ?: return null
        var apk = ""
        var code = parseCode(tag)
        for (i in 0 until assets.length()) {
            val a = assets.optJSONObject(i) ?: continue
            val name = a.optString("name")
            val url = a.optString("browser_download_url")
            if (name.endsWith(".apk", true)) apk = url
            if (name == "version.json") {
                try {
                    val v = JSONObject(Http.getString(url))
                    code = v.optInt("versionCode", code)
                } catch (_: Exception) {
                }
            }
        }
        if (apk.isBlank()) return null
        return UpdateInfo(tag, code, notes, apk, o.optString("html_url"))
    }

    private fun fromRaw(): UpdateInfo? {
        val v = Http.getJson(RAW)
        val code = v.optInt("versionCode")
        val ver = v.optString("version")
        val notes = v.optString("notes")
        val apk =
            "https://github.com/plnoble/OMNIX-Podstash/releases/download/v$ver/OMNIX-Podstash-$ver.apk"
        return UpdateInfo(ver, code, notes, apk, "https://github.com/plnoble/OMNIX-Podstash/releases/latest")
    }

    private fun parseCode(tag: String): Int {
        val p = tag.split(".").mapNotNull { it.toIntOrNull() }
        if (p.size >= 3) return p[0] * 10000 + p[1] * 100 + p[2]
        return 0
    }

    fun downloadApk(context: Context, info: UpdateInfo, onProgress: (Long, Long) -> Unit): File {
        val dir = File(context.cacheDir, "update").apply { mkdirs() }
        val dest = File(dir, "OMNIX-Podstash-${info.version}.apk")
        val req = okhttp3.Request.Builder().url(info.apkUrl).header("User-Agent", Http.UA).build()
        Http.client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("下载更新失败 HTTP ${resp.code}")
            val body = resp.body ?: error("empty apk")
            val total = body.contentLength()
            dest.outputStream().use { out ->
                body.byteStream().use { input ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        done += n
                        onProgress(done, total)
                    }
                }
            }
        }
        return dest
    }

    fun install(context: Context, apk: File) {
        val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            if (Build.VERSION.SDK_INT >= 26) {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
        }
        context.startActivity(intent)
    }
}
