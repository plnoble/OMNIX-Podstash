# OMNIX-Podstash

私人播客库。搜、订、存、听，都在你自己的设备上。

不经过任何中转站：搜索走 Apple 公开目录，热门走中文播客榜 / Apple Top，音频走节目自己的 RSS 直链。

当前版本 **0.4.6**

## 两端

| | PC / Docker 库管 | Android 听库 |
|---|---|---|
| 位置 | `pc/`、根目录 `docker-compose.yml` | `android/` |
| 能力 | 搜索、关注、全集下载、已有跳过、定期扫描 | 搜索、订阅、下载、播放、打开即下新集、定期扫描 |
| 启动 | 双击 `启动.bat`，或 `docker compose up -d` | 安装 GitHub Release 里的 APK |

## Android 安装与更新

1. 打开 [Releases](https://github.com/plnoble/OMNIX-Podstash/releases) 安装 APK。
2. 之后每次打开应用会检查 GitHub 上的最新 Release；有新版本会弹窗，可直接下载安装。
3. 需要系统允许「安装未知应用」。

开发者发新版：改 `VERSION` 和 `version.json`，打 tag `vX.Y.Z` 并 push。GitHub Actions 会编译 APK 并创建 Release。日常 `git push` 到 `main` 只同步源码，**不会**让手机弹更新。

## PC

```bash
cd pc
pip install -r requirements.txt
python app.py
```

Windows 也可在仓库根目录双击 `启动.bat`。

默认保存到 `D:\Podcasts\<节目名>\`。打开节目或点「检测已有文件」会扫描该目录：用其他工具下过、文件名里带单集标题的音频会标成已下载，不会重复下载。

关注节目后，可打开「定期自动扫描」：默认每周检查一次，把还没有的单集补下来（已有的跳过）。每档每次默认最多 30 集，可在设置里改。

## Docker（ARM NAS / 树莓派）

镜像发布在 **GitHub Container Registry**（需能访问 GitHub，国内可走代理/VPN）：

```
ghcr.io/plnoble/omnix-podstash:latest
```

### 部署（极空间 T2S 等 NAS）

1. 文件管理里建好 `.../omnix-podstash/podcasts` 和 `config` 两个空目录。
2. Docker → 新建项目，粘贴仓库里的 `docker-compose.yml`（已指向 ghcr 镜像，没有 `build`）。
3. 把 `volumes` 改成你实际的路径；浏览器打开 `http://NAS的IP:8765`。

### 更新

```bash
docker compose pull && docker compose up -d
```

或极空间 Docker → 项目 → 更新 / 重新创建；想全自动可挂 watchtower（镜像名带 registry 地址，watchtower 可直接查更新）。

### 拉不到镜像时的兜底（离线 tar 导入）

1. 从 [Releases](https://github.com/plnoble/OMNIX-Podstash/releases) 下载 `omnix-podstash-*-linux-arm64.tar`
2. 极空间 Docker → 镜像 → 本地镜像 → 导入
3. 把 `docker-compose.yml` 里的 `image` 改成 `omnix-podstash:<版本>` 再创建

> 可选：阿里云 ACR 国内直连渠道会随 CI 一起推送（`registry.cn-hangzhou.aliyuncs.com/omnix/omnix-podstash`），但属于可选项、失败不影响发布；也可用 `docker-compose.build.yml` 在 NAS 上本地编译。

### 权限与访问保护

- 容器默认以**非 root** 运行：`PUID` / `PGID`（默认 `1000:1000`），下载出的文件归该 uid 所有，NAS 上可直接管理。首次启用会对 `/podcasts` 做一次递归 chown（大库会稍慢，之后启动自动跳过）。
- 设置 `PODSTASH_PASSWORD` 后，整个 Web 界面启用 HTTP Basic 登录（用户名任意，密码为该值）；`/api/health` 不受保护，供容器健康检查使用。
- 备份：浏览器打开 `http://NAS的IP:8765/api/backup` 下载一份 zip（配置库 + 订阅 OPML + 各节目的已有文件索引）。

## 版权

仅供个人合法收听备份。请遵守各节目版权与使用条款。需要登录才能拿到的音频，公开 RSS 往往没有直链，无法下载。

## 仓库

https://github.com/plnoble/OMNIX-Podstash
