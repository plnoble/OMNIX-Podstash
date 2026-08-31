package com.omnix.podstash.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class Store(context: Context) {
    val root: File = File(context.getExternalFilesDir(null), "library").apply { mkdirs() }
    private val stateFile = File(root, "library.json")
    private val lock = ReentrantLock()

    var shows: List<Show> = emptyList()
        private set
    var positions: MutableMap<String, Long> = mutableMapOf()
        private set
    var speed: Float = 1f
        private set
    var wifiOnly: Boolean = true
        private set
    var skippedVersionCode: Int = 0
        private set
    var treeUri: String = ""
        private set
    var lastPlayed: LastPlayed? = null
        private set

    init {
        load()
    }

    fun libraryPath(): String = root.absolutePath

    fun setTreeUri(uri: String) {
        lock.withLock {
            treeUri = uri
            persist()
        }
    }

    fun setLastPlayed(lp: LastPlayed?) {
        lock.withLock {
            lastPlayed = lp
            persist()
        }
    }

    fun showDir(show: Show): File {
        val name = sanitize(show.name.ifBlank { "Podcast" })
        return File(root, name).apply { mkdirs() }
    }

    fun upsertShow(show: Show) {
        lock.withLock {
            val rest = shows.filterNot { it.key() == show.key() }
            shows = rest + show
            persist()
        }
    }

    fun setSubscribed(show: Show, subscribed: Boolean) {
        val merged = shows.find { it.key() == show.key() } ?: show
        upsertShow(merged.copy(subscribed = subscribed))
    }

    fun setLastSeen(show: Show, guid: String) {
        val merged = shows.find { it.key() == show.key() } ?: show
        upsertShow(merged.copy(lastSeenGuid = guid))
    }

    fun subscribed(): List<Show> = shows.filter { it.subscribed }

    fun setPosition(guid: String, pos: Long) {
        lock.withLock {
            positions[guid] = pos
            persist()
        }
    }

    fun setSpeed(v: Float) {
        lock.withLock {
            speed = v
            persist()
        }
    }

    fun setWifiOnly(v: Boolean) {
        lock.withLock {
            wifiOnly = v
            persist()
        }
    }

    fun setSkippedVersion(code: Int) {
        lock.withLock {
            skippedVersionCode = code
            persist()
        }
    }

    fun rememberFile(show: Show, ep: Episode, file: File) {
        val dir = showDir(show)
        val indexFile = File(dir, INDEX)
        lock.withLock {
            val obj = if (indexFile.exists()) JSONObject(indexFile.readText()) else JSONObject().put("episodes", JSONObject())
            val eps = obj.optJSONObject("episodes") ?: JSONObject()
            val rec = JSONObject()
                .put("title", ep.title)
                .put("file", file.name)
                .put("size", file.length())
                .put("complete", file.length() >= MIN_COMPLETE)
                .put("guid", ep.guid)
            listOf(ep.guid, ep.audioUrl, sanitize(ep.title)).filter { it.isNotBlank() }.forEach {
                eps.put(it, rec)
            }
            obj.put("episodes", eps)
            indexFile.writeText(obj.toString())
        }
    }

    fun localStatus(show: Show, ep: Episode): Episode {
        val dir = showDir(show)
        val found = findExisting(dir, ep) ?: return ep
        val complete = found.length() >= MIN_COMPLETE &&
            (ep.size <= 0 || found.length() >= (ep.size * 0.98).toLong())
        return ep.copy(
            localPath = found.absolutePath,
            downloaded = complete,
            partial = !complete && found.length() > 0,
        )
    }

    fun findExisting(dir: File, ep: Episode): File? {
        val indexFile = File(dir, INDEX)
        if (indexFile.exists()) {
            try {
                val eps = JSONObject(indexFile.readText()).optJSONObject("episodes")
                if (eps != null) {
                    for (key in listOf(ep.guid, ep.audioUrl, sanitize(ep.title))) {
                        if (key.isBlank()) continue
                        val rec = eps.optJSONObject(key) ?: continue
                        val name = rec.optString("file")
                        if (name.isBlank()) continue
                        val f = File(dir, File(name).name)
                        if (f.exists() && f.length() > 0) return f
                    }
                }
            } catch (_: Exception) {
            }
        }
        val base = sanitize(ep.title)
        if (base.isBlank()) return null
        return AUDIO_EXTS.map { File(dir, "$base$it") }
            .filter { it.exists() && it.length() > 0 }
            .maxByOrNull { it.length() }
    }

    fun destFile(show: Show, ep: Episode): File {
        val dir = showDir(show)
        findExisting(dir, ep)?.let { return it }
        val base = sanitize(ep.title).ifBlank { sanitize(ep.guid.ifBlank { "episode" }) }
        val ext = guessExt(ep.audioUrl)
        return File(dir, "$base$ext")
    }

    private fun load() {
        if (!stateFile.exists()) return
        try {
            val o = JSONObject(stateFile.readText())
            wifiOnly = o.optBoolean("wifiOnly", true)
            speed = o.optDouble("speed", 1.0).toFloat()
            skippedVersionCode = o.optInt("skippedVersionCode", 0)
            treeUri = o.optString("treeUri")
            o.optJSONObject("lastPlayed")?.let { lp ->
                val sh = lp.optJSONObject("show") ?: return@let
                val ep = lp.optJSONObject("episode") ?: return@let
                lastPlayed = LastPlayed(
                    guid = lp.optString("guid"),
                    position = lp.optLong("position"),
                    show = Show(
                        id = sh.optString("id"),
                        name = sh.optString("name"),
                        author = sh.optString("author"),
                        artwork = sh.optString("artwork"),
                        feedUrl = sh.optString("feedUrl"),
                        subscribed = sh.optBoolean("subscribed"),
                    ),
                    episode = Episode(
                        index = ep.optInt("index"),
                        title = ep.optString("title"),
                        audioUrl = ep.optString("audioUrl"),
                        published = ep.optString("published"),
                        duration = ep.optString("duration"),
                        guid = ep.optString("guid"),
                        localPath = ep.optString("localPath"),
                        downloaded = ep.optBoolean("downloaded"),
                    ),
                )
            }
            val pos = o.optJSONObject("positions")
            if (pos != null) {
                pos.keys().forEach { positions[it] = pos.optLong(it) }
            }
            val arr = o.optJSONArray("shows") ?: JSONArray()
            val list = mutableListOf<Show>()
            for (i in 0 until arr.length()) {
                val s = arr.optJSONObject(i) ?: continue
                list += Show(
                    id = s.optString("id"),
                    name = s.optString("name"),
                    author = s.optString("author"),
                    artwork = s.optString("artwork"),
                    feedUrl = s.optString("feedUrl"),
                    episodeCount = s.optInt("episodeCount"),
                    subscribed = s.optBoolean("subscribed"),
                    lastSeenGuid = s.optString("lastSeenGuid"),
                )
            }
            shows = list
        } catch (_: Exception) {
        }
    }

    private fun persist() {
        val arr = JSONArray()
        shows.forEach { s ->
            arr.put(
                JSONObject()
                    .put("id", s.id)
                    .put("name", s.name)
                    .put("author", s.author)
                    .put("artwork", s.artwork)
                    .put("feedUrl", s.feedUrl)
                    .put("episodeCount", s.episodeCount)
                    .put("subscribed", s.subscribed)
                    .put("lastSeenGuid", s.lastSeenGuid),
            )
        }
        val pos = JSONObject()
        positions.forEach { (k, v) -> pos.put(k, v) }
        val lp = lastPlayed?.let {
            JSONObject()
                .put("guid", it.guid)
                .put("position", it.position)
                .put(
                    "show",
                    JSONObject()
                        .put("id", it.show.id)
                        .put("name", it.show.name)
                        .put("author", it.show.author)
                        .put("artwork", it.show.artwork)
                        .put("feedUrl", it.show.feedUrl)
                        .put("subscribed", it.show.subscribed),
                )
                .put(
                    "episode",
                    JSONObject()
                        .put("index", it.episode.index)
                        .put("title", it.episode.title)
                        .put("audioUrl", it.episode.audioUrl)
                        .put("published", it.episode.published)
                        .put("duration", it.episode.duration)
                        .put("guid", it.episode.guid)
                        .put("localPath", it.episode.localPath)
                        .put("downloaded", it.episode.downloaded),
                )
        }
        stateFile.writeText(
            JSONObject()
                .put("shows", arr)
                .put("positions", pos)
                .put("speed", speed.toDouble())
                .put("wifiOnly", wifiOnly)
                .put("skippedVersionCode", skippedVersionCode)
                .put("treeUri", treeUri)
                .put("lastPlayed", lp ?: JSONObject.NULL)
                .toString(),
        )
    }

    companion object {
        const val INDEX = ".omnix-index.json"
        const val MIN_COMPLETE = 32 * 1024L
        val AUDIO_EXTS = listOf(".mp3", ".m4a", ".mp4", ".aac", ".ogg", ".opus", ".wav", ".flac")

        fun sanitize(name: String, max: Int = 120): String {
            var n = name.trim().replace(Regex("""[<>:"/\\|?*\u0000-\u001f]"""), "")
            n = n.replace(Regex("""\s+"""), " ").trim(' ', '.')
            if (n.uppercase(java.util.Locale.ROOT) in setOf("CON", "PRN", "AUX", "NUL")) n = "_$n"
            if (n.isBlank()) n = "untitled"
            if (n.length > max) n = n.take(max).trimEnd(' ', '.')
            return n
        }

        fun guessExt(url: String): String {
            val path = url.substringBefore('?').lowercase(java.util.Locale.ROOT)
            return AUDIO_EXTS.firstOrNull { path.endsWith(it) } ?: ".mp3"
        }
    }
}

fun Show.key(): String = id.ifBlank { feedUrl }.ifBlank { name }
