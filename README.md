# OMNIX-Podstash

私人播客库。搜、订、存、听，都在你自己的设备上。

不经过任何中转站：搜索走 Apple 公开目录，热门走中文播客榜 / Apple Top，音频走节目自己的 RSS 直链。

当前版本 **0.3.0**

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

适合飞牛、绿联、群晖、极空间等 ARM 设备。用 YAML 安装：

1. 把仓库放到设备上，或只保留根目录的 `Dockerfile`、`docker-compose.yml` 和 `pc/`。
2. 编辑 `docker-compose.yml` 里的两个目录：音频 `/podcasts`、配置 `/config`。
3. 在该目录执行：

```bash
docker compose up -d --build
```

本机 build 会按设备架构来（ARM64 就是 ARM 镜像）。浏览器打开 `http://设备IP:8765`，先关注节目或导入 OPML，再打开「定期自动扫描」。

也可以用 GitHub 镜像（tag 发布后才有）：

`ghcr.io/plnoble/omnix-podstash:latest`

## 版权

仅供个人合法收听备份。请遵守各节目版权与使用条款。需要登录才能拿到的音频，公开 RSS 往往没有直链，无法下载。

## 仓库

https://github.com/plnoble/OMNIX-Podstash
