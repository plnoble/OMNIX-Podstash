# OMNIX-Podstash

私人播客库。搜、订、存、听，都在你自己的设备上。

不经过任何中转站：搜索走 Apple 公开目录，热门走中文播客榜 / Apple Top，音频走节目自己的 RSS 直链。

当前版本 **0.1.0**

## 两端

| | PC 库管 | Android 听库 |
|---|---|---|
| 位置 | `pc/` | `android/` |
| 能力 | 搜索、热门、全集批量下载、已有跳过 | 搜索、订阅、下载、播放、打开即下新集 |
| 启动 | 双击 `启动.bat` → http://127.0.0.1:8765 | 安装 GitHub Release 里的 APK |

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

默认保存到 `D:\Podcasts\<节目名>\`。已有完整文件会跳过，不会重复下载。

## 版权

仅供个人合法收听备份。请遵守各节目版权与使用条款。需要登录才能拿到的音频，公开 RSS 往往没有直链，无法下载。

## 仓库

https://github.com/plnoble/OMNIX-Podstash
