# Changelog

## 0.3.0

- 定期自动扫描：关注的节目按天/周/两周检查，补下还未下载的单集（可开关；每档每次集数可限制）
- PC / Docker：关注、OPML 导入导出、设置与订阅写入本地配置
- Docker：`docker-compose.yml` + 多架构镜像（linux/amd64、linux/arm64）

## 0.2.1

- 打开节目时扫描设定的下载目录（含子文件夹、你选择的系统文件夹）
- 文件名包含单集标题即视为已下载（支持「001 标题」「日期 标题」「节目名 - 标题」）
- PC / Android 增加「检测已有文件」；识别到的集不会重复下载，Android 可直接播本地文件

## 0.2.0

- Android：暂停 / 继续下载（断点续传）
- Android：设置里显示下载目录，可选择系统文件夹（完成后复制一份）
- Android：节目页多选后下载已选；仍保留「下载未有」
- Android：播放页进度条、倍速、±10/30 秒、睡眠定时、继续听
- Android：库首页下载队列；OPML 导入 / 导出

## 0.1.1

- 修复 Android 启动图标 XML，使 GitHub Actions 能打出 APK

## 0.1.0

- PC 库管：搜索、热门、全集下载、已有跳过；改名为 OMNIX-Podstash
- Android：搜索、关注、下载、播放、打开即下新集、GitHub 应用内更新
- 版本与 APK 由 GitHub Release 发布
