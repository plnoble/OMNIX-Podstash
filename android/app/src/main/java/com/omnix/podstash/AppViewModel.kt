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
import com.omnix.podstash.data.DlStatus
import com.omnix.podstash.data.Downloader
import com.omnix.podstash.data.Episode
import com.omnix.podstash.data.LastPlayed
import com.omnix.podstash.data.Opml
import com.omnix.podstash.data.QueueItem
import com.omnix.podstash.data.Show
import com.omnix.podstash.data.Store
import com.omnix.podstash.data.UpdateChecker
import com.omnix.podstash.data.UpdateInfo
import com.omnix.podstash.data.key
import com.omnix.podstash.playback.PlaybackService
import com.omnix.podstash.work.AutoScanScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
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
    val selected: Set<String> = emptySet(),
    val selectMode: Boolean = false,
    val queue: List<QueueItem> = emptyList(),
    val downloadsPaused: Boolean = false,
    val loading: String = "",
    val toast: String = "",
    val playing: Episode? = null,
    val playingShow: Show? = null,
    val playerOpen: Boolean = false,
    val playerPlaying: Boolean = false,
    val playerPos: Long = 0,
    val playerDur: Long = 0,
    val sleepLeftMs: Long = 0,
    val update: UpdateInfo? = null,
    val updateBusy: String = "",
)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val store: Store = (app as PodstashApp).store
    private val downloader = Downloader(app, store)
    private val player get() = getApplication<PodstashApp>().player

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui

    val subscribed: List<Show> get() = store.subscribed()
    val wifiOnly: Boolean get() = store.wifiOnly
    val speed: Float get() = store.speed
    val libraryPath: String get() = store.libraryPath()
    val pickedFolder: Boolean get() = store.treeUri.isNotBlank()
    val lastPlayed: LastPlayed? get() = store.lastPlayed
    val autoScan: Boolean get() = store.autoScan
    val autoScanDays: Int get() = store.autoScanDays
    val autoScanLimit: Int get() = store.autoScanLimit
    val lastAutoScanAt: Long get() = store.lastAutoScan
    val lastAutoScanMessage: String get() = store.lastAutoScanMessage

    private var refreshJob: Job? = null
    private var queueJob: Job? = null
    private var tickerJob: Job? = null
    private var sleepJob: Job? = null
    private var playListener: Player.Listener? = null

    init {
        startTicker()
        store.lastPlayed?.let { lp ->
            _ui.value = _ui.value.copy(playing = lp.episode, playingShow = lp.show)
        }
    }

    fun setTab(i: Int) {
        _ui.value = _ui.value.copy(tab = i)
    }

    fun setQuery(q: String) {
        _ui.value = _ui.value.copy(query = q)
    }

    fun toastConsumed() {
        _ui.value = _ui.value.copy(toast = "")
    }

    fun userMessage(msg: String) {
        _ui.value = _ui.value.copy(toast = msg)
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
            _ui.value = _ui.value.copy(loading = "加载全集并检测本地文件…", selectMode = false, selected = emptySet())
            try {
                val (show, eps) = withContext(Dispatchers.IO) { Catalog.resolve(source, _ui.value.country) }
                val merged = show.copy(
                    artwork = show.artwork.ifBlank { preview?.artwork.orEmpty() },
                    name = if (show.name.isBlank() || show.name == "Podcast") preview?.name ?: show.name else show.name,
                    subscribed = store.shows.any { it.key() == show.key() && it.subscribed },
                    lastSeenGuid = store.shows.find { it.key() == show.key() }?.lastSeenGuid.orEmpty(),
                )
                val marked = withContext(Dispatchers.IO) { store.scanAndMark(merged, eps) }
                store.upsertShow(merged)
                val localN = marked.count { it.downloaded }
                _ui.value = _ui.value.copy(
                    current = merged,
                    episodes = marked,
                    loading = "",
                    tab = 3,
                    toast = if (localN > 0) "已加载 ${marked.size} 集 · 本地已有 $localN 集" else "",
                )
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
            toast = if (next) "已关注。下次打开会自动下新集" else "已取消关注",
        )
    }

    fun setSelectMode(on: Boolean) {
        _ui.value = _ui.value.copy(selectMode = on, selected = if (on) _ui.value.selected else emptySet())
    }

    fun toggleSelect(ep: Episode) {
        val k = epKey(ep)
        val next = _ui.value.selected.toMutableSet()
        if (!next.add(k)) next.remove(k)
        _ui.value = _ui.value.copy(selected = next, selectMode = true)
    }

    fun selectAllVisible() {
        _ui.value = _ui.value.copy(
            selectMode = true,
            selected = _ui.value.episodes.map { epKey(it) }.toSet(),
        )
    }

    fun clearSelected() {
        _ui.value = _ui.value.copy(selected = emptySet())
    }

    fun enqueueSelected() {
        val show = _ui.value.current ?: return
        val picked = _ui.value.episodes.filter { epKey(it) in _ui.value.selected }
        if (picked.isEmpty()) {
            _ui.value = _ui.value.copy(toast = "请先勾选要下载的单集")
            return
        }
        enqueue(show, picked)
        _ui.value = _ui.value.copy(selectMode = false, selected = emptySet())
    }

    fun download(ep: Episode, force: Boolean = false) {
        val show = _ui.value.current ?: return
        enqueue(show, listOf(ep), force)
    }

    fun downloadUndownloaded() {
        val show = _ui.value.current ?: return
        val pending = _ui.value.episodes.filter { !it.downloaded }
        if (pending.isEmpty()) {
            _ui.value = _ui.value.copy(toast = "全部已在库中")
            return
        }
        enqueue(show, pending)
    }

    fun pauseDownloads() {
        downloader.paused = true
        downloader.abortCurrent = true
        _ui.value = _ui.value.copy(
            downloadsPaused = true,
            queue = _ui.value.queue.map {
                if (it.status == DlStatus.running || it.status == DlStatus.queued) it.copy(status = DlStatus.paused) else it
            },
            toast = "已暂停下载，可随时继续",
        )
    }

    fun resumeDownloads() {
        downloader.paused = false
        downloader.abortCurrent = false
        _ui.value = _ui.value.copy(
            downloadsPaused = false,
            queue = _ui.value.queue.map {
                if (it.status == DlStatus.paused) it.copy(status = DlStatus.queued) else it
            },
        )
        pumpQueue()
    }

    private fun epKey(ep: Episode) = ep.guid.ifBlank { ep.audioUrl + ep.index }

    private fun enqueue(show: Show, episodes: List<Episode>, force: Boolean = false) {
        val existing = _ui.value.queue.associateBy { it.key }
        val add = episodes.mapNotNull { ep ->
            val k = epKey(ep)
            val old = existing[k]
            if (old != null && old.status in setOf(DlStatus.queued, DlStatus.running, DlStatus.paused, DlStatus.done) && !force) {
                null
            } else {
                QueueItem(k, show, ep, DlStatus.queued)
            }
        }
        if (add.isEmpty()) {
            _ui.value = _ui.value.copy(toast = "没有新的下载任务")
            return
        }
        _ui.value = _ui.value.copy(
            queue = _ui.value.queue.filterNot { q -> add.any { it.key == q.key } } + add,
            downloadsPaused = false,
            toast = "已加入 ${add.size} 集到下载队列",
        )
        downloader.paused = false
        pumpQueue()
    }

    private fun pumpQueue() {
        if (queueJob?.isActive == true) return
        queueJob = viewModelScope.launch {
            while (isActive) {
                if (downloader.paused) {
                    delay(300)
                    continue
                }
                val next = _ui.value.queue.firstOrNull { it.status == DlStatus.queued } ?: break
                patchQueue(next.key) { it.copy(status = DlStatus.running) }
                try {
                    val done = downloader.download(next.show, next.episode) { d, t ->
                        patchQueue(next.key) { it.copy(bytesDone = d, bytesTotal = t, status = DlStatus.running) }
                    }
                    patchQueue(next.key) {
                        it.copy(
                            status = if (done.downloaded) DlStatus.done else DlStatus.skipped,
                            episode = done,
                        )
                    }
                    if (_ui.value.current?.key() == next.show.key()) {
                        _ui.value = _ui.value.copy(
                            episodes = _ui.value.episodes.map { if (epKey(it) == next.key) done else it },
                        )
                    }
                } catch (e: Downloader.Paused) {
                    patchQueue(next.key) { it.copy(status = DlStatus.paused) }
                } catch (e: Exception) {
                    patchQueue(next.key) { it.copy(status = DlStatus.error, error = e.message.orEmpty()) }
                }
            }
        }
    }

    private fun patchQueue(key: String, fn: (QueueItem) -> QueueItem) {
        _ui.value = _ui.value.copy(queue = _ui.value.queue.map { if (it.key == key) fn(it) else it })
    }

    fun retryFailed() {
        _ui.value = _ui.value.copy(
            queue = _ui.value.queue.map {
                if (it.status == DlStatus.error) it.copy(status = DlStatus.queued, error = "") else it
            },
            downloadsPaused = false,
        )
        downloader.paused = false
        pumpQueue()
    }

    fun play(ep: Episode, show: Show? = _ui.value.current) {
        val s = show ?: return
        val markedLocal = store.localStatus(s, ep)
        val path = markedLocal.localPath.ifBlank { ep.localPath }
        val uri = playbackUri(path, ep.audioUrl)
        val app = getApplication<PodstashApp>()
        app.startForegroundService(Intent(app, PlaybackService::class.java))
        playListener?.let { player.removeListener(it) }
        val item = MediaItem.Builder()
            .setUri(uri)
            .setMediaId(epKey(ep))
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(ep.title)
                    .setArtist(s.name)
                    .setArtworkUri(s.artwork.takeIf { it.isNotBlank() }?.let { Uri.parse(it) })
                    .build(),
            )
            .build()
        player.setMediaItem(item)
        player.playbackParameters = player.playbackParameters.withSpeed(store.speed)
        val pos = store.positions[ep.guid] ?: 0L
        player.prepare()
        if (pos > 0) player.seekTo(pos)
        player.play()
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                persistPlayhead()
                _ui.value = _ui.value.copy(playerPlaying = isPlaying)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                persistPlayhead()
            }
        }
        playListener = listener
        player.addListener(listener)
        val marked = if (markedLocal.downloaded) markedLocal else store.localStatus(s, ep)
        store.setLastPlayed(LastPlayed(ep.guid, s, marked, pos))
        _ui.value = _ui.value.copy(
            playing = marked,
            playingShow = s,
            playerPlaying = true,
            playerPos = pos,
        )
    }

    fun togglePlayPause() {
        if (player.isPlaying) player.pause() else player.play()
        persistPlayhead()
        _ui.value = _ui.value.copy(playerPlaying = player.isPlaying)
    }

    fun seekTo(ms: Long) {
        player.seekTo(ms.coerceAtLeast(0))
        _ui.value = _ui.value.copy(playerPos = player.currentPosition)
        persistPlayhead()
    }

    fun skipBy(ms: Long) {
        seekTo(player.currentPosition + ms)
    }

    fun openPlayer(open: Boolean) {
        _ui.value = _ui.value.copy(playerOpen = open)
    }

    fun continueLast() {
        val lp = store.lastPlayed ?: return
        play(lp.episode, lp.show)
        _ui.value = _ui.value.copy(playerOpen = true)
    }

    fun setSpeed(v: Float) {
        store.setSpeed(v)
        player.playbackParameters = player.playbackParameters.withSpeed(v)
        _ui.value = _ui.value.copy(toast = "倍速 ${v}x")
    }

    fun setSleepMinutes(m: Int) {
        sleepJob?.cancel()
        if (m <= 0) {
            _ui.value = _ui.value.copy(sleepLeftMs = 0, toast = "已关闭睡眠定时")
            return
        }
        val end = System.currentTimeMillis() + m * 60_000L
        _ui.value = _ui.value.copy(sleepLeftMs = m * 60_000L, toast = "将在 $m 分钟后暂停")
        sleepJob = viewModelScope.launch {
            while (isActive) {
                val left = end - System.currentTimeMillis()
                if (left <= 0) {
                    player.pause()
                    persistPlayhead()
                    _ui.value = _ui.value.copy(sleepLeftMs = 0, playerPlaying = false, toast = "睡眠定时：已暂停")
                    break
                }
                _ui.value = _ui.value.copy(sleepLeftMs = left)
                delay(1000)
            }
        }
    }

    fun setWifiOnly(v: Boolean) {
        store.setWifiOnly(v)
        AutoScanScheduler.ensure(getApplication())
        _ui.value = _ui.value.copy(toast = if (v) "仅 Wi-Fi 下载" else "允许流量下载")
    }

    fun setAutoScan(v: Boolean) {
        store.setAutoScan(v)
        AutoScanScheduler.ensure(getApplication())
        _ui.value = _ui.value.copy(
            toast = if (v) "已开启定期扫描：会按间隔检查关注的节目并下载未有的单集" else "已关闭定期扫描",
        )
    }

    fun setAutoScanDays(days: Int) {
        store.setAutoScanDays(days)
        AutoScanScheduler.ensure(getApplication())
        val label = when (days) {
            1 -> "每天"
            14 -> "每两周"
            else -> "每周"
        }
        _ui.value = _ui.value.copy(toast = "扫描间隔：$label")
    }

    fun setAutoScanLimit(n: Int) {
        store.setAutoScanLimit(n)
        _ui.value = _ui.value.copy(
            toast = if (n <= 0) "每档每次不限制集数" else "每档每次最多补 $n 集",
        )
    }

    fun runAutoScanNow() {
        if (store.subscribed().isEmpty()) {
            _ui.value = _ui.value.copy(toast = "请先关注节目")
            return
        }
        AutoScanScheduler.runOnce(getApplication())
        _ui.value = _ui.value.copy(toast = "已在后台开始扫描，通知栏可看进度")
    }

    fun setTreeUri(uri: String) {
        store.setTreeUri(uri)
        val show = _ui.value.current
        val eps = _ui.value.episodes
        if (show != null && eps.isNotEmpty()) {
            viewModelScope.launch { scanCurrent("正在识别文件夹里的已有文件…") }
        } else {
            _ui.value = _ui.value.copy(toast = "已选择文件夹。打开节目后会自动识别里面已经下过的音频")
        }
    }

    fun scanCurrent(loadingMsg: String = "正在检测已有文件…") {
        val show = _ui.value.current
        val eps = _ui.value.episodes
        if (show == null || eps.isEmpty()) {
            _ui.value = _ui.value.copy(toast = "请先打开一档节目，再检测该节目在文件夹里的已有文件")
            return
        }
        viewModelScope.launch {
            _ui.value = _ui.value.copy(loading = loadingMsg)
            try {
                val marked = withContext(Dispatchers.IO) { store.scanAndMark(show, eps) }
                val n = marked.count { it.downloaded }
                _ui.value = _ui.value.copy(
                    episodes = marked,
                    loading = "",
                    toast = if (n > 0) "已标记 $n 集为已下载，不会重复下载" else "没有识别到已有文件（文件名需包含单集标题）",
                )
            } catch (e: Exception) {
                _ui.value = _ui.value.copy(loading = "", toast = e.message ?: "检测失败")
            }
        }
    }

    fun importOpml(uri: Uri) {
        viewModelScope.launch {
            try {
                val text = withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openInputStream(uri)
                        ?.bufferedReader()?.readText() ?: error("无法读取文件")
                }
                val shows = Opml.parse(text)
                if (shows.isEmpty()) {
                    _ui.value = _ui.value.copy(toast = "OPML 里没有有效的 feed")
                    return@launch
                }
                shows.forEach { store.setSubscribed(it, true) }
                _ui.value = _ui.value.copy(toast = "已导入 ${shows.size} 档关注（不会整档下载）", tab = 0)
            } catch (e: Exception) {
                _ui.value = _ui.value.copy(toast = e.message ?: "导入失败")
            }
        }
    }

    fun exportOpml(uri: Uri) {
        viewModelScope.launch {
            try {
                val xml = Opml.write(store.subscribed())
                withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openOutputStream(uri)?.use {
                        it.write(xml.toByteArray(Charsets.UTF_8))
                    } ?: error("无法写入文件")
                }
                _ui.value = _ui.value.copy(toast = "已导出 ${store.subscribed().size} 档订阅")
            } catch (e: Exception) {
                _ui.value = _ui.value.copy(toast = e.message ?: "导出失败")
            }
        }
    }

    fun refreshSubscriptionsOnOpen() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            val subs = store.subscribed()
            if (subs.isEmpty()) return@launch
            _ui.value = _ui.value.copy(loading = "检查订阅新集…")
            var added = 0
            val failures = mutableListOf<String>()
            for (show in subs) {
                try {
                    val (fresh, eps) = withContext(Dispatchers.IO) { Catalog.fetchRss(show) }
                    val marked = withContext(Dispatchers.IO) { store.scanAndMark(fresh, eps) }
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
                    if (news.isNotEmpty()) enqueue(fresh.copy(subscribed = true), news)
                    added += news.size
                    if (newest.isNotBlank()) store.setLastSeen(fresh.copy(subscribed = true), newest)
                } catch (e: Exception) {
                    failures += "${show.name}：${e.message ?: "失败"}"
                }
            }
            val msg = buildString {
                append(if (added > 0) "订阅：加入 $added 集新内容" else "订阅已是最新")
                if (failures.isNotEmpty()) append("；失败 ${failures.size} 档")
            }
            _ui.value = _ui.value.copy(loading = "", toast = msg)
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

    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = viewModelScope.launch {
            while (isActive) {
                if (player.playbackState != Player.STATE_IDLE) {
                    _ui.value = _ui.value.copy(
                        playerPos = player.currentPosition,
                        playerDur = player.duration.coerceAtLeast(0),
                        playerPlaying = player.isPlaying,
                    )
                    if (player.isPlaying) persistPlayhead()
                }
                delay(500)
            }
        }
    }

    private fun playbackUri(path: String, audioUrl: String): Uri {
        if (path.startsWith("content://") || path.startsWith("file://")) return Uri.parse(path)
        if (path.isNotBlank()) {
            val f = java.io.File(path)
            if (f.exists()) return Uri.fromFile(f)
        }
        return Uri.parse(audioUrl)
    }

    private fun persistPlayhead() {
        val ep = _ui.value.playing ?: return
        val show = _ui.value.playingShow ?: return
        val pos = player.currentPosition
        store.setPosition(ep.guid, pos)
        store.setLastPlayed(LastPlayed(ep.guid, show, ep, pos))
    }

    override fun onCleared() {
        persistPlayhead()
        super.onCleared()
    }
}
