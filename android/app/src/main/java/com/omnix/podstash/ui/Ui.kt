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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import com.omnix.podstash.data.Episode
import com.omnix.podstash.data.Show

private val Bg = Color(0xFF0F1115)
private val Surface = Color(0xFF171A21)
private val Soft = Color(0xFF1E2330)
private val Accent = Color(0xFF6C8CFF)
private val Ok = Color(0xFF3ECF8E)
private val Muted = Color(0xFF8B95A8)
private val TextC = Color(0xFFE8ECF4)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PodstashRoot(vm: AppViewModel) {
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
        Scaffold(
            containerColor = Bg,
            snackbarHost = { SnackbarHost(snack) },
            bottomBar = {
                Column {
                    state.playing?.let { MiniPlayer(it, state.playingShow) }
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
                    2 -> SettingsPane(vm)
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
    val subs = vm.subscribed
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text("OMNIX-Podstash", color = TextC, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text("私人播客库 · 已关注 ${subs.size} 档", color = Muted, fontSize = 13.sp)
        }
        if (subs.isEmpty()) {
            item { Text("还没有关注的节目。到「发现」里点进一档，再点星标关注。打开应用会自动下新集，不会重复下载。", color = Muted) }
        }
        items(subs, key = { it.id + it.feedUrl }) { s ->
            ShowCard(s) { vm.openShow(s.feedUrl.ifBlank { s.id }, s) }
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.downloadUndownloaded() }, colors = ButtonDefaults.buttonColors(containerColor = Accent)) {
                    Text("下载未有")
                }
                TextButton(onClick = { vm.setTab(1) }) { Text("返回发现", color = Muted) }
            }
        }
        items(state.episodes, key = { it.guid.ifBlank { it.audioUrl + it.index } }) { ep ->
            EpisodeRow(ep, onPlay = { vm.play(ep) }, onDownload = { vm.download(ep) })
        }
    }
}

@Composable
private fun SettingsPane(vm: AppViewModel) {
    Column(Modifier.padding(16.dp)) {
        Text("设置", color = TextC, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Text("版本 ${BuildConfig.VERSION_NAME}  (${BuildConfig.VERSION_CODE})", color = TextC)
        Text("GitHub plnoble/OMNIX-Podstash", color = Muted, fontSize = 13.sp)
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("仅 Wi-Fi 下载", color = TextC)
                Text("关注节目后，打开应用会检查新集并自动下载", color = Muted, fontSize = 12.sp)
            }
            Switch(checked = vm.wifiOnly, onCheckedChange = vm::setWifiOnly)
        }
        Spacer(Modifier.height(16.dp))
        Button(onClick = { vm.checkUpdate() }, colors = ButtonDefaults.buttonColors(containerColor = Accent)) {
            Text("检查更新")
        }
        Spacer(Modifier.height(24.dp))
        Text("音频保存在应用专属目录，不经过任何中转站。已有完整文件会跳过。个人备份请遵守节目版权。", color = Muted, fontSize = 12.sp)
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
private fun EpisodeRow(ep: Episode, onPlay: () -> Unit, onDownload: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Soft).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).clickable(onClick = onPlay)) {
            Text(ep.title, color = if (ep.downloaded) Muted else TextC, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                listOf(ep.published.ifBlank { "日期未知" }, ep.duration).filter { it.isNotBlank() }.joinToString(" · "),
                color = Muted,
                fontSize = 12.sp,
            )
        }
        if (ep.downloaded) {
            Icon(Icons.Default.Check, null, tint = Ok, modifier = Modifier.size(22.dp).clickable(onClick = onPlay))
        } else {
            Icon(Icons.Default.Download, null, tint = Accent, modifier = Modifier.size(22.dp).clickable(onClick = onDownload))
        }
        Spacer(Modifier.width(8.dp))
        Icon(Icons.Default.PlayArrow, null, tint = TextC, modifier = Modifier.size(26.dp).clickable(onClick = onPlay))
    }
}

@Composable
private fun MiniPlayer(ep: Episode, show: Show?) {
    Row(
        Modifier.fillMaxWidth().background(Soft).padding(12.dp, 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.PlayArrow, null, tint = Accent)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(ep.title, color = TextC, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp)
            Text(show?.name.orEmpty(), color = Muted, fontSize = 11.sp, maxLines = 1)
        }
    }
}
