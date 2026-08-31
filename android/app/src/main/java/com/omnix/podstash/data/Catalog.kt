package com.omnix.podstash.data

import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.net.URLEncoder
import java.util.Locale

object Catalog {
    private const val ITUNES_SEARCH = "https://itunes.apple.com/search"
    private const val ITUNES_LOOKUP = "https://itunes.apple.com/lookup"
    private const val XYZRANK = "https://xyzrank.com/api/podcasts"
    private const val APPLE_TOP = "https://itunes.apple.com/%s/rss/toppodcasts/limit=%d/json"

    fun search(query: String, country: String = "CN", limit: Int = 20): List<Show> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val cc = country.uppercase(Locale.ROOT)
        var shows = searchItunes(q, cc, limit)
        if (shows.isEmpty() && cc != "US") shows = searchItunes(q, "US", limit)
        return shows
    }

    fun trending(source: String): Pair<String, List<Show>> {
        val src = source.lowercase(Locale.ROOT)
        if (src in listOf("apple", "intl", "international", "us")) {
            return "apple" to appleCharts("US", 40)
        }
        return try {
            val xyz = xyzrank(40)
            if (xyz.isNotEmpty()) "xyzrank" to xyz else "apple-cn" to appleCharts("CN", 40)
        } catch (_: Exception) {
            "apple-cn" to appleCharts("CN", 40)
        }
    }

    fun resolve(src: String, country: String = "CN"): Pair<Show, List<Episode>> {
        val s = src.trim()
        require(s.isNotEmpty()) { "请输入节目 ID、链接或 RSS" }
        val lower = s.lowercase(Locale.ROOT)
        when {
            "youzhiyouxing.cn/materials/" in lower -> error("有知有行 materials 请在 PC 端打开")
            "xiaoyuzhoufm.com/podcast/" in lower || "xiaoyuzhoufm.com/episode/" in lower ->
                return resolveXiaoyuzhou(s, country)
            "ximalaya.com/album/" in lower -> {
                val id = Regex("""album/(\d+)""").find(s)?.groupValues?.get(1)
                    ?: error("不是有效的喜马拉雅专辑链接")
                return fetchRss(Show(id = id, name = "", feedUrl = "https://www.ximalaya.com/album/$id.xml"))
            }
            s.all { it.isDigit() } -> return fetchRss(lookup(s) ?: error("找不到 Apple Podcast ID: $s"))
            "podcasts.apple.com" in lower -> {
                val id = Regex("""id(\d+)""").find(s)?.groupValues?.get(1)
                    ?: error("无法从 Apple 链接解析 ID")
                return fetchRss(lookup(id) ?: error("找不到 Apple Podcast ID: $id"))
            }
            lower.startsWith("http://") || lower.startsWith("https://") ->
                return fetchRss(Show(id = "", name = "", feedUrl = s))
            else -> error("无法识别来源")
        }
    }

    private fun searchItunes(term: String, country: String, limit: Int): List<Show> {
        val url =
            "$ITUNES_SEARCH?media=podcast&entity=podcast&country=$country&limit=$limit&term=" +
                URLEncoder.encode(term, "UTF-8")
        val arr = Http.getJson(url).optJSONArray("results") ?: JSONArray()
        val out = mutableListOf<Show>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val feed = o.optString("feedUrl").trim()
            if (feed.isEmpty()) continue
            out += Show(
                id = o.opt("collectionId")?.toString() ?: "",
                name = o.optString("collectionName").ifBlank { o.optString("trackName") },
                author = o.optString("artistName"),
                artwork = o.optString("artworkUrl600").ifBlank { o.optString("artworkUrl100") },
                feedUrl = feed,
                episodeCount = o.optInt("trackCount"),
                country = o.optString("country").ifBlank { country },
            )
        }
        return out
    }

    fun lookup(appleId: String): Show? {
        val data = Http.getJson("$ITUNES_LOOKUP?id=$appleId&entity=podcast")
        val arr = data.optJSONArray("results") ?: return null
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val feed = o.optString("feedUrl").trim()
            if (feed.isEmpty()) continue
            return Show(
                id = o.opt("collectionId")?.toString() ?: appleId,
                name = o.optString("collectionName").ifBlank { o.optString("trackName") },
                author = o.optString("artistName"),
                artwork = o.optString("artworkUrl600").ifBlank { o.optString("artworkUrl100") },
                feedUrl = feed,
                episodeCount = o.optInt("trackCount"),
                country = o.optString("country"),
            )
        }
        return null
    }

    private fun xyzrank(limit: Int): List<Show> {
        val data = Http.getJson("$XYZRANK?limit=$limit&offset=0")
        val items = data.optJSONArray("items") ?: JSONArray()
        val out = mutableListOf<Show>()
        for (i in 0 until items.length()) {
            val o = items.optJSONObject(i) ?: continue
            val links = linkMap(o.optJSONArray("links"))
            val appleUrl = links["apple"].orEmpty()
            val appleId = Regex("""id(\d+)""").find(appleUrl)?.groupValues?.get(1).orEmpty()
            val rss = links["rss"].orEmpty()
            val xyz = links["xyz"] ?: links["website"].orEmpty()
            val name = o.optString("name").trim()
            if (name.isEmpty()) continue
            val feed = rss.ifBlank { xyz }
            if (feed.isEmpty() && appleId.isEmpty()) continue
            out += Show(
                id = appleId.ifBlank { o.optString("id") },
                name = name,
                author = o.optString("authorsText"),
                artwork = o.optString("logoURL"),
                feedUrl = feed,
                episodeCount = o.optInt("trackCount"),
                country = "CN",
                rank = o.optInt("rank"),
            )
        }
        return out
    }

    private fun appleCharts(country: String, limit: Int): List<Show> {
        val data = Http.getJson(APPLE_TOP.format(country.lowercase(Locale.ROOT), limit.coerceIn(1, 200)))
        val feed = data.optJSONObject("feed") ?: return emptyList()
        val raw = feed.opt("entry") ?: return emptyList()
        val entries: JSONArray = when (raw) {
            is JSONArray -> raw
            is JSONObject -> JSONArray().put(raw)
            else -> return emptyList()
        }
        val out = mutableListOf<Show>()
        for (i in 0 until entries.length()) {
            val e = entries.optJSONObject(i) ?: continue
            val idObj = e.optJSONObject("id")
            val appleId = idObj?.optJSONObject("attributes")?.optString("im:id").orEmpty()
            val name = e.optJSONObject("im:name")?.optString("label").orEmpty()
            if (name.isEmpty() && appleId.isEmpty()) continue
            val images = e.optJSONArray("im:image")
            val art = if (images != null && images.length() > 0) {
                images.optJSONObject(images.length() - 1)?.optString("label").orEmpty()
            } else ""
            out += Show(
                id = appleId,
                name = name.ifBlank { "Untitled" },
                author = e.optJSONObject("im:artist")?.optString("label").orEmpty(),
                artwork = art,
                feedUrl = "",
                rank = i + 1,
                country = country.uppercase(Locale.ROOT),
            )
        }
        return out
    }

    private fun resolveXiaoyuzhou(url: String, country: String): Pair<Show, List<Episode>> {
        val html = Http.getString(url)
        val title = xiaoyuzhouTitle(html).ifBlank { error("无法读取小宇宙节目标题") }
        val found = search(title, country, 10)
        val match = found.firstOrNull { it.name.replace("\\s".toRegex(), "").equals(title.replace("\\s".toRegex(), ""), true) }
            ?: found.firstOrNull()
            ?: error("未在 Apple 目录中找到「$title」的 RSS")
        val show = if (match.id.isNotBlank()) lookup(match.id) ?: match else match
        return fetchRss(show)
    }

    private fun xiaoyuzhouTitle(html: String): String {
        val next = Regex("""<script id="__NEXT_DATA__"[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1)
        if (!next.isNullOrBlank()) {
            try {
                val data = JSONObject(next)
                val page = data.optJSONObject("props")?.optJSONObject("pageProps")
                val pod = page?.optJSONObject("podcast")
                    ?: page?.optJSONObject("episode")?.optJSONObject("podcast")
                val t = pod?.optString("title").orEmpty()
                if (t.isNotBlank()) return t
            } catch (_: Exception) {
            }
        }
        return Regex("""property=["']og:title["'][^>]*content=["']([^"']+)""")
            .find(html)?.groupValues?.get(1).orEmpty()
    }

    fun fetchRss(show: Show): Pair<Show, List<Episode>> {
        val feed = show.feedUrl.ifBlank {
            if (show.id.isNotBlank()) lookup(show.id)?.feedUrl.orEmpty() else ""
        }
        require(feed.isNotBlank()) { "该节目没有 RSS" }
        val xml = Http.getString(feed)
        val (title, episodes) = parseRss(xml)
        val merged = show.copy(
            name = if (title.isNotBlank() && title != "Podcast") title else show.name.ifBlank { title },
            feedUrl = feed,
            episodeCount = episodes.size,
        )
        return merged to episodes
    }

    private fun parseRss(xml: String): Pair<String, List<Episode>> {
        val factory = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }
        val p = factory.newPullParser()
        p.setInput(StringReader(xml))
        var showTitle = "Podcast"
        val episodes = mutableListOf<Episode>()
        var event = p.eventType
        var inItem = false
        var inChannel = false
        var title = ""
        var guid = ""
        var pub = ""
        var duration = ""
        var enclosure = ""
        var size = 0L
        fun flushItem() {
            if (enclosure.isNotBlank()) {
                episodes += Episode(
                    index = episodes.size,
                    title = title.ifBlank { "Untitled" },
                    audioUrl = enclosure,
                    published = pub.take(10),
                    duration = duration,
                    guid = guid.ifBlank { enclosure },
                    size = size,
                )
            }
            title = ""; guid = ""; pub = ""; duration = ""; enclosure = ""; size = 0L
        }
        while (event != XmlPullParser.END_DOCUMENT) {
            val name = p.name?.substringAfterLast(':') ?: ""
            when (event) {
                XmlPullParser.START_TAG -> when (name.lowercase(Locale.ROOT)) {
                    "channel", "feed" -> inChannel = true
                    "item", "entry" -> {
                        inItem = true
                        title = ""; guid = ""; pub = ""; duration = ""; enclosure = ""; size = 0L
                    }
                    "title" -> {
                        val t = nextText(p)
                        if (inItem) title = t else if (inChannel && showTitle == "Podcast") showTitle = t.ifBlank { showTitle }
                    }
                    "guid", "id" -> if (inItem) guid = nextText(p)
                    "pubdate", "published", "updated" -> if (inItem) pub = nextText(p)
                    "duration" -> if (inItem) duration = nextText(p)
                    "enclosure" -> if (inItem) {
                        val url = p.getAttributeValue(null, "url").orEmpty()
                        val typ = p.getAttributeValue(null, "type").orEmpty().lowercase(Locale.ROOT)
                        if (url.isNotBlank() && (typ.startsWith("audio") || typ.startsWith("video") ||
                                url.contains(Regex("""\.(mp3|m4a|aac|ogg|opus|wav|flac)(\?|$)""", RegexOption.IGNORE_CASE)))
                        ) {
                            enclosure = url
                            size = p.getAttributeValue(null, "length")?.toLongOrNull() ?: 0L
                        }
                    }
                    "link" -> if (inItem && enclosure.isEmpty()) {
                        val href = p.getAttributeValue(null, "href").orEmpty()
                        val rel = p.getAttributeValue(null, "rel").orEmpty()
                        val typ = p.getAttributeValue(null, "type").orEmpty()
                        if (href.isNotBlank() && (rel == "enclosure" || typ.startsWith("audio"))) enclosure = href
                    }
                }
                XmlPullParser.END_TAG -> when (name.lowercase(Locale.ROOT)) {
                    "item", "entry" -> {
                        flushItem()
                        inItem = false
                    }
                }
            }
            event = p.next()
        }
        return showTitle to episodes
    }

    private fun nextText(p: XmlPullParser): String = try {
        p.nextText()?.trim().orEmpty()
    } catch (_: Exception) {
        ""
    }

    private fun linkMap(arr: JSONArray?): Map<String, String> {
        if (arr == null) return emptyMap()
        val out = mutableMapOf<String, String>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val n = o.optString("name").trim().lowercase(Locale.ROOT)
            val u = o.optString("url").trim()
            if (n.isNotBlank() && u.isNotBlank()) out[n] = u
        }
        return out
    }
}
