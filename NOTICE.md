# 来源与许可

本项目为独立的安卓移植版本，参考、改写和移植了以下开源项目的实现思路与代码逻辑：

- 项目：BiliDesk
- 作者 / 仓库所有者：ABING1111 及项目贡献者
- 仓库：https://github.com/ABING1111/BiliDesk
- 参考提交：`37aa833b85542a7bf43bb75099ab9716fc1fe1f3`
- 上游许可：GNU General Public License version 3
- 本移植版本：GPL-3.0-only，完整许可见 `LICENSE`

移植及修改映射：

| 上游 | 安卓实现 | 修改 |
| --- | --- | --- |
| `Services/ApiClient.cs` | `data/BiliApi.kt`、`data/Models.kt` | C# 改写 Kotlin；OkHttp、协程；严格基于平台授权返回的媒体流；有界退避与签名刷新 |
| `Services/PassportService.cs` | `BiliApi.kt`、`ui/AccountScreens.kt` | 安卓二维码与官方 WebView 登录；Android Keystore AES-GCM 凭据存储 |
| `Helpers/DanmakuParser.cs` | `data/DanmakuParser.kt` | Kotlin protobuf 读取器；边界检查；UTF-8、颜色、模式及权重字段 |
| 弹幕过滤与运动逻辑 | `player/DanmakuView.kt` | Android Canvas、播放器时钟、轨道避让、重复和关键词过滤 |
| `Services/SponsorBlockService.cs` | `BiliApi.sponsors`、`SponsorRules` | 同一第三方数据源；3 秒超时；分 P、类别、动作、票数校验；默认关闭 |
| `PlayerService` / `PlayerWindow` / `CdnService` | `player/PlayerEngine.kt` | Media3 替换 Windows LibVLC；音视频源合并、有限测速、备用线路重试、画中画、媒体服务 |
| `ShortVideoCache` / `DashRemuxer` | `download/Downloads.kt` | Android 前台服务、MediaExtractor、MediaMuxer，无需 FFmpeg 原生二进制 |
| WPF 界面与桌面集成 | `ui/`、`MainActivity.kt` | 从头实现 Compose 安卓界面，以手机手势与媒体通知替代桌面操作 |
| `BiliDesk/Assets/app.png`、`docs/logo.png` | `res/drawable-nodpi/bilidesk_launcher_art.png`、`bilidesk_brand.png` | 沿用原版粉底猫耳头像，图片内容不改动；新增安卓自适应图标图层与首页/关于页显示 |

图标来源为上述参考提交中的原项目资源，保留上游来源说明与 GPL-3.0 许可；安卓移植版与上游作者没有官方关联。桌面图标适配参考 [Android 自适应图标说明](https://developer.android.com/develop/ui/compose/system/icon_design_adaptive)。

0.2.0界面改版参考了用户提供、元数据标注为`tv.danmaku.bili 7.14.1`的APK主题与尺寸资源，提炼配色、间距与内容层级。界面由本项目的Compose组件独立实现，素材使用原有授权BiliDesk形象、AndroidX Material Icons和系统字体；参考APK仅用于本地分析。文档中的`UI DEMO`封面由设备测试中的Android Canvas绘制，使用方式见[设计说明](docs/UI-DESIGN.md)。

主要第三方依赖（各自许可仍适用）：

- AndroidX / Jetpack Compose / Media3：Apache-2.0。
- Kotlin 与 kotlinx.coroutines：Apache-2.0。
- OkHttp：Apache-2.0。
- Coil：Apache-2.0。
- ZXing Core：Apache-2.0。
- JUnit 4（仅测试）：EPL-1.0。
- JSON-java（仅 JVM 测试）：JSON License；不打包进应用，应用使用 Android 系统 JSON API。

视频、图片、弹幕等内容均通过平台接口按用户操作获取，版权属于哔哩哔哩及各创作者。应用不包含预置视频内容。第三方片段标注服务与本项目独立，默认不访问。
