# ui：页面、布局与手势

| 入口 | 责任 |
| --- | --- |
| [BiliApp.kt](BiliApp.kt)、[AppViewModel.kt](AppViewModel.kt) | 导航、列表、共享状态和用户动作入口 |
| [PlayerScreen.kt](PlayerScreen.kt)、[PlayerSurface.kt](PlayerSurface.kt) | 普通视频页、画面、控制栏和播放器展示 |
| [ShortVideoScreen.kt](ShortVideoScreen.kt)、[ShortVideoActions.kt](ShortVideoActions.kt)、[VideoComments.kt](VideoComments.kt) | 短视频切换、互动、评论抽屉及播放连续性 |
| [MusicLibraryScreen.kt](MusicLibraryScreen.kt)、[MusicPlayerScreen.kt](MusicPlayerScreen.kt) | 音乐库、唱片、歌词、原视频、队列与小播放条 |
| [MusicPlaylists.kt](MusicPlaylists.kt)、[MusicAppearance.kt](MusicAppearance.kt) | 多歌单编辑、合集批量选曲与音乐外观／音质设置 |
| [AccountActions.kt](AccountActions.kt)、[VideoComments.kt](VideoComments.kt) | 关注状态、创建收藏夹、评论排序／点赞／楼中楼 |
| [PlaybackGestures.kt](PlaybackGestures.kt)、[LayoutPolicy.kt](LayoutPolicy.kt)、[AdaptivePlayerLayout.kt](AdaptivePlayerLayout.kt)、[ImmersivePlayback.kt](ImmersivePlayback.kt) | 手势判定、方向与窗口布局、沉浸模式 |
| [PlaybackSettings.kt](PlaybackSettings.kt)、[CacheSettings.kt](CacheSettings.kt)、[DownloadOptions.kt](DownloadOptions.kt)、[FeedDisplaySettings.kt](FeedDisplaySettings.kt) | 播放中设置、缓存选择和列表密度 |
| [AccountScreens.kt](AccountScreens.kt)、[CoinPanel.kt](CoinPanel.kt) | 登录、个人内容、关于与投币交互 |

修改布局要同时考虑手机竖屏、宽横屏和平板分屏；窗口宽度与物理设备类别不能相互替代。横屏全屏使用普通播放控件，不叠加短视频竖屏互动条。评论抽屉不应停止短视频播放；切换唱片/歌词/原视频不能重建音频会话。

手势需明确长按、横滑、上下滑和倍率锁定的优先级，左侧调亮度、右侧调音量。设置变化应立即影响绘制或播放器状态，避免仅更新滑块外观。

布局与手势规则由 `FeedAndGestureTest`、`InteractionPolicyTest` 等 JVM 测试覆盖；真实触摸、系统栏、短视频与平板交互按设备测试文档回归。新增页面前先查看现有导航与状态来源，避免复制独立播放器或下载管理器。

音乐根入口默认显示播放器；歌单库与搜索单独导航并保留小播放条。平板横屏可同时呈现歌单和播放器。收藏按钮保存到本机歌单，歌曲选项另保留B站收藏入口，不能在未说明的情况下把本机操作伪装成云同步。

紧凑竖屏的音乐根入口由`BiliApp`复用完整`MusicPlayerScreen`，隐藏主导航；`AppViewModel`记住进入前的根板块，收起与系统返回恢复同一目标。宽窗口继续使用原有导航／双栏；窗口变化只切换展示组件，不停止或重新创建播放器。完整播放器和普通Scaffold各负责自己可见的通知，避免音乐根页没有Snackbar承载入口。

普通视频与短视频共用评论组件：切换排序重建分页任务，线程视图不操作媒体，点赞通过评论id协调状态与防连点；错误保留供用户重试。倍速锁定提示不使用会截获触摸的中央Surface，暂停状态不显示锁定标记。
