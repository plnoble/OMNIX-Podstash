package com.omnix.podstash.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class Store(context: Context) {
    private val appContext = context.applicationContext
    val root: File = File(appContext.getExternalFilesDir(null), "library").apply { mkdirs() }
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
    var autoScan: Boolean = false
        private set
    var autoScanDays: Int = 7
        private set
    var autoScanLimit: Int = 30
        private set
    var lastAutoScan: Long = 0
        private set
    var lastAutoScanMessage: String = ""
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

    fun showDir(show: Show, create: Boolean = true): File {
        val name = sanitize(show.name.ifBlank { "Podcast" })
        return File(root, name).apply { if (create) mkdirs() }
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

    fun setAutoScan(v: Boolean) {
        lock.withLock {
            autoScan = v
            persist()
        }
    }

    fun setAutoScanDays(days: Int) {
        lock.withLock {
            autoScanDays = days.coerceIn(1, 30)
            persist()
        }
    }

    fun setAutoScanLimit(n: Int) {
        lock.withLock {
            autoScanLimit = n.coerceAtLeast(0)
            persist()
        }
    }

    fun setLastAutoScan(message: String) {
        lock.withLock {
            lastAutoScan = System.currentTimeMillis()
            lastAutoScanMessage = message
            persist()
        }
    }

    fun rememberFile(show: Show, ep: Episode, file: File) {
        rememberHit(show, ep, file.name, file.length(), file.absolutePath, uri = "")
    }

    fun rememberUri(show: Show, ep: Episode, name: String, size: Long, uri: String) {
        rememberHit(show, ep, name, size, absPath = "", uri = uri)
    }

    private fun rememberHit(show: Show, ep: Episode, name: String, size: Long, absPath: String, uri: String) {
        val dir = showDir(show)
        val indexFile = File(dir, INDEX)
        lock.withLock {
            val obj = if (indexFile.exists()) JSONObject(indexFile.readText()) else JSONObject().put("episodes", JSONObject())
            val eps = obj.optJSONObject("episodes") ?: JSONObject()
            val rec = JSONObject()
                .put("title", ep.title)
                .put("file", name)
                .put("abs", absPath)
                .put("uri", uri)
                .put("size", size)
                .put("complete", size >= MIN_COMPLETE)
                .put("guid", ep.guid)
            listOf(ep.guid, ep.audioUrl, sanitize(ep.title)).filter { it.isNotBlank() }.forEach {
                eps.put(it, rec)
            }
            obj.put("episodes", eps)
            indexFile.writeText(obj.toString())
        }
    }

    fun localStatus(show: Show, ep: Episode): Episode {
        val dir = showDir(show, create = false)
        val found = findExisting(dir, ep)
        if (found != null) {
            val complete = found.length() >= MIN_COMPLETE &&
                (ep.size <= 0 || found.length() >= (ep.size * 0.98).toLong())
            return ep.copy(
                localPath = found.absolutePath,
                downloaded = complete,
                partial = !complete && found.length() > 0,
            )
        }
        val indexed = findIndexUri(dir, ep)
        if (indexed != null) {
            val (uri, complete) = indexed
            return ep.copy(localPath = uri, downloaded = complete, partial = !complete)
        }
        return ep
    }

    fun scanAndMark(show: Show, episodes: List<Episode>): List<Episode> {
        if (episodes.isEmpty()) return episodes
        val prelim = episodes.map { localStatus(show, it) }
        val hits = LibraryScan.listAppLibrary(root, show.name).toMutableList()
        if (treeUri.isNotBlank()) {
            hits += LibraryScan.listSaf(appContext, treeUri, show.name)
        }
        val remaining = prelim.filter { !it.downloaded }
        val taken = prelim.map { it.localPath }.filter { it.isNotBlank() }.toSet()
        val free = hits.distinctBy { it.path }.filter { it.path !in taken }
        val assigned = LibraryScan.assign(remaining, free, show.name)
        return prelim.map { ep ->
            val hit = assigned[ep.index] ?: return@map ep
            val complete = hit.size >= MIN_COMPLETE &&
                (ep.size <= 0 || hit.size >= (ep.size * 0.98).toLong())
            if (hit.path.startsWith("content://")) {
                rememberUri(show, ep, hit.name, hit.size, hit.path)
            } else {
                val f = File(hit.path)
                if (f.exists()) rememberFile(show, ep, f)
            }
            ep.copy(
                localPath = hit.path,
                downloaded = complete,
                partial = !complete && hit.size > 0,
            )
        }
    }

    fun findIndexUri(dir: File, ep: Episode): Pair<String, Boolean>? {
        val indexFile = File(dir, INDEX)
        if (!indexFile.exists()) return null
        return try {
            val eps = JSONObject(indexFile.readText()).optJSONObject("episodes") ?: return null
            for (key in listOf(ep.guid, ep.audioUrl, sanitize(ep.title))) {
                if (key.isBlank()) continue
                val rec = eps.optJSONObject(key) ?: continue
                val uri = rec.optString("uri")
                if (!uri.startsWith("content://")) continue
                val size = rec.optLong("size")
                val complete = rec.optBoolean("complete", size >= MIN_COMPLETE)
                return uri to complete
            }
            null
        } catch (_: Exception) {
            null
        }
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
                        val abs = rec.optString("abs")
                        if (abs.isNotBlank()) {
                            val af = File(abs)
                            if (af.exists() && af.length() > 0) return af
                        }
                        val name = rec.optString("file")
                        if (name.isBlank()) continue
                        val named = File(name)
                        if (named.isAbsolute && named.exists() && named.length() > 0) return named
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
        fuzzyInDir(dir, ep)?.let { return it }
        val marked = localStatus(show, ep)
        if (marked.downloaded && marked.localPath.isNotBlank() && !marked.localPath.startsWith("content://")) {
            val existing = File(marked.localPath)
            if (existing.exists()) return existing
        }
        val base = sanitize(ep.title).ifBlank { sanitize(ep.guid.ifBlank { "episode" }) }
        val ext = guessExt(ep.audioUrl)
        return File(dir, "$base$ext")
    }

    private fun fuzzyInDir(dir: File, ep: Episode): File? {
        if (!dir.exists()) return null
        val audio = dir.listFiles()?.filter { it.isFile && LibraryScan.isAudio(it.name) && it.length() > 0 } ?: return null
        val ranked = audio.map { it to LibraryScan.score(ep.title, it.name, dir.name) }
            .filter { it.second >= 80 }
            .sortedByDescending { it.second }
        if (ranked.isEmpty()) return null
        if (ranked.size >= 2 && ranked[0].second == ranked[1].second && ranked[0].second < 90) return null
        return ranked[0].first
    }

    private fun load() {
        if (!stateFile.exists()) return
        try {
            val o = JSONObject(stateFile.readText())
            wifiOnly = o.optBoolean("wifiOnly", true)
            speed = o.optDouble("speed", 1.0).toFloat()
            skippedVersionCode = o.optInt("skippedVersionCode", 0)
            treeUri = o.optString("treeUri")
            autoScan = o.optBoolean("autoScan", false)
            autoScanDays = o.optInt("autoScanDays", 7).coerceIn(1, 30)
            autoScanLimit = o.optInt("autoScanLimit", 30).coerceAtLeast(0)
            lastAutoScan = o.optLong("lastAutoScan", 0)
            lastAutoScanMessage = o.optString("lastAutoScanMessage")
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
                .put("autoScan", autoScan)
                .put("autoScanDays", autoScanDays)
                .put("autoScanLimit", autoScanLimit)
                .put("lastAutoScan", lastAutoScan)
                .put("lastAutoScanMessage", lastAutoScanMessage)
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
