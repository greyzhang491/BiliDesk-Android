<img src="app/src/main/res/drawable-nodpi/bilidesk_brand.png" width="72" alt="BiliDesk 图标">

# BiliDesk Android

面向手机和平板的原生 Android 第三方 B站客户端，参考并移植 [ABING1111/BiliDesk](https://github.com/ABING1111/BiliDesk) 的核心逻辑，使用 Kotlin、Jetpack Compose 和 Media3 实现。

**当前版本：0.1.6 开发预览版 · Android 8.0+ · GPL-3.0-only**

这是独立的安卓移植项目，与哔哩哔哩官方、上游作者没有官方关联。上游来源、图标和移植映射保留在 [NOTICE.md](NOTICE.md)，许可全文见 [LICENSE](LICENSE)。

## 下载和使用

在 [Releases](https://github.com/greyzhang491/BiliDesk-Android/releases/tag/v0.1.6) 中下载已验证的预览 APK。已有本项目此前提供的安装包时，可使用同签名版本覆盖更新，保留登录、设置和缓存。

- [使用说明 PDF](docs/USAGE.pdf)：10页，包含操作步骤、手势对照和常见问题。
- [离线网页版说明](docs/USAGE.html)：下载后用浏览器打开，适合手机和平板。
- [0.1.6 版本说明](docs/RELEASE-0.1.6.md) · [完整变更记录](CHANGELOG.md)

GitHub Actions 的构建产物用于开发检查，使用运行环境生成的临时调试签名，**不能保证覆盖安装 Releases 中的 APK**。需要日常使用时，请选择 Releases 的已验证版本。

## 功能

| 功能 | 当前实现 |
| --- | --- |
| 浏览与搜索 | 推荐、热门、排行榜、分区、BV/AV/链接搜索；下拉刷新、自动分页、卡片密度与列数 |
| 视频播放 | DASH 音视频、分P、清晰度与线路、倍速、全屏、画中画、后台听视频 |
| 播放手势 | 双击快进退、长按倍速、长按横滑调进度、横屏左亮度/右音量 |
| 短视频模式 | 普通长短视频上下滑连播，点赞/评论/收藏/分享；横竖切换、倍率选择与锁定、评论继续播放 |
| 音乐模式 | 收藏夹歌单、搜歌、下一首/队列、四种模式、旋转唱片、封面取色、字幕歌词或原视频、小播放条与后台控制 |
| 弹幕 | 滚动/顶部/底部、颜色、透明度、区域、字号、速度、去重与屏蔽词；播放中即时调整 |
| 离线缓存 | 可用画质与视频/音频选择，分段续传、暂停继续、任务恢复、合流、离线播放 |
| 存储管理 | 自动缓存默认关闭；容量与过期清理、手动文件保护，图片与字幕独立限额 |
| 账号内容 | 官方扫码/网页登录，收藏、历史、稍后再看、追番、消息入口 |
| 平板 | 侧边导航、横屏双栏、全屏和分屏适配；音乐歌单/队列与播放区并排 |

<img src="docs/screenshots/music-phone.png" width="240" alt="手机音乐唱片界面示例">

<img src="docs/screenshots/music-tablet.png" width="900" alt="平板横屏音乐界面示例">

界面示例使用测试账号、独立样片和占位封面，不代表真实用户收藏。

## 缓存会自动清理吗

会。自动缓存默认关闭，开启后仅在不超过3分钟的视频播完时尝试保存；默认容量256MB、7天未播放后清理，并限制为非计费 Wi-Fi。

应用启动、自动缓存前后、修改限制和停止本地播放时检查；到期或空间不足时优先删除最久未播放的自动缓存。正在播放的文件受保护，手动下载的视频和歌曲单独保留。应用完全关闭时不会准点后台清理，下次触发检查时处理。

256MB约束自动缓存，**不代表整个应用总占用**。手动下载、未完成任务、图片等另行占空间，详见使用说明第8页。

## 构建与参与

需要 JDK 17、Android SDK Platform 35、Build Tools 35.0.0。Gradle Wrapper 固定8.11.1并检查发行包校验值。

```bash
chmod +x gradlew
./gradlew assembleDebug testDebugUnitTest lintDebug
```

Windows 使用 `gradlew.bat`。国内依赖连接受限时，可显式加 `-PuseChinaMirrors=true`，默认使用官方仓库。

详细步骤见 [构建说明](docs/BUILD.md)、[贡献指南](CONTRIBUTING.md) 和 [设备交互测试方法](validation/INTERACTION_TESTS.md)。签名私钥、账号凭据、本地SDK路径和构建缓存不在仓库中。

## 验证与限制

0.1.6 的本地构建通过78项JVM测试，Lint无错误；手机11项、平板5项独立交互场景按同一最终APK分次通过。测试使用独立样片、本地模拟接口和模拟账号，实际检查音视频解码、系统媒体控制、手势、布局、缓存轨道及断点恢复。

**这些结果不等于真实B站网络验收。** 本轮新增的真实B站在线播放、字幕接口和CDN下载仍需联调；没有真实账号扣币或互动写入，也没有新增K60/小米平板6物理设备验证。平台会员、地域、登录与内容权限仍然生效。

短视频模式复用普通推荐和当前列表，不是官方私有Story流；歌词以视频可读取的字幕为准，不自动生成。高级/BAS弹幕、直播实时弹幕和官方客户端全部管理操作尚未覆盖。

[验证摘要](validation/interaction-checks-0.1.6.json) · [源码一致性清单](validation/source-integrity-0.1.6.json) · [详细验证边界](docs/VALIDATION-0.1.6.md)

## 许可

按 **GPL-3.0-only** 开源，保留上游原作者、来源和许可。修改及再分发时请遵守相应许可；内容版权仍归平台及创作者。原项目图标来源和第三方依赖说明见 [NOTICE.md](NOTICE.md)。
