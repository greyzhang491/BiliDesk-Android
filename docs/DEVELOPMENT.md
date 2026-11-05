# 修改与验证指南

[架构导览](ARCHITECTURE.md) · [构建环境](BUILD.md) · [贡献约定](../CONTRIBUTING.md)

## 先找到对应职责

源码包前缀为 `app/src/main/java/dev/bilidesk/android/`。

| 要修改的行为 | 优先阅读 | 相关回归 |
| --- | --- | --- |
| 推荐刷新、追加与去重 | `data/VideoFeedPager.kt`、`data/BiliApi.kt`、`ui/BiliApp.kt` | `MusicAndRefreshTest`、`FeedAndGestureTest`、`NewInteractionsDeviceTest` |
| 清晰度、线路、解码与播放恢复 | `data/Models.kt`、`player/PlayerEngine.kt`、`ui/PlayerScreen.kt` | `CoreTest`、`InteractionPolicyTest` 与播放设备测试 |
| 音乐模式、下一首与队列 | `player/MusicQueue.kt`、`MusicController.kt`、`MusicSessionPlayer.kt`、`ui/MusicPlayerScreen.kt` | `MusicAndRefreshTest`、`MusicModeDeviceTest` |
| 横屏、平板和沉浸模式 | `ui/LayoutPolicy.kt`、`AdaptivePlayerLayout.kt`、`ImmersivePlayback.kt` | `FeedAndGestureTest`、`TabletInteractionDeviceTest`、`ImmersiveGesturesDeviceTest` |
| 短视频动作、速度与评论 | `ui/ShortVideoScreen.kt`、`ShortVideoActions.kt`、`PlaybackGestures.kt`、`VideoComments.kt` | `ShortVideoDeviceTest`、`ShortActionsDeviceTest`、`NewInteractionsDeviceTest` |
| 弹幕解析与即时设置 | `data/DanmakuParser.kt`、`player/DanmakuView.kt`、`ui/PlaybackSettings.kt` | `CoreTest`、`DanmakuRenderDeviceTest` |
| 缓存质量、续传与空间 | `ui/DownloadOptions.kt`、`download/`、`data/CachePolicy.kt`、`data/Store.kt` | `RangeDownloaderTest`、`CacheRecoveryDeviceTest`、`CacheOptionsDeviceTest` |
| 登录、Cookie 与互动写入 | `data/BiliApi.kt`、`AccountWriteClient.kt`、`Store.kt`、`ui/AccountScreens.kt` | `AccountWriteTest`、使用模拟账号的设备测试 |

## 开发约定

保持现有 Kotlin 命名和4空格缩进，新文件使用 UTF-8，并保留适用的 SPDX 与来源声明。`.editorconfig` 约束新编辑内容；不要为小改动批量格式化无关文件。

页面专注显示状态和收集动作。复用队列、分页、缓存或手势策略时先看现有实现，避免为某个页面复制第二套状态。网络和磁盘操作不得阻塞主线程；捕获取消异常时应继续抛出，避免暂停或退出后任务仍在运行。

新增持久化字段需要兼容旧版本默认值；修改下载键、文件布局或缓存索引时检查旧任务恢复。不要在升级时默认清空用户数据，也不要把自动缓存淘汰应用到手动下载。

新依赖须说明用途、许可与 APK/原生库影响。不要引入共享账号、硬编码令牌、私有 API 密钥或签名私钥。新增网络接收方、权限或数据用途时同步更新 [PRIVACY](../PRIVACY.md)、[NOTICE](../NOTICE.md) 和相关用户说明。

## 选择合适的验证

运行项目基本检查：

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
```

纯策略修改重点验证边界、顺序和取消等行为；播放器、手势、系统媒体控制和下载文件变化需要按 [设备测试方法](../validation/INTERACTION_TESTS.md) 运行相关场景。文档修改检查链接、截图说明与代码一致性即可。

本地 HTTP 测试使用 MockWebServer。涉及断开后重新连接时，测试服务器和请求应使用相同的明确回环地址，避免依赖不同系统的 `localhost` IPv4/IPv6 解析。测试仍须验证实际写入偏移和文件内容，不能以关闭断线模拟、跳过失败测试或放宽断言代替修复。

设备夹具不随主 APK 打包。使用模拟账号、独立样片或模拟 CDN 时，报告中明确这些条件；它们不能证明真实平台接口或账号操作已经通过。遇到平台验证、权限或频率限制时停止重复操作，转官方渠道处理。

## CI 失败怎么查

1. 打开对应提交的 Actions 运行，先看失败的具体步骤。
2. 若为单元测试，下载 `Android-check-reports`，查看测试类、异常和请求条件；若为 Lint，查看 HTML 报告。
3. 在同一提交和相同工具链下复现，做最小修改后重新推送。不要把上传成功与检查成功混为一谈：Git 提交可以已经存在于远端，而自动检查仍然失败。
4. CI APK 使用临时调试签名。发布时另行核对经过验证的 APK、签名兼容性、对应源码和 SHA256。

## 提交与发布

一个提交解释一个明确问题；PR 写清触发条件、修改后的行为、验证结果与尚未验证的部分。更新功能时同时更新版本说明或使用说明，引用第三方逻辑或资源时补充来源。

Release 使用开发预览标记，附件包括安装包、公开源码、使用说明和校验值。签名私钥留在维护者安全环境中，不能进入 Git 或公开构建产物。源码一致性清单记录运行源码与已交付 APK 的对应关系；仅测试或文档变化也应如实说明，不能把重新编译的临时签名 APK 冒充既有已验证文件。
