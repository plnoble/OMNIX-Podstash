package com.omnix.podstash.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.omnix.podstash.AppViewModel
import com.omnix.podstash.BuildConfig
import com.omnix.podstash.data.DlStatus
import com.omnix.podstash.data.Episode
import com.omnix.podstash.data.QueueItem
import com.omnix.podstash.data.Show
import kotlin.math.max

private val Bg = Color(0xFF0F1115)
private val Surface = Color(0xFF171A21)
private val Soft = Color(0xFF1E2330)
private val Accent = Color(0xFF6C8CFF)
private val Ok = Color(0xFF3ECF8E)
private val Muted = Color(0xFF8B95A8)
private val TextC = Color(0xFFE8ECF4)
private val Warn = Color(0xFFF0B429)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PodstashRoot(
    vm: AppViewModel,
    onPickFolder: () -> Unit = {},
    onImportOpml: () -> Unit = {},
    onExportOpml: () -> Unit = {},
    onOpenFolder: () -> Unit = {},
) {
    val state by vm.ui.collectAsState()
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(state.toast) {
        if (state.toast.isNotBlank()) {
            snack.showSnackbar(state.toast)
            vm.toastConsumed()
        }
    }
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = Bg,
            surface = Surface,
            primary = Accent,
            onPrimary = Color(0xFF0B1020),
            onBackground = TextC,
            onSurface = TextC,
        ),
    ) {
        Box(Modifier.fillMaxSize()) {
            Scaffold(
                containerColor = Bg,
                snackbarHost = { SnackbarHost(snack) },
                bottomBar = {
                    Column {
                        state.playing?.let { MiniPlayer(vm, it, state.playingShow, state.playerPlaying) }
                        NavigationBar(containerColor = Surface) {
                            NavigationBarItem(
                                selected = state.tab == 0,
                                onClick = { vm.setTab(0) },
                                icon = { Icon(Icons.Default.LibraryMusic, null) },
                                label = { Text("库") },
                                colors = navColors(),
                            )
                            NavigationBarItem(
                                selected = state.tab == 1 || state.tab == 3,
                                onClick = { vm.setTab(1) },
                                icon = { Icon(Icons.Default.Explore, null) },
                                label = { Text("发现") },
                                colors = navColors(),
                            )
                            NavigationBarItem(
                                selected = state.tab == 2,
                                onClick = { vm.setTab(2) },
                                icon = { Icon(Icons.Default.Settings, null) },
                                label = { Text("设置") },
                                colors = navColors(),
                            )
                        }
                    }
                },
            ) { pad ->
                Box(Modifier.fillMaxSize().padding(pad)) {
                    when (state.tab) {
                        0 -> LibraryPane(vm)
                        1 -> DiscoverPane(vm)
                        2 -> SettingsPane(vm, onPickFolder, onImportOpml, onExportOpml, onOpenFolder)
                        3 -> ShowPane(vm)
                    }
                    if (state.loading.isNotBlank()) {
                        Row(
                            Modifier.align(Alignment.TopCenter).padding(12.dp)
                                .clip(RoundedCornerShape(20.dp)).background(Soft).padding(12.dp, 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(Modifier.size(16.dp), color = Accent, strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(state.loading, color = Muted, fontSize = 13.sp)
                        }
                    }
                }
            }
            if (state.playerOpen) PlayerSheet(vm)
        }
        state.update?.let { info ->
            AlertDialog(
                onDismissRequest = { vm.dismissUpdate() },
                title = { Text("新版本 ${info.version}") },
                text = {
                    Column {
                        Text(info.notes.ifBlank { "GitHub 上有新的安装包。" })
                        if (state.updateBusy.isNotBlank()) {
                            Spacer(Modifier.height(8.dp))
                            LinearProgressIndicator(Modifier.fillMaxWidth(), color = Accent)
                            Text(state.updateBusy, color = Muted, fontSize = 12.sp)
                        }
                    }
                },
                confirmButton = {
                    Button(onClick = { vm.applyUpdate() }, enabled = state.updateBusy.isBlank()) {
                        Text("下载并安装")
                    }
                },
                dismissButton = { TextButton(onClick = { vm.dismissUpdate() }) { Text("稍后") } },
                containerColor = Surface,
            )
        }
    }
}

@Composable
private fun navColors() = NavigationBarItemDefaults.colors(
    selectedIconColor = Accent,
    selectedTextColor = Accent,
    unselectedIconColor = Muted,
    unselectedTextColor = Muted,
    indicatorColor = Soft,
)

@Composable
private fun LibraryPane(vm: AppViewModel) {
    val state by vm.ui.collectAsState()
    val subs = vm.subscribed
    val last = vm.lastPlayed
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text("OMNIX-Podstash", color = TextC, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text("私人播客库 · 已关注 ${subs.size} 档", color = Muted, fontSize = 13.sp)
        }
        if (last != null) {
            item {
                Text("继续听", color = TextC, fontWeight = FontWeight.SemiBold)
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Soft)
                        .clickable { vm.continueLast() }.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AsyncImage(
                        model = last.show.artwork,
                        contentDescription = null,
                        modifier = Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)).background(Bg),
                        contentScale = ContentScale.Crop,
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(last.episode.title, color = TextC, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(last.show.name, color = Muted, fontSize = 12.sp, maxLines = 1)
                    }
                    Icon(Icons.Default.PlayArrow, null, tint = Accent)
                }
            }
        }
        val q = state.queue.filter { it.status in setOf(DlStatus.queued, DlStatus.running, DlStatus.paused, DlStatus.error) }
        if (q.isNotEmpty()) {
            item { QueueBar(vm, q, state.downloadsPaused) }
        }
        if (subs.isEmpty()) {
            item {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Soft).padding(14.dp)) {
                    Text("第一次用，按这三步", color = TextC, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    Text("1. 到「发现」搜索常听的节目并点开", color = Muted, fontSize = 13.sp)
                    Text("2. 点星标关注，再点「检测已有」", color = Muted, fontSize = 13.sp)
                    Text("3. 设置里打开「定期自动扫描」", color = Muted, fontSize = 13.sp)
                    Spacer(Modifier.height(10.dp))
                    Button(onClick = { vm.setTab(1) }, colors = ButtonDefaults.buttonColors(containerColor = Accent)) {
                        Text("去发现")
                    }
                }
            }
        } else {
            item { Text("已关注", color = TextC, fontWeight = FontWeight.SemiBold) }
        }
        items(subs, key = { it.id + it.feedUrl }) { s ->
            ShowCard(s) { vm.openShow(s.feedUrl.ifBlank { s.id }, s) }
        }
    }
}

@Composable
private fun QueueBar(vm: AppViewModel, q: List<QueueItem>, paused: Boolean) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Soft).padding(12.dp)) {
        val run = q.count { it.status == DlStatus.running }
        val wait = q.count { it.status == DlStatus.queued || it.status == DlStatus.paused }
        val err = q.count { it.status == DlStatus.error }
        Text(
            "下载队列 · 进行中 $run · 等待 $wait" + if (err > 0) " · 失败 $err" else "",
            color = TextC,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (paused) {
                Button(onClick = { vm.resumeDownloads() }, colors = ButtonDefaults.buttonColors(containerColor = Accent)) {
                    Text("继续下载")
                }
            } else {
                Button(onClick = { vm.pauseDownloads() }, colors = ButtonDefaults.buttonColors(containerColor = Soft)) {
                    Text("暂停下载")
                }
            }
            if (err > 0) {
                TextButton(onClick = { vm.retryFailed() }) { Text("重试失败", color = Accent) }
            }
        }
        q.take(4).forEach { item ->
            val label = when (item.status) {
                DlStatus.running -> "下载中"
                DlStatus.paused -> "已暂停"
                DlStatus.queued -> "等待"
                DlStatus.error -> "失败"
                else -> item.status.name
            }
            Text("${item.episode.title.take(28)} · $label", color = Muted, fontSize = 11.sp, maxLines = 1)
            if (item.bytesTotal > 0 && item.status == DlStatus.running) {
                LinearProgressIndicator(
                    progress = { (item.bytesDone.toFloat() / item.bytesTotal).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    color = Accent,
                )
            }
        }
    }
}

@Composable
private fun DiscoverPane(vm: AppViewModel) {
    val state by vm.ui.collectAsState()
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text("发现", color = TextC, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = state.query,
                onValueChange = vm::setQuery,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("搜索节目，例如 知行小酒馆 / NPR") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { vm.search() }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Accent,
                    unfocusedBorderColor = Soft,
                    focusedTextColor = TextC,
                    unfocusedTextColor = TextC,
                ),
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.search() }, colors = ButtonDefaults.buttonColors(containerColor = Accent)) {
                    Text("搜索")
                }
                FilterChip(
                    selected = state.trendSource == "cn",
                    onClick = { vm.loadTrending("cn") },
                    label = { Text("中文热门") },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Soft, selectedLabelColor = Accent),
                )
                FilterChip(
                    selected = state.trendSource == "apple",
                    onClick = { vm.loadTrending("apple") },
                    label = { Text("International") },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Soft, selectedLabelColor = Accent),
                )
            }
        }
        if (state.search.isNotEmpty()) {
            item { Text("搜索结果", color = TextC, fontWeight = FontWeight.SemiBold) }
            items(state.search, key = { "s" + it.id + it.feedUrl + it.name }) { s ->
                ShowCard(s) { vm.openShow(s.feedUrl.ifBlank { s.id }, s) }
            }
        }
        item { Text("热门", color = TextC, fontWeight = FontWeight.SemiBold) }
        items(state.trending, key = { "t" + it.id + it.feedUrl + it.rank }) { s ->
            ShowCard(s, rank = s.rank) { vm.openShow(s.feedUrl.ifBlank { s.id }, s) }
        }
    }
}

@Composable
private fun ShowPane(vm: AppViewModel) {
    val state by vm.ui.collectAsState()
    val show = state.current
    if (show == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("从发现里点开一档节目", color = Muted) }
        return
    }
    val localN = state.episodes.count { it.downloaded }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(
                    model = show.artwork,
                    contentDescription = null,
                    modifier = Modifier.size(72.dp).clip(RoundedCornerShape(10.dp)).background(Soft),
                    contentScale = ContentScale.Crop,
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(show.name, color = TextC, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 2)
                    Text("${show.author} · ${state.episodes.size} 集 · 本地 $localN", color = Muted, fontSize = 12.sp)
                }
                Icon(
                    if (show.subscribed) Icons.Default.Star else Icons.Outlined.StarOutline,
                    contentDescription = "关注",
                    tint = if (show.subscribed) Accent else Muted,
                    modifier = Modifier.size(28.dp).clickable { vm.toggleSubscribe() },
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = { vm.downloadUndownloaded() }, colors = ButtonDefaults.buttonColors(containerColor = Accent)) {
                    Text("下载未有")
                }
                Button(
                    onClick = { if (state.selectMode) vm.enqueueSelected() else vm.setSelectMode(true) },
                    colors = ButtonDefaults.buttonColors(containerColor = Soft),
                ) {
                    Text(if (state.selectMode) "下载已选 ${state.selected.size}" else "多选下载")
                }
                TextButton(onClick = { vm.scanCurrent() }) { Text("检测已有", color = Accent) }
            }
            if (state.selectMode) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(onClick = { vm.selectAllVisible() }) { Text("全选", color = Accent) }
                    TextButton(onClick = { vm.clearSelected() }) { Text("清空", color = Muted) }
                    TextButton(onClick = { vm.setSelectMode(false) }) { Text("取消多选", color = Muted) }
                }
            }
            val q = state.queue.filter { it.status in setOf(DlStatus.queued, DlStatus.running, DlStatus.paused, DlStatus.error) }
            if (q.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                QueueBar(vm, q, state.downloadsPaused)
            }
            TextButton(onClick = { vm.setTab(1) }) { Text("返回发现", color = Muted) }
            if (state.loading.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Bg).padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(Modifier.size(16.dp), color = Accent, strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(state.loading, color = Accent, fontSize = 13.sp)
                }
            }
        }
        items(state.episodes, key = { it.guid.ifBlank { it.audioUrl + it.index } }) { ep ->
            val k = ep.guid.ifBlank { ep.audioUrl + ep.index }
            EpisodeRow(
                ep = ep,
                selectMode = state.selectMode,
                selected = k in state.selected,
                onToggleSelect = { vm.toggleSelect(ep) },
                onPlay = { vm.play(ep) },
                onDownload = { vm.download(ep) },
            )
        }
    }
}

@Composable
private fun SettingsPane(
    vm: AppViewModel,
    onPickFolder: () -> Unit,
    onImportOpml: () -> Unit,
    onExportOpml: () -> Unit,
    onOpenFolder: () -> Unit,
) {
    Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
        Text("设置", color = TextC, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Text("版本 ${BuildConfig.VERSION_NAME}  (${BuildConfig.VERSION_CODE})", color = TextC)
        Text("GitHub plnoble/OMNIX-Podstash", color = Muted, fontSize = 13.sp)
        Spacer(Modifier.height(20.dp))
        Text("下载目录", color = TextC, fontWeight = FontWeight.SemiBold)
        Text(vm.libraryPath, color = Muted, fontSize = 12.sp)
        if (vm.pickedFolder) {
            Text("已选择系统文件夹：下载完成后会再复制一份；里面已有的音频也会被识别为已下载。", color = Ok, fontSize = 12.sp)
        } else {
            Text("选择你以前存播客的文件夹后，打开节目即可把已有文件标成已下载。", color = Muted, fontSize = 12.sp)
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onPickFolder, colors = ButtonDefaults.buttonColors(containerColor = Accent)) {
                Icon(Icons.Default.Folder, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("选择文件夹")
            }
            TextButton(onClick = onOpenFolder) { Text("打开目录", color = Accent) }
        }
        Spacer(Modifier.height(8.dp))
        Button(onClick = { vm.scanCurrent() }, colors = ButtonDefaults.buttonColors(containerColor = Soft)) {
            Text("检测已有文件")
        }
        Text("按单集标题匹配文件名（支持「001 标题」「日期 标题」「节目名 - 标题」）。识别到的会标成已下载，不会再下一次。", color = Muted, fontSize = 12.sp)
        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("仅 Wi-Fi 下载", color = TextC)
                Text("流量网络时排队，不自动下", color = Muted, fontSize = 12.sp)
            }
            Switch(checked = vm.wifiOnly, onCheckedChange = vm::setWifiOnly)
        }
        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("定期自动扫描", color = TextC)
                Text("按间隔检查已关注节目，补下还未下载的单集。已有文件会跳过。建议同时打开仅 Wi-Fi。", color = Muted, fontSize = 12.sp)
            }
            Switch(checked = vm.autoScan, onCheckedChange = vm::setAutoScan)
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(1 to "每天", 7 to "每周", 14 to "每两周").forEach { (d, label) ->
                FilterChip(
                    selected = vm.autoScanDays == d,
                    onClick = { vm.setAutoScanDays(d) },
                    label = { Text(label) },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Soft, selectedLabelColor = Accent),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(10, 30, 50, 0).forEach { n ->
                FilterChip(
                    selected = vm.autoScanLimit == n,
                    onClick = { vm.setAutoScanLimit(n) },
                    label = { Text(if (n == 0) "不限制" else "每次 $n 集") },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Soft, selectedLabelColor = Accent),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Button(onClick = { vm.runAutoScanNow() }, colors = ButtonDefaults.buttonColors(containerColor = Soft)) {
            Text("立即扫描一次")
        }
        if (vm.lastAutoScanMessage.isNotBlank() || vm.lastAutoScanAt > 0) {
            val whenTxt = if (vm.lastAutoScanAt > 0) {
                java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
                    .format(java.util.Date(vm.lastAutoScanAt))
            } else ""
            Text(
                listOf(whenTxt, vm.lastAutoScanMessage).filter { it.isNotBlank() }.joinToString(" · "),
                color = Muted,
                fontSize = 12.sp,
            )
        }
        Spacer(Modifier.height(20.dp))
        Text("订阅 OPML", color = TextC, fontWeight = FontWeight.SemiBold)
        Text("从苹果播客、AntennaPod 等导入关注列表；导入后不会整档下载。", color = Muted, fontSize = 12.sp)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onImportOpml, colors = ButtonDefaults.buttonColors(containerColor = Soft)) { Text("导入 OPML") }
            Button(onClick = onExportOpml, colors = ButtonDefaults.buttonColors(containerColor = Soft)) { Text("导出 OPML") }
        }
        Spacer(Modifier.height(20.dp))
        Button(onClick = { vm.checkUpdate() }, colors = ButtonDefaults.buttonColors(containerColor = Accent)) {
            Text("检查更新")
        }
        Spacer(Modifier.height(24.dp))
        Text("应用目录始终可播。若选择了系统文件夹，下完后会再复制一份进去。个人备份请遵守节目版权。", color = Muted, fontSize = 12.sp)
    }
}

@Composable
private fun ShowCard(show: Show, rank: Int = 0, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Soft).clickable(onClick = onClick).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = show.artwork,
            contentDescription = null,
            modifier = Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)).background(Bg),
            contentScale = ContentScale.Crop,
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                (if (rank > 0) "#$rank " else "") + show.name,
                color = TextC,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOf(show.author, if (show.episodeCount > 0) "${show.episodeCount} 集" else "").filter { it.isNotBlank() }.joinToString(" · "),
                color = Muted,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun EpisodeRow(
    ep: Episode,
    selectMode: Boolean,
    selected: Boolean,
    onToggleSelect: () -> Unit,
    onPlay: () -> Unit,
    onDownload: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Soft).padding(10.dp)
            .clickable { if (selectMode) onToggleSelect() else onPlay() },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selectMode) {
            Checkbox(checked = selected, onCheckedChange = { onToggleSelect() })
        }
        Column(Modifier.weight(1f)) {
            Text(ep.title, color = if (ep.downloaded) Muted else TextC, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                listOf(ep.published.ifBlank { "日期未知" }, ep.duration, if (ep.partial) "未下完" else "").filter { it.isNotBlank() }.joinToString(" · "),
                color = if (ep.partial) Warn else Muted,
                fontSize = 12.sp,
            )
        }
        if (!selectMode) {
            if (ep.downloaded) {
                Icon(Icons.Default.Check, null, tint = Ok, modifier = Modifier.size(22.dp).clickable(onClick = onPlay))
            } else {
                Icon(Icons.Default.Download, null, tint = Accent, modifier = Modifier.size(22.dp).clickable(onClick = onDownload))
            }
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Default.PlayArrow, null, tint = TextC, modifier = Modifier.size(26.dp).clickable(onClick = onPlay))
        }
    }
}

@Composable
private fun MiniPlayer(vm: AppViewModel, ep: Episode, show: Show?, playing: Boolean) {
    val state by vm.ui.collectAsState()
    Row(
        Modifier.fillMaxWidth().background(Soft).clickable { vm.openPlayer(true) }.padding(12.dp, 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
            null,
            tint = Accent,
            modifier = Modifier.clickable { vm.togglePlayPause() },
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(ep.title, color = TextC, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp)
            Text(show?.name.orEmpty(), color = Muted, fontSize = 11.sp, maxLines = 1)
            if (state.playerDur > 0) {
                LinearProgressIndicator(
                    progress = { (state.playerPos.toFloat() / state.playerDur).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    color = Accent,
                )
            }
        }
    }
}

@Composable
private fun PlayerSheet(vm: AppViewModel) {
    val state by vm.ui.collectAsState()
    val ep = state.playing ?: return
    val show = state.playingShow
    Column(
        Modifier.fillMaxSize().background(Bg).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Icon(Icons.Default.Close, null, tint = TextC, modifier = Modifier.clickable { vm.openPlayer(false) })
            Text("正在播放", color = Muted, fontSize = 13.sp)
            Spacer(Modifier.width(24.dp))
        }
        Spacer(Modifier.height(24.dp))
        AsyncImage(
            model = show?.artwork,
            contentDescription = null,
            modifier = Modifier.size(240.dp).clip(RoundedCornerShape(16.dp)).background(Soft),
            contentScale = ContentScale.Crop,
        )
        Spacer(Modifier.height(20.dp))
        Text(ep.title, color = TextC, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Text(show?.name.orEmpty(), color = Muted, fontSize = 14.sp)
        Spacer(Modifier.height(16.dp))
        val dur = max(state.playerDur, 1L)
        Slider(
            value = (state.playerPos.toFloat() / dur).coerceIn(0f, 1f),
            onValueChange = { vm.seekTo((it * dur).toLong()) },
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(fmtTime(state.playerPos), color = Muted, fontSize = 12.sp)
            Text(fmtTime(state.playerDur), color = Muted, fontSize = 12.sp)
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            Icon(Icons.Default.Replay10, null, tint = TextC, modifier = Modifier.size(36.dp).clickable { vm.skipBy(-10_000) })
            Icon(
                if (state.playerPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                null,
                tint = Accent,
                modifier = Modifier.size(56.dp).clickable { vm.togglePlayPause() },
            )
            Icon(Icons.Default.Forward30, null, tint = TextC, modifier = Modifier.size(36.dp).clickable { vm.skipBy(30_000) })
        }
        Spacer(Modifier.height(16.dp))
        Text("倍速", color = Muted, fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(0.8f, 1f, 1.2f, 1.5f, 2f).forEach { s ->
                FilterChip(
                    selected = kotlin.math.abs(vm.speed - s) < 0.01f,
                    onClick = { vm.setSpeed(s) },
                    label = { Text("${s}x") },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Soft, selectedLabelColor = Accent),
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            if (state.sleepLeftMs > 0) "睡眠剩余 ${fmtTime(state.sleepLeftMs)}" else "睡眠定时",
            color = Muted,
            fontSize = 12.sp,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(15, 30, 45, 60).forEach { m ->
                TextButton(onClick = { vm.setSleepMinutes(m) }) { Text("${m}分", color = Accent) }
            }
            TextButton(onClick = { vm.setSleepMinutes(0) }) { Text("关", color = Muted) }
        }
    }
}

private fun fmtTime(ms: Long): String {
    if (ms <= 0) return "0:00"
    val s = (ms / 1000).toInt()
    val m = s / 60
    val r = s % 60
    return if (m >= 60) "%d:%02d:%02d".format(m / 60, m % 60, r) else "%d:%02d".format(m, r)
}
