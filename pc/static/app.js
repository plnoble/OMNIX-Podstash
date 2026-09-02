/* OMNIX-Podstash PC frontend */

const EP_PAGE = 40;

const state = {
  shows: [],
  trending: [],
  library: [],
  trendSource: "cn",
  show: null,
  episodes: [],
  epLimit: EP_PAGE,
  selected: new Set(),
  jobId: null,
  jobStartedAt: 0,
  pollTimer: null,
  loadGen: 0,
  autoScanTimer: null,
  libraryLabel: "",
};

const $ = (id) => document.getElementById(id);

function toast(msg, ms = 2600) {
  const el = $("toast");
  el.textContent = msg;
  el.classList.add("show");
  clearTimeout(toast._t);
  toast._t = setTimeout(() => el.classList.remove("show"), ms);
}

async function api(path, options = {}) {
  const res = await fetch(path, {
    headers: { "Content-Type": "application/json", ...(options.headers || {}) },
    ...options,
  });
  let data = null;
  try {
    data = await res.json();
  } catch {
    data = null;
  }
  if (!res.ok) {
    const detail = data?.detail;
    const msg =
      typeof detail === "string"
        ? detail
        : Array.isArray(detail)
          ? detail.map((d) => d.msg || d).join("; ")
          : res.statusText || "请求失败";
    throw new Error(msg);
  }
  return data;
}

function fmtBytes(n) {
  if (!n || n <= 0) return "—";
  const u = ["B", "KB", "MB", "GB"];
  let i = 0;
  let v = n;
  while (v >= 1024 && i < u.length - 1) {
    v /= 1024;
    i++;
  }
  return `${v.toFixed(i ? 1 : 0)} ${u[i]}`;
}

function fmtDuration(sec) {
  sec = Math.max(0, Math.floor(sec || 0));
  if (sec < 60) return `${sec} 秒`;
  const m = Math.floor(sec / 60);
  const s = sec % 60;
  if (m < 60) return `${m} 分 ${s} 秒`;
  return `${Math.floor(m / 60)} 时 ${m % 60} 分`;
}

function fmtScanTime(ts) {
  if (!ts) return "尚未扫描";
  const d = new Date(Number(ts) * 1000);
  if (Number.isNaN(d.getTime())) return "尚未扫描";
  return d.toLocaleString();
}

function applySettings(s) {
  $("outDir").value = s.out_dir || "";
  $("concurrency").value = s.concurrency || 4;
  $("autoScan").checked = !!s.auto_scan;
  $("autoScanDays").value = String(s.auto_scan_days || 7);
  $("autoScanLimit").value = String(s.auto_scan_limit ?? 30);
  if ($("autoScanMode")) $("autoScanMode").value = s.auto_scan_mode || "new";
  if ($("autoUpgradeQuality")) $("autoUpgradeQuality").checked = !!s.auto_upgrade_quality;
  state.libraryLabel = s.library_label || "";
  const verEl = $("appVersion");
  if (verEl && s.version) {
    verEl.textContent = `v${s.version} · 本地`;
  }
  const hint = $("outDirHint");
  if (hint) {
    hint.textContent = state.libraryLabel
      ? `容器内路径 ${s.out_dir}，实际对应 ${state.libraryLabel}。打开节目后点「检测已有文件」会扫描该目录。`
      : `打开节目或点「检测已有文件」会扫描该目录。文件名包含单集标题即可识别。`;
  }
  const meta = [];
  meta.push(`关注 ${s.subscribed_count || 0} 档`);
  if (s.auto_scan) meta.push(`每 ${s.auto_scan_days || 7} 天自动扫描`);
  else meta.push("定期扫描已关闭");
  meta.push(`上次：${fmtScanTime(s.last_auto_scan)}`);
  if (s.last_auto_scan_message) meta.push(s.last_auto_scan_message);
  $("autoScanMeta").textContent = meta.join(" · ");
  const sum = $("settingsSummary");
  if (sum) {
    const where = state.libraryLabel || s.out_dir || "";
    sum.textContent = `${where} · ${meta[0]} · ${meta[1]}`;
  }
  syncOnboard(s.subscribed_count || 0);
}

function syncOnboard(subscribedCount) {
  const el = $("onboard");
  if (!el) return;
  const dismissed = localStorage.getItem("podstash-onboard") === "1";
  el.classList.toggle("hidden", dismissed || subscribedCount > 0);
}

function setWorkBanner(text) {
  const bar = $("workBanner");
  const label = $("workBannerText");
  if (!bar) return;
  if (!text) {
    bar.classList.add("hidden");
    return;
  }
  if (label) label.textContent = text;
  bar.classList.remove("hidden");
}

async function loadSettings() {
  const s = await api("/api/settings");
  applySettings(s);
  await loadLibrary();
}

async function checkUpdate() {
  try {
    const u = await api("/api/update");
    if (!u || !u.has_update) return;
    const banner = $("updateBanner");
    if (!banner) return;
    $("updateBannerText").textContent =
      `有新版本 v${u.latest}（当前 v${u.current}）。在 NAS 上执行：` +
      `docker compose pull && docker compose up -d`;
    const link = $("updateBannerLink");
    if (u.html_url) {
      link.href = u.html_url;
      link.style.display = "";
    } else {
      link.style.display = "none";
    }
    banner.classList.remove("hidden");
  } catch {
    /* ignore update-check failures */
  }
}

async function saveSettings() {
  try {
    const s = await api("/api/settings", {
      method: "POST",
      body: JSON.stringify({
        out_dir: $("outDir").value.trim(),
        concurrency: Number($("concurrency").value || 32),
        auto_scan: $("autoScan").checked,
        auto_scan_days: Number($("autoScanDays").value || 7),
        auto_scan_limit: Number($("autoScanLimit").value),
        auto_scan_mode: $("autoScanMode") ? $("autoScanMode").value : "new",
        auto_upgrade_quality: $("autoUpgradeQuality") ? $("autoUpgradeQuality").checked : false,
      }),
    });
    applySettings(s);
    toast("设置已保存");
    if (state.show) await scanExisting(true);
  } catch (e) {
    toast(e.message);
  }
}

async function loadLibrary() {
  try {
    const data = await api("/api/library");
    state.library = data.shows || [];
    renderLibrary();
  } catch {
    state.library = [];
    renderLibrary();
  }
}

function renderLibrary() {
  const box = $("libraryShows");
  const empty = $("libraryEmpty");
  const list = state.library || [];
  if (!list.length) {
    box.innerHTML = "";
    empty.style.display = "";
    return;
  }
  empty.style.display = "none";
  renderShowGrid(box, list, "library");
}

async function toggleSubscribe() {
  if (!state.show) {
    toast("请先加载一档节目");
    return;
  }
  const next = !state.show.subscribed;
  try {
    await api("/api/subscribe", {
      method: "POST",
      body: JSON.stringify({
        id: state.show.id || "",
        name: state.show.name || "",
        author: state.show.author || "",
        artwork: state.show.artwork || "",
        feed_url: state.show.feed_url || "",
        episode_count: state.episodes.length,
        subscribed: next,
      }),
    });
    state.show.subscribed = next;
    renderEpisodes();
    await loadLibrary();
    toast(next ? "已关注。定期扫描会补下未有的单集" : "已取消关注");
  } catch (e) {
    toast(e.message);
  }
}

function syncPerShowSettings() {
  if (!state.show) return;
  const key = state.show.id || state.show.feed_url || state.show.name || "";
  const lib = (state.library || []).find(
    (s) => (s.id || s.feed_url || s.name || "") === key
  );
  if ($("perShowScanDays")) {
    $("perShowScanDays").value = lib && lib.scan_days != null ? String(lib.scan_days) : "";
  }
  if ($("perShowScanLimit")) {
    $("perShowScanLimit").value =
      lib && lib.scan_limit != null ? String(lib.scan_limit) : "";
  }
}

async function savePerShowSettings() {
  if (!state.show) return;
  const daysEl = $("perShowScanDays");
  const limitEl = $("perShowScanLimit");
  const days = daysEl.value === "" ? null : Number(daysEl.value);
  const limit = limitEl.value === "" ? null : Number(limitEl.value);
  try {
    await api("/api/show-settings", {
      method: "POST",
      body: JSON.stringify({
        id: state.show.id || "",
        name: state.show.name || "",
        feed_url: state.show.feed_url || "",
        scan_days: days,
        scan_limit: limit,
      }),
    });
    await loadLibrary();
    syncPerShowSettings();
    toast("已保存本档扫描设置");
  } catch (e) {
    toast(e.message);
  }
}

async function runAutoScanNow() {
  const btn = $("btnAutoScanNow");
  try {
    const data = await api("/api/auto-scan", { method: "POST", body: "{}" });
    toast(data.message || "已开始扫描");
    setWorkBanner(data.message || "正在扫描关注的节目…");
    pollAutoScan();
  } catch (e) {
    toast(e.message);
  } finally {
    if (btn) btn.disabled = false;
  }
}

function pollAutoScan() {
  clearInterval(state.autoScanTimer);
  const tick = async () => {
    try {
      const s = await api("/api/auto-scan");
      if (s.last_auto_scan_message) {
        $("autoScanMeta").textContent = s.last_auto_scan_message;
        setWorkBanner(s.running ? s.last_auto_scan_message : "");
      }
      if (!s.running) {
        clearInterval(state.autoScanTimer);
        state.autoScanTimer = null;
        setWorkBanner("");
        toast(s.last_auto_scan_message || "扫描结束");
        await loadSettings();
        if (state.show) await scanExisting(false);
      }
    } catch (e) {
      clearInterval(state.autoScanTimer);
      state.autoScanTimer = null;
      setWorkBanner("");
      toast(e.message);
    }
  };
  tick();
  state.autoScanTimer = setInterval(tick, 1000);
}

async function importOpmlFile(file) {
  const xml = await file.text();
  const data = await api("/api/opml/import", {
    method: "POST",
    body: JSON.stringify({ xml }),
  });
  toast(`已导入 ${data.imported} 档关注`);
  await loadLibrary();
  await loadSettings();
}

function exportOpml() {
  window.location.href = "/api/opml/export";
}

/* ---------- Trending ---------- */

async function loadTrending(source) {
  state.trendSource = source || state.trendSource || "cn";
  document.querySelectorAll(".tab").forEach((t) => {
    t.classList.toggle("active", t.dataset.src === state.trendSource);
  });
  const box = $("trending");
  box.innerHTML = `<div class="empty"><span class="spinner"></span> 加载热门…</div>`;
  try {
    const data = await api(
      `/api/trending?source=${encodeURIComponent(state.trendSource)}`
    );
    state.trending = data.shows || [];
    const src = data.source || state.trendSource;
    $("trendingHint").textContent =
      src === "apple"
        ? "International · Apple Top Podcasts · 点击节目加载全集"
        : src === "apple-cn"
          ? "中文热门 · Apple 中国榜（中文播客榜暂不可用）· 点击节目加载全集"
          : "中文榜 · 中文播客榜 xyzrank · 点击节目加载全集";
    renderShowGrid(box, state.trending, "trend");
    if (!state.trending.length) {
      box.innerHTML = `<div class="empty">暂无热门数据</div>`;
    }
  } catch (e) {
    box.innerHTML = `<div class="empty">热门加载失败：${escapeHtml(e.message)}</div>`;
  }
}

/* ---------- Search ---------- */

async function doSearch() {
  const q = $("searchInput").value.trim();
  if (!q) {
    toast("请输入搜索关键词");
    return;
  }
  const btn = $("btnSearch");
  btn.disabled = true;
  btn.innerHTML = '<span class="spinner"></span> 搜索中';
  try {
    const country = $("country").value;
    const data = await api(
      `/api/search?q=${encodeURIComponent(q)}&country=${encodeURIComponent(country)}`
    );
    state.shows = data.shows || [];
    renderShows();
    if (!state.shows.length) toast("没有找到节目");
    else toast(`找到 ${state.shows.length} 个（via ${data.via || "api"}）`);
  } catch (e) {
    toast(e.message);
  } finally {
    btn.disabled = false;
    btn.textContent = "搜索";
  }
}

function renderShows() {
  const section = $("showsSection");
  const box = $("shows");
  if (!state.shows.length) {
    section.classList.add("hidden");
    return;
  }
  section.classList.remove("hidden");
  $("showsCount").textContent = `${state.shows.length} 个节目 · 点击加载`;
  renderShowGrid(box, state.shows, "search");
}

function renderShowGrid(box, list, kind) {
  box.innerHTML = list
    .map((s, i) => {
      const art = s.artwork
        ? `<img src="${escapeAttr(s.artwork)}" alt="" loading="lazy" decoding="async" />`
        : `<div class="art-ph">${escapeHtml((s.name || "?")[0])}</div>`;
      const rank =
        kind === "trend" && s.rank
          ? `<span class="rank">#${s.rank}</span>`
          : kind === "trend"
            ? `<span class="rank">#${i + 1}</span>`
            : "";
      const sub = [s.author, s.episode_count ? `${s.episode_count} 集` : ""]
        .filter(Boolean)
        .join(" · ");
      return `
        <button class="show-card" data-kind="${kind}" data-i="${i}" title="点击加载单集">
          ${art}
          <div class="meta">
            <div class="name">${rank}${escapeHtml(s.name)}</div>
            <div class="sub">${escapeHtml(sub)}</div>
          </div>
        </button>`;
    })
    .join("");

  box.querySelectorAll(".show-card").forEach((el) => {
    el.addEventListener("click", async () => {
      const k = el.dataset.kind;
      const listRef = k === "trend" ? state.trending : k === "library" ? state.library : state.shows;
      const s = listRef[Number(el.dataset.i)];
      box.querySelectorAll(".show-card").forEach((c) => c.classList.remove("active"));
      el.classList.add("active");
      const source = s.feed_url || s.id || s.apple_id;
      if (!source) {
        toast("该节目缺少 feed / ID");
        return;
      }
      await loadShow(source, s);
      $("episodesSection").scrollIntoView({ behavior: "smooth", block: "start" });
    });
  });
}

/* ---------- Resolve / load show ---------- */

async function doResolve() {
  const src = $("sourceInput").value.trim();
  if (!src) {
    toast("请粘贴链接或 ID");
    return;
  }
  await loadShow(src);
}

async function loadShow(source, preview = null) {
  const gen = ++state.loadGen;
  const btn = $("btnResolve");
  btn.disabled = true;
  const old = btn.textContent;
  btn.innerHTML = '<span class="spinner"></span> 加载中';
  if (preview) {
    state.show = { ...preview, subscribed: preview.subscribed };
    state.episodes = [];
    state.selected = new Set();
    state.epLimit = EP_PAGE;
    renderEpisodes();
    const box = $("epList");
    box.innerHTML = `<div class="ep-skel"></div><div class="ep-skel"></div><div class="ep-skel"></div>
      <div class="empty"><span class="spinner"></span> 正在拉取节目列表…</div>`;
  }
  setWorkBanner("正在拉取节目列表…");
  $("episodesSection").classList.remove("hidden");
  $("episodesSection").scrollIntoView({ behavior: "smooth", block: "start" });
  try {
    const data = await api("/api/resolve", {
      method: "POST",
      body: JSON.stringify({
        source,
        country: $("country").value,
        scan: false,
      }),
    });
    if (gen !== state.loadGen) return;
    state.show = { ...(preview || {}), ...data.show };
    if (preview?.artwork && !state.show.artwork) state.show.artwork = preview.artwork;
    if (preview?.name && (!state.show.name || state.show.name === "Podcast")) {
      state.show.name = preview.name;
    }
    state.episodes = data.episodes || [];
    state.selected = new Set();
    state.epLimit = EP_PAGE;
    const undown = state.episodes.filter((e) => !e.downloaded);
    const pick = (undown.length ? undown : state.episodes).slice(0, 10);
    pick.forEach((e) => state.selected.add(e.index));
    renderEpisodes();
    toast(`已加载 ${state.episodes.length} 集，正在检测本地文件…`);
    if (state.show.id) $("sourceInput").value = state.show.id;
    else if (state.show.feed_url) $("sourceInput").value = state.show.feed_url;
    await scanExisting(false);
    if (gen !== state.loadGen) return;
    const localN = state.episodes.filter((e) => e.downloaded).length;
    if (localN) toast(`本地已有 ${localN} 集，不会重复下载`);
  } catch (e) {
    if (gen !== state.loadGen) return;
    setWorkBanner("");
    toast(e.message);
  } finally {
    if (gen === state.loadGen) {
      btn.disabled = false;
      btn.textContent = old;
    }
  }
}

function renderEpisodes() {
  const section = $("episodesSection");
  if (!state.show) {
    section.classList.add("hidden");
    return;
  }
  section.classList.remove("hidden");

  $("showName").textContent = state.show.name || "未命名节目";
  const localN = state.episodes.filter((e) => e.downloaded).length;
  $("showMeta").textContent = [
    state.show.author,
    `${state.episodes.length} 集`,
    localN ? `本地已有 ${localN}` : "",
    state.show.subscribed ? "已关注" : "",
    state.show.feed_url ? "可批量下载" : "",
  ]
    .filter(Boolean)
    .join(" · ");
  const subBtn = $("btnSubscribe");
  if (subBtn) {
    subBtn.textContent = state.show.subscribed ? "已关注" : "关注";
    subBtn.classList.toggle("btn-primary", !state.show.subscribed);
  }

  const art = $("showArt");
  if (state.show.artwork) {
    art.src = state.show.artwork;
    art.style.display = "";
  } else {
    art.removeAttribute("src");
    art.style.display = "none";
  }

  const filter = ($("filterInput").value || "").trim().toLowerCase();
  const list = state.episodes.filter((e) =>
    filter ? (e.title || "").toLowerCase().includes(filter) : true
  );
  const visible = list.slice(0, state.epLimit);
  const box = $("epList");
  if (!list.length) {
    box.innerHTML = `<div class="empty">${state.episodes.length ? "没有匹配的单集" : "正在加载…"}</div>`;
  } else {
    box.innerHTML = visible
      .map((e) => {
        const checked = state.selected.has(e.index) ? "checked" : "";
        const size = e.size ? fmtBytes(e.size) : e.local_size ? fmtBytes(e.local_size) : "";
        const rowClass = e.downloaded ? "ep-item downloaded" : "ep-item";
        let badge = `<span class="badge">#${e.index}</span>`;
        if (e.downloaded) badge = `<span class="badge badge-ok">已下载</span>`;
        else if (e.partial) badge = `<span class="badge badge-warn">未下完</span>`;
        const descText = htmlToText(e.description || "");
        let descHtml = "";
        if (descText) {
          const short = descText.length > 220;
          descHtml = `<div class="ep-desc">${escapeHtml(
            short ? descText.slice(0, 220) : descText
          )}${
            short
              ? ` <button class="linklike ep-desc-toggle" data-full="${escapeAttr(descText)}">展开</button>`
              : ""
          }</div>`;
        }
        const ignBtn = e.guid
          ? `<button class="btn btn-mini${e.ignored ? " is-ignored" : ""}" data-ignore="${escapeAttr(
              e.guid
            )}">${e.ignored ? "取消忽略" : "忽略"}</button>`
          : "";
        return `
          <div class="${rowClass}">
            <input type="checkbox" id="epcb${e.index}" data-index="${e.index}" ${checked} />
            <label class="ep-main" for="epcb${e.index}">
              <div class="title">${escapeHtml(e.title)}</div>
              <div class="info">${escapeHtml(e.published || "日期未知")}${
                e.duration ? " · " + escapeHtml(e.duration) : ""
              }${size ? " · " + size : ""}</div>
              ${descHtml}
            </label>
            <div class="ep-side">
              ${badge}
              ${ignBtn}
            </div>
          </div>`;
      })
      .join("");
    if (!box._bound) {
      box.addEventListener("change", (ev) => {
        const cb = ev.target.closest("input[type=checkbox]");
        if (!cb) return;
        const idx = Number(cb.dataset.index);
        if (cb.checked) state.selected.add(idx);
        else state.selected.delete(idx);
        updateSelectedCount();
      });
      box.addEventListener("click", (ev) => {
        const toggle = ev.target.closest(".ep-desc-toggle");
        if (toggle) {
          ev.preventDefault();
          ev.stopPropagation();
          const full = toggle.dataset.full || "";
          const parent = toggle.parentElement;
          if (toggle.textContent.trim() === "展开") {
            parent.classList.add("open");
            parent.innerHTML =
              escapeHtml(full) +
              ` <button class="linklike ep-desc-toggle" data-full="${escapeAttr(full)}">收起</button>`;
          } else {
            parent.classList.remove("open");
            parent.innerHTML =
              escapeHtml(full.slice(0, 220)) +
              ` <button class="linklike ep-desc-toggle" data-full="${escapeAttr(full)}">展开</button>`;
          }
          return;
        }
        const ign = ev.target.closest("[data-ignore]");
        if (ign) {
          ev.preventDefault();
          ev.stopPropagation();
          toggleIgnore(ign.dataset.ignore);
          return;
        }
      });
      box._bound = true;
    }
  }
  const more = $("btnMoreEpisodes");
  if (more) {
    const left = list.length - visible.length;
    more.classList.toggle("hidden", left <= 0);
    more.textContent = left > 0 ? `显示更多（还有 ${left} 集）` : "显示更多";
  }
  syncPerShowSettings();
  updateSelectedCount();
}

function updateSelectedCount() {
  $("selectedCount").textContent = `已选 ${state.selected.size}`;
}

function selectLatest(n) {
  state.selected = new Set(state.episodes.slice(0, n).map((e) => e.index));
  renderEpisodes();
}

function selectAllVisible() {
  const filter = ($("filterInput").value || "").trim().toLowerCase();
  state.episodes.forEach((e) => {
    if (!filter || (e.title || "").toLowerCase().includes(filter)) {
      state.selected.add(e.index);
    }
  });
  renderEpisodes();
}

function clearSelection() {
  state.selected.clear();
  renderEpisodes();
}

function selectUndownloaded() {
  state.selected = new Set(
    state.episodes.filter((e) => !e.downloaded).map((e) => e.index)
  );
  renderEpisodes();
}

async function refreshLocalStatus() {
  await scanExisting(false);
}

async function scanExisting(notify) {
  if (!state.show || !state.episodes.length) {
    if (notify) toast("请先加载一档节目，再检测该节目在目录里的已有文件");
    return;
  }
  const where = state.libraryLabel || $("outDir").value.trim() || "下载目录";
  const scanBtns = [$("btnScanLibrary"), $("btnScanShow")].filter(Boolean);
  scanBtns.forEach((b) => {
    b.disabled = true;
    b.dataset.old = b.textContent;
    b.innerHTML = '<span class="spinner"></span> 检测中';
  });
  setWorkBanner(`正在检测已有文件：扫描「${where}」…`);
  try {
    const data = await api("/api/local-status", {
      method: "POST",
      body: JSON.stringify({
        show_name: state.show.name,
        out_dir: $("outDir").value.trim() || undefined,
        episodes: state.episodes,
      }),
    });
    const byIndex = new Map((data.episodes || []).map((e) => [e.index, e]));
    state.episodes = state.episodes.map((e) => ({
      ...e,
      ...(byIndex.get(e.index) || {}),
    }));
    renderEpisodes();
    const n = data.local_downloaded || 0;
    setWorkBanner(n ? `检测完成：已标记 ${n} 集为已下载` : "检测完成：这个节目在目录里没有识别到已有文件");
    if (notify) {
      toast(n ? `已标记 ${n} 集为已下载，不会重复下载` : "该目录里没有识别到已有文件（文件名需包含单集标题）");
    }
    setTimeout(() => setWorkBanner(""), 2800);
  } catch (e) {
    setWorkBanner("");
    if (notify) toast(e.message);
  } finally {
    scanBtns.forEach((b) => {
      b.disabled = false;
      b.textContent = b.dataset.old || "检测已有文件";
    });
  }
}

async function openDiag() {
  if (!state.show) {
    toast("请先加载一档节目");
    return;
  }
  const src = state.show.feed_url || state.show.id || state.show.name;
  const btn = $("btnDiag");
  btn.disabled = true;
  setWorkBanner("正在生成识别诊断…");
  try {
    const data = await api(`/api/scan-debug?source=${encodeURIComponent(src)}`);
    $("diagText").textContent = JSON.stringify(data, null, 2);
    $("diagModal").classList.remove("hidden");
  } catch (e) {
    toast(e.message);
  } finally {
    btn.disabled = false;
    setWorkBanner("");
  }
}

function closeDiag() {
  $("diagModal").classList.add("hidden");
}

function copyDiag() {
  const t = $("diagText").textContent || "";
  const done = () => toast("已复制，粘贴给我即可");
  const fail = () => toast("复制失败，请手动全选复制");
  if (navigator.clipboard && navigator.clipboard.writeText) {
    navigator.clipboard.writeText(t).then(done).catch(fail);
  } else {
    const ta = document.createElement("textarea");
    ta.value = t;
    document.body.appendChild(ta);
    ta.select();
    try {
      document.execCommand("copy");
      done();
    } catch (err) {
      fail();
    }
    document.body.removeChild(ta);
  }
}

async function retagFiles() {
  if (!state.show || !state.episodes.length) {
    toast("请先加载一档节目");
    return;
  }
  const done = state.episodes.filter((e) => e.downloaded);
  if (!done.length) {
    toast("没有识别到已下载的文件。先点「检测已有文件」，成功标记后再补写标签。");
    return;
  }
  const btn = $("btnRetag");
  btn.disabled = true;
  const old = btn.textContent;
  btn.innerHTML = '<span class="spinner"></span> 补写中';
  setWorkBanner(`正在给 ${done.length} 个已有文件补写标签…`);
  try {
    const data = await api("/api/retag", {
      method: "POST",
      body: JSON.stringify({
        show_name: state.show.name,
        author: state.show.author,
        artwork: state.show.artwork,
        out_dir: $("outDir").value.trim() || undefined,
        episodes: done,
      }),
    });
    toast(`已为 ${data.tagged || 0} 个文件补写标签`);
    setWorkBanner("");
  } catch (e) {
    setWorkBanner("");
    toast(e.message);
  } finally {
    btn.disabled = false;
    btn.textContent = old;
  }
}

async function startDownload() {
  if (!state.show || !state.selected.size) {
    toast("请先选择要下载的单集");
    return;
  }
  const eps = state.episodes.filter((e) => state.selected.has(e.index));
  const btn = $("btnDownload");
  btn.disabled = true;
  try {
    const data = await api("/api/download", {
      method: "POST",
      body: JSON.stringify({
        show_name: state.show.name,
        out_dir: $("outDir").value.trim() || undefined,
        concurrency: Number($("concurrency").value || 32),
        artwork: state.show.artwork || "",
        episodes: eps,
      }),
    });
    state.jobId = data.job_id;
    state.jobStartedAt = Date.now();
    $("progressSection").classList.remove("hidden");
    $("jobTitle").textContent = `下载：${state.show.name}`;
    $("progressSection").scrollIntoView({ behavior: "smooth", block: "nearest" });
    pollJob();
    toast(`开始下载 ${eps.length} 集`);
  } catch (e) {
    toast(e.message);
  } finally {
    btn.disabled = false;
  }
}

async function quickDownloadLatest10() {
  if (!state.episodes.length) {
    toast("请先加载节目");
    return;
  }
  selectLatest(10);
  await startDownload();
}

async function downloadAllEpisodes() {
  if (!state.episodes.length) {
    toast("请先加载节目");
    return;
  }
  const n = state.episodes.length;
  if (n > 80 && !window.confirm(`将下载全部 ${n} 集，可能耗时较长，确定继续？`)) {
    return;
  }
  state.selected = new Set(state.episodes.map((e) => e.index));
  renderEpisodes();
  await startDownload();
}

function pollJob() {
  clearInterval(state.pollTimer);
  const tick = async () => {
    if (!state.jobId) return;
    try {
      const job = await api(`/api/download/${state.jobId}`);
      renderJob(job);
      if (["done", "error", "cancelled"].includes(job.status)) {
        clearInterval(state.pollTimer);
        state.pollTimer = null;
        if (job.status === "done") toast(job.message || "下载完成");
        else if (job.status === "error") toast(job.message || "下载出错");
        else toast("已取消");
        refreshLocalStatus();
      }
    } catch (e) {
      clearInterval(state.pollTimer);
      toast(e.message);
    }
  };
  tick();
  state.pollTimer = setInterval(tick, 800);
}

function renderJob(job) {
  const pct = job.total ? Math.round((job.done / job.total) * 100) : 0;
  $("jobBar").style.width = `${pct}%`;
  const running = (job.items || []).filter((i) => i.status === "running").length;
  const elapsed = state.jobStartedAt
    ? Math.max(0, Math.round((Date.now() - state.jobStartedAt) / 1000))
    : 0;
  const live =
    job.status === "running"
      ? `⏳ ${running ? `正在下载 ${running} 集` : "准备中"} · 已用时 ${fmtDuration(elapsed)}`
      : `已用时 ${fmtDuration(elapsed)}`;
  $("jobMeta").textContent = `${job.done}/${job.total} 完成 · 失败 ${job.failed} · ${live}${
    job.message ? " · " + job.message : ""
  }`;
  const statusLabel = {
    pending: "等待",
    running: "下载中",
    done: "完成",
    error: "失败",
    skipped: "已存在",
  };

  // show retry when finished and has failures
  const retryBtn = $("btnRetryFailed");
  const canRetry =
    job.failed > 0 && ["done", "error", "cancelled"].includes(job.status);
  retryBtn.classList.toggle("hidden", !canRetry);
  retryBtn.disabled = !canRetry;

  $("jobItems").innerHTML = (job.items || [])
    .map((it) => {
      const st = statusLabel[it.status] || it.status;
      const cls =
        it.status === "done"
          ? "st-done"
          : it.status === "error"
            ? "st-error"
            : it.status === "running"
              ? "st-running"
              : it.status === "skipped"
                ? "st-skipped"
                : "";
      const prog =
        it.bytes_total > 0
          ? `${fmtBytes(it.bytes_done)} / ${fmtBytes(it.bytes_total)}`
          : it.bytes_done
            ? fmtBytes(it.bytes_done)
            : it.status === "running"
              ? "连接中…"
              : "";
      const err =
        it.status === "error" && it.error
          ? `<div class="job-err">${escapeHtml(it.error)}</div>`
          : "";
      return `<div class="job-line">
        <span class="${cls}">${st}</span>
        <span title="${escapeAttr(it.path || it.error || "")}">
          ${escapeHtml(it.title)}
          ${err}
        </span>
        <span>${escapeHtml(prog)}</span>
      </div>`;
    })
    .join("");
}

async function cancelJob() {
  if (!state.jobId) return;
  try {
    await api(`/api/download/${state.jobId}/cancel`, { method: "POST" });
    toast("正在取消…");
  } catch (e) {
    toast(e.message);
  }
}

async function retryFailed() {
  if (!state.jobId) return;
  const btn = $("btnRetryFailed");
  btn.disabled = true;
  try {
    const data = await api(`/api/download/${state.jobId}/retry`, {
      method: "POST",
    });
    toast(`正在重试 ${data.retrying} 集失败项…`);
    pollJob();
  } catch (e) {
    toast(e.message);
    btn.disabled = false;
  }
}

function escapeHtml(s) {
  return String(s ?? "")
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;");
}

function escapeAttr(s) {
  return escapeHtml(s).replace(/'/g, "&#39;");
}

function htmlToText(html) {
  if (!html) return "";
  const key = html;
  const hit = htmlToText._cache && htmlToText._cache.get(key);
  if (hit !== undefined) return hit;
  const div = document.createElement("div");
  div.innerHTML = html;
  const text = (div.textContent || "").replace(/\s+/g, " ").trim();
  if (!htmlToText._cache) htmlToText._cache = new Map();
  if (htmlToText._cache.size > 500) htmlToText._cache.clear();
  htmlToText._cache.set(key, text);
  return text;
}

async function toggleIgnore(guid) {
  if (!guid) return;
  const ep = state.episodes.find((e) => (e.guid || "") === guid);
  if (!ep) return;
  const next = !ep.ignored;
  try {
    await api("/api/episodes/ignore", {
      method: "POST",
      body: JSON.stringify({ guid, ignored: next }),
    });
    ep.ignored = next;
    renderEpisodes();
    toast(next ? "已忽略，扫描不会再下这一集" : "已取消忽略");
  } catch (e) {
    toast(e.message);
  }
}

async function doContentSearch() {
  const q = $("contentSearchInput").value.trim();
  const box = $("contentSearchResults");
  if (!q) {
    toast("请输入关键词");
    return;
  }
  box.innerHTML = `<div class="empty"><span class="spinner"></span> 搜索中…</div>`;
  try {
    const data = await api(`/api/episodes/search?q=${encodeURIComponent(q)}`);
    const list = data.results || [];
    if (!list.length) {
      box.innerHTML = `<div class="empty">没有找到匹配的单集</div>`;
      return;
    }
    box.innerHTML = list
      .map(
        (r) => `
        <div class="search-hit">
          <div class="search-hit-title">${escapeHtml(r.title)}${
            r.show_name ? ` <span class="muted">· ${escapeHtml(r.show_name)}</span>` : ""
          }</div>
          <div class="search-hit-info muted">${escapeHtml(r.published || "日期未知")}${
            r.duration ? " · " + escapeHtml(r.duration) : ""
          }</div>
          ${r.snippet ? `<div class="search-hit-snippet">${escapeHtml(r.snippet)}</div>` : ""}
        </div>`
      )
      .join("");
  } catch (e) {
    box.innerHTML = `<div class="empty">搜索失败：${escapeHtml(e.message)}</div>`;
  }
}

/* ---------- Deep link & bookmarklet ---------- */

function handleDeepLink() {
  const params = new URLSearchParams(location.search);
  const src =
    params.get("src") ||
    params.get("id") ||
    params.get("feed") ||
    params.get("url") ||
    "";
  if (src) {
    $("sourceInput").value = src;
    loadShow(src);
  }
}

function setupBookmarklet() {
  // Opens local OMNIX-Podstash with Apple podcast id extracted from current page URL
  const js = `javascript:(function(){
    var u=location.href,m=u.match(/id(\\d+)/)||u.match(/\\b(\\d{8,})\\b/),id=m&&m[1];
    if(!id){alert('当前页未找到 Apple Podcast ID');return;}
    open('http://127.0.0.1:8765/?src='+encodeURIComponent(id),'_blank');
  })();`;
  const a = $("bookmarklet");
  if (a) {
    a.href = js;
    a.addEventListener("click", (e) => {
      // prevent navigating away when clicked in-page; user should drag to bookmarks
      if (!e.metaKey && !e.ctrlKey) {
        e.preventDefault();
        toast("请把此链接拖到浏览器收藏栏，再到播客页点击");
      }
    });
  }
}

function bind() {
  $("btnSearch").addEventListener("click", doSearch);
  $("searchInput").addEventListener("keydown", (e) => {
    if (e.key === "Enter") doSearch();
  });
  $("btnResolve").addEventListener("click", doResolve);
  $("sourceInput").addEventListener("keydown", (e) => {
    if (e.key === "Enter") doResolve();
  });
  $("btnSaveSettings").addEventListener("click", saveSettings);
  $("autoScan").addEventListener("change", saveSettings);
  $("autoScanDays").addEventListener("change", saveSettings);
  $("autoScanLimit").addEventListener("change", saveSettings);
  $("autoScanMode")?.addEventListener("change", saveSettings);
  $("autoUpgradeQuality")?.addEventListener("change", saveSettings);
  $("btnAutoScanNow").addEventListener("click", runAutoScanNow);
  $("btnContentSearch").addEventListener("click", doContentSearch);
  $("contentSearchInput").addEventListener("keydown", (e) => {
    if (e.key === "Enter") doContentSearch();
  });
  $("btnSubscribe").addEventListener("click", toggleSubscribe);
  $("btnImportOpml").addEventListener("click", () => $("opmlFile").click());
  $("opmlFile").addEventListener("change", async (e) => {
    const file = e.target.files && e.target.files[0];
    e.target.value = "";
    if (!file) return;
    try {
      await importOpmlFile(file);
    } catch (err) {
      toast(err.message);
    }
  });
  $("btnExportOpml").addEventListener("click", exportOpml);
  $("btnScanLibrary").addEventListener("click", () => scanExisting(true));
  $("btnScanShow").addEventListener("click", () => scanExisting(true));
  $("btnRetag").addEventListener("click", retagFiles);
  $("btnDiag").addEventListener("click", openDiag);
  $("btnDiagClose").addEventListener("click", closeDiag);
  $("btnDiagCopy").addEventListener("click", copyDiag);
  $("diagModal").addEventListener("click", (ev) => {
    if (ev.target === $("diagModal")) closeDiag();
  });
  $("btnPerShowSave")?.addEventListener("click", savePerShowSettings);
  $("btnDismissOnboard")?.addEventListener("click", () => {
    localStorage.setItem("podstash-onboard", "1");
    $("onboard").classList.add("hidden");
  });
  $("btnToggleSettings")?.addEventListener("click", () => {
    const extra = $("settingsExtra");
    const open = extra.classList.toggle("hidden");
    $("btnToggleSettings").textContent = extra.classList.contains("hidden") ? "展开" : "收起";
    void open;
  });
  $("btnMoreEpisodes")?.addEventListener("click", () => {
    state.epLimit += EP_PAGE;
    renderEpisodes();
  });
  $("btnAll").addEventListener("click", selectAllVisible);
  $("btnUndownloaded").addEventListener("click", selectUndownloaded);
  $("btnNone").addEventListener("click", clearSelection);
  $("btnLatest10").addEventListener("click", () => selectLatest(10));
  $("btnLatest30").addEventListener("click", () => selectLatest(30));
  $("btnLatest100").addEventListener("click", () => selectLatest(100));
  $("filterInput").addEventListener("input", () => renderEpisodes());
  $("btnDownload").addEventListener("click", startDownload);
  $("btnDownloadAll").addEventListener("click", downloadAllEpisodes);
  $("btnQuick10").addEventListener("click", quickDownloadLatest10);
  $("btnCancel").addEventListener("click", cancelJob);
  $("btnRetryFailed").addEventListener("click", retryFailed);
  $("btnRefreshTrend").addEventListener("click", () => loadTrending(state.trendSource));
  $("tabCn").addEventListener("click", () => loadTrending("cn"));
  $("tabIntl").addEventListener("click", () => loadTrending("apple"));
  $("btnDismissUpdate")?.addEventListener("click", () => {
    $("updateBanner")?.classList.add("hidden");
  });
}

bind();
setupBookmarklet();
loadSettings()
  .then(() => handleDeepLink())
  .catch((e) => toast(e.message));
loadTrending("cn").catch((e) => toast(e.message));
checkUpdate();
