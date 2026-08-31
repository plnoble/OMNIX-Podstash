package com.omnix.podstash

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import com.omnix.podstash.data.Catalog
import com.omnix.podstash.data.Downloader
import com.omnix.podstash.data.Episode
import com.omnix.podstash.data.Show
import com.omnix.podstash.data.Store
import com.omnix.podstash.data.UpdateChecker
import com.omnix.podstash.data.UpdateInfo
import com.omnix.podstash.data.key
import com.omnix.podstash.playback.PlaybackService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class UiState(
    val tab: Int = 0,
    val query: String = "",
    val country: String = "CN",
    val trendSource: String = "cn",
    val trending: List<Show> = emptyList(),
    val search: List<Show> = emptyList(),
    val current: Show? = null,
    val episodes: List<Episode> = emptyList(),
    val loading: String = "",
    val toast: String = "",
    val playing: Episode? = null,
    val playingShow: Show? = null,
    val update: UpdateInfo? = null,
    val updateBusy: String = "",
)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val store: Store = (app as PodstashApp).store
    private val downloader = Downloader(app, store)

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui

    val subscribed: List<Show> get() = store.subscribed()
    val wifiOnly: Boolean get() = store.wifiOnly
    val speed: Float get() = store.speed

    private var refreshJob: Job? = null

    fun setTab(i: Int) {
        _ui.value = _ui.value.copy(tab = i)
    }

    fun setQuery(q: String) {
        _ui.value = _ui.value.copy(query = q)
    }

    fun toastConsumed() {
        _ui.value = _ui.value.copy(toast = "")
    }

    fun dismissUpdate() {
        _ui.value.update?.versionCode?.let { store.setSkippedVersion(it) }
        _ui.value = _ui.value.copy(update = null)
    }

    fun loadTrending(source: String = _ui.value.trendSource) {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(loading = "加载热门…", trendSource = source)
            try {
                val (_, shows) = withContext(Dispatchers.IO) { Catalog.trending(source) }
                _ui.value = _ui.value.copy(trending = shows, loading = "")
            } catch (e: Exception) {
                _ui.value = _ui.value.copy(loading = "", toast = e.message ?: "热门加载失败")
            }
        }
    }

    fun search() {
        val q = _ui.value.query.trim()
        if (q.isEmpty()) {
            _ui.value = _ui.value.copy(toast = "请输入关键词")
            return
        }
        viewModelScope.launch {
            _ui.value = _ui.value.copy(loading = "搜索中…")
            try {
                val shows = withContext(Dispatchers.IO) { Catalog.search(q, _ui.value.country) }
                _ui.value = _ui.value.copy(search = shows, loading = "", toast = "找到 ${shows.size} 个节目")
            } catch (e: Exception) {
                _ui.value = _ui.value.copy(loading = "", toast = e.message ?: "搜索失败")
            }
        }
    }

    fun openShow(source: String, preview: Show? = null) {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(loading = "加载全集…")
            try {
                val (show, eps) = withContext(Dispatchers.IO) { Catalog.resolve(source, _ui.value.country) }
                val merged = show.copy(
                    artwork = show.artwork.ifBlank { preview?.artwork.orEmpty() },
                    name = if (show.name.isBlank() || show.name == "Podcast") preview?.name ?: show.name else show.name,
                    subscribed = store.shows.any { it.key() == show.key() && it.subscribed },
                    lastSeenGuid = store.shows.find { it.key() == show.key() }?.lastSeenGuid.orEmpty(),
                )
                val marked = eps.map { store.localStatus(merged, it) }
                store.upsertShow(merged)
                _ui.value = _ui.value.copy(current = merged, episodes = marked, loading = "", tab = 3)
            } catch (e: Exception) {
                _ui.value = _ui.value.copy(loading = "", toast = e.message ?: "解析失败")
            }
        }
    }

    fun toggleSubscribe() {
        val show = _ui.value.current ?: return
        val next = !show.subscribed
        val newest = _ui.value.episodes.firstOrNull()?.guid.orEmpty()
        val updated = show.copy(subscribed = next, lastSeenGuid = if (next) newest else show.lastSeenGuid)
        store.setSubscribed(updated, next)
        if (next) store.setLastSeen(updated, newest)
        _ui.value = _ui.value.copy(
            current = updated,
            toast = if (next) "已关注。下次打开会自动下新集（不会重下整档）" else "已取消关注",
        )
    }

    fun download(ep: Episode, force: Boolean = false) {
        val show = _ui.value.current ?: return
        viewModelScope.launch {
            try {
                val done = downloader.download(show, ep, force)
                _ui.value = _ui.value.copy(
                    episodes = _ui.value.episodes.map { if (it.guid == ep.guid) done else it },
                    toast = if (done.downloaded) "已在库中：${ep.title}" else "下载未完成",
                )
            } catch (e: Exception) {
                _ui.value = _ui.value.copy(toast = e.message ?: "下载失败")
            }
        }
    }

    fun downloadUndownloaded() {
        val show = _ui.value.current ?: return
        val pending = _ui.value.episodes.filter { !it.downloaded }
        if (pending.isEmpty()) {
            _ui.value = _ui.value.copy(toast = "全部已在库中")
            return
        }
        viewModelScope.launch {
            var n = 0
            for (ep in pending) {
                try {
                    val done = downloader.download(show, ep)
                    if (done.downloaded) n++
                    _ui.value = _ui.value.copy(
                        episodes = _ui.value.episodes.map { if (it.guid == ep.guid) done else it },
                    )
                } catch (e: Exception) {
                    _ui.value = _ui.value.copy(toast = e.message ?: "下载失败")
                    break
                }
            }
            _ui.value = _ui.value.copy(toast = "新下 $n 集，其余已跳过")
        }
    }

    fun play(ep: Episode) {
        val show = _ui.value.current ?: return
        val path = store.localStatus(show, ep).localPath
        val uri = if (path.isNotBlank()) Uri.fromFile(java.io.File(path)) else Uri.parse(ep.audioUrl)
        val app = getApplication<PodstashApp>()
        app.startForegroundService(Intent(app, PlaybackService::class.java))
        val player = app.player
        val item = MediaItem.Builder()
            .setUri(uri)
            .setMediaId(ep.guid.ifBlank { ep.audioUrl })
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(ep.title)
                    .setArtist(show.name)
                    .build(),
            )
            .build()
        player.setMediaItem(item)
        player.playbackParameters = player.playbackParameters.withSpeed(store.speed)
        val pos = store.positions[ep.guid] ?: 0L
        player.prepare()
        if (pos > 0) player.seekTo(pos)
        player.play()
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!isPlaying) store.setPosition(ep.guid, player.currentPosition)
            }
        })
        _ui.value = _ui.value.copy(playing = ep, playingShow = show)
    }

    fun setSpeed(v: Float) {
        store.setSpeed(v)
        getApplication<PodstashApp>().player.let { it.playbackParameters = it.playbackParameters.withSpeed(v) }
    }

    fun setWifiOnly(v: Boolean) {
        store.setWifiOnly(v)
        _ui.value = _ui.value.copy(toast = if (v) "仅 Wi-Fi 下载" else "允许流量下载")
    }

    fun refreshSubscriptionsOnOpen() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            val subs = store.subscribed()
            if (subs.isEmpty()) return@launch
            _ui.value = _ui.value.copy(loading = "检查订阅新集…")
            var added = 0
            for (show in subs) {
                try {
                    val (fresh, eps) = withContext(Dispatchers.IO) { Catalog.fetchRss(show) }
                    val marked = eps.map { store.localStatus(fresh, it) }
                    val newest = marked.firstOrNull()?.guid.orEmpty()
                    val cursor = show.lastSeenGuid
                    val news = if (cursor.isBlank()) emptyList() else {
                        val idx = marked.indexOfFirst { it.guid == cursor }
                        when {
                            idx == 0 -> emptyList()
                            idx < 0 -> marked.filter { !it.downloaded }.take(3)
                            else -> marked.take(idx).filter { !it.downloaded }
                        }
                    }
                    for (ep in news) {
                        try {
                            downloader.download(fresh, ep)
                            added++
                        } catch (_: Exception) {
                        }
                    }
                    if (newest.isNotBlank()) store.setLastSeen(fresh.copy(subscribed = true), newest)
                } catch (_: Exception) {
                }
            }
            _ui.value = _ui.value.copy(
                loading = "",
                toast = if (added > 0) "订阅更新：新下 $added 集" else "订阅已是最新",
            )
        }
    }

    fun checkUpdate() {
        viewModelScope.launch {
            try {
                val code = BuildConfig.VERSION_CODE
                val info = withContext(Dispatchers.IO) { UpdateChecker.check(code) } ?: return@launch
                if (info.versionCode <= store.skippedVersionCode) return@launch
                _ui.value = _ui.value.copy(update = info)
            } catch (_: Exception) {
            }
        }
    }

    fun applyUpdate() {
        val info = _ui.value.update ?: return
        viewModelScope.launch {
            _ui.value = _ui.value.copy(updateBusy = "正在下载更新…")
            try {
                val apk = withContext(Dispatchers.IO) {
                    UpdateChecker.downloadApk(getApplication(), info) { _, _ -> }
                }
                _ui.value = _ui.value.copy(updateBusy = "", update = null)
                UpdateChecker.install(getApplication(), apk)
            } catch (e: Exception) {
                _ui.value = _ui.value.copy(updateBusy = "", toast = e.message ?: "更新失败")
            }
        }
    }
}
