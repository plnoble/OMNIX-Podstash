package com.omnix.podstash.data

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object Http {
    const val UA =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

    val client: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    fun getBytes(url: String, extraHeaders: Map<String, String> = emptyMap()): Pair<okhttp3.Response, okhttp3.ResponseBody> {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Accept", "*/*")
            .apply { extraHeaders.forEach { (k, v) -> header(k, v) } }
            .build()
        val resp = client.newCall(req).execute()
        val body = resp.body ?: error("empty body ${resp.code}")
        return resp to body
    }

    fun getString(url: String): String {
        var last: Exception? = null
        repeat(3) { attempt ->
            try {
                val (resp, body) = getBytes(url)
                resp.use {
                    body.use {
                        if (!resp.isSuccessful) error("HTTP ${resp.code} $url")
                        return body.string()
                    }
                }
            } catch (e: Exception) {
                last = e
                Thread.sleep(300L * (attempt + 1))
            }
        }
        throw last ?: IllegalStateException("request failed $url")
    }

    fun getJson(url: String): JSONObject = JSONObject(getString(url))
}
