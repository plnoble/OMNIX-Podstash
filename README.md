# OMNIX-Podstash

私人播客库。搜、订、存、听，都在你自己的设备上。

不经过任何中转站：搜索走 Apple 公开目录，热门走中文播客榜 / Apple Top，音频走节目自己的 RSS 直链。

当前版本 **0.3.1**

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

### 极空间 T2S（不要网上拉 ghcr.io）

极空间在国内经常拉不了 GitHub 容器，而且该镜像默认私有，所以粘贴 YAML 会直接 `Task failed`。

请改用 **导入本地镜像**：

1. 从 [Releases](https://github.com/plnoble/OMNIX-Podstash/releases) 下载 `omnix-podstash-*-linux-arm64.tar`
2. 上传到极空间任意文件夹
3. Docker → 镜像 → 本地镜像 → 导入镜像 → 从极空间导入
4. 文件管理里建好 `.../omnix-podstash/podcasts` 和 `config` 两个空目录
5. Docker → 新建项目，粘贴仓库里的 `docker-compose.yml`（`image: omnix-podstash:0.3.1`，没有 `build`）

### 其他 NAS 若能拉 GitHub 镜像

不要写 `build:`。界面里没有 Dockerfile，带 `build` 会直接失败。只拉镜像：

```yaml
version: "3.8"
services:
  podstash:
    image: ghcr.io/plnoble/omnix-podstash:0.3.1
    container_name: omnix-podstash
    restart: unless-stopped
    ports:
      - "8765:8765"
    environment:
      TZ: Asia/Shanghai
      PODSTASH_HOST: "0.0.0.0"
      PODSTASH_OUT_DIR: /podcasts
      PODSTASH_CONFIG: /config
      PODSTASH_CONCURRENCY: "4"
      PODSTASH_NO_BROWSER: "1"
    volumes:
      - /你复制的路径/podcasts:/podcasts
      - /你复制的路径/config:/config
```

GitHub 容器镜像默认是私有的，极空间拉不下来。需要先把包改成公开：

1. 打开 https://github.com/users/plnoble/packages/container/package/omnix-podstash
2. Package settings → Change visibility → Public

然后在极空间 Docker 里新建项目、粘贴 YAML、创建。浏览器打开 `http://NAS的IP:8765`。

若 `ghcr.io` 一直超时（国内常见），用仓库里的 `docker-compose.build.yml` 在 NAS 上本地编译，不拉 GitHub 镜像。

## 版权

仅供个人合法收听备份。请遵守各节目版权与使用条款。需要登录才能拿到的音频，公开 RSS 往往没有直链，无法下载。

## 仓库

https://github.com/plnoble/OMNIX-Podstash
