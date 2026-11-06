# ui：页面、布局与手势

| 入口 | 责任 |
| --- | --- |
| [BiliApp.kt](BiliApp.kt)、[AppViewModel.kt](AppViewModel.kt) | 导航、列表、共享状态和用户动作入口 |
| [AppChrome.kt](AppChrome.kt)、[Theme.kt](Theme.kt) | 搜索工具栏、主导航、亮暗配色与排版 |
| [LibraryScreen.kt](LibraryScreen.kt) | 账号概览、常用入口、分组服务和共享菜单行 |
| [PlayerScreen.kt](PlayerScreen.kt)、[PlayerSurface.kt](PlayerSurface.kt) | 普通视频页、画面、控制栏和播放器展示 |
| [ShortVideoScreen.kt](ShortVideoScreen.kt)、[ShortVideoActions.kt](ShortVideoActions.kt)、[VideoComments.kt](VideoComments.kt) | 短视频切换、互动、评论抽屉及播放连续性 |
| [MusicLibraryScreen.kt](MusicLibraryScreen.kt)、[MusicPlayerScreen.kt](MusicPlayerScreen.kt) | 音乐库、唱片、歌词、原视频、队列与小播放条 |
| [MusicPlaylists.kt](MusicPlaylists.kt)、[MusicAppearance.kt](MusicAppearance.kt) | 多歌单编辑、合集批量选曲与音乐外观／音质设置 |
| [AccountActions.kt](AccountActions.kt)、[VideoComments.kt](VideoComments.kt) | 关注状态、创建收藏夹、评论排序／点赞／楼中楼 |
| [PlaybackGestures.kt](PlaybackGestures.kt)、[LayoutPolicy.kt](LayoutPolicy.kt)、[AdaptivePlayerLayout.kt](AdaptivePlayerLayout.kt)、[ImmersivePlayback.kt](ImmersivePlayback.kt) | 手势判定、方向与窗口布局、沉浸模式 |
| [PlaybackSettings.kt](PlaybackSettings.kt)、[CacheSettings.kt](CacheSettings.kt)、[DownloadOptions.kt](DownloadOptions.kt)、[FeedDisplaySettings.kt](FeedDisplaySettings.kt) | 播放中设置、缓存选择和列表密度 |
| [CommentComponents.kt](CommentComponents.kt)、[CreatorScreen.kt](CreatorScreen.kt) | 评论富文本、头像、等级、原图、提及查找与用户主页／投稿 |
| [CreatorIdentity.kt](CreatorIdentity.kt)、[CreatorScreen.kt](CreatorScreen.kt) | 作者头像／粉丝、统一主页、UP内搜索与当前投稿定位 |
| [FollowingScreen.kt](FollowingScreen.kt) | 我的关注、分页／刷新、关系更新与主页入口 |
| [AccountScreens.kt](AccountScreens.kt)、[CoinPanel.kt](CoinPanel.kt) | 登录、个人内容、关于与投币交互 |

修改布局要同时考虑手机竖屏、宽横屏和平板分屏；窗口宽度与物理设备类别不能相互替代。横屏全屏使用普通播放控件，不叠加短视频竖屏互动条。评论抽屉不应停止短视频播放；切换唱片/歌词/原视频不能重建音频会话。

0.2.0的视觉规范见[界面设计与维护](../../../../../../../../docs/UI-DESIGN.md)。使用系统字体与现有授权图标，新组件避免把主题颜色、导航逻辑与账号／播放服务混在一起；演示数据和封面只放在`androidTest`。

手势需明确长按、横滑、上下滑和倍率锁定的优先级，左侧调亮度、右侧调音量。设置变化应立即影响绘制或播放器状态，避免仅更新滑块外观。

布局与手势规则由 `FeedAndGestureTest`、`InteractionPolicyTest` 等 JVM 测试覆盖；真实触摸、系统栏、短视频与平板交互按设备测试文档回归。新增页面前先查看现有导航与状态来源，避免复制独立播放器或下载管理器。

音乐根入口默认显示播放器；歌单库与搜索单独导航并保留小播放条。平板横屏可同时呈现歌单和播放器。收藏按钮保存到本机歌单，歌曲选项另保留B站收藏入口，不能在未说明的情况下把本机操作伪装成云同步。

紧凑竖屏的音乐根入口由`BiliApp`复用完整`MusicPlayerScreen`，隐藏主导航；`AppViewModel`记住进入前的根板块，收起与系统返回恢复同一目标。宽窗口继续使用原有导航／双栏；窗口变化只切换展示组件，不停止或重新创建播放器。完整播放器和普通Scaffold各负责自己可见的通知，避免音乐根页没有Snackbar承载入口。

普通视频与短视频共用评论组件：切换排序重建分页任务，线程视图不操作媒体，点赞通过评论id协调状态与防连点；错误保留供用户重试。倍速锁定提示不使用会截获触摸的中央Surface，暂停状态不显示锁定标记。

0.2.1评论按主评论／线程分别持有分页和LazyListState，打开主页使用全窗口Dialog保留底层composition，系统返回和箭头都恢复同一个列表实例。切换视频／账号／排序或主动刷新会重建对应分页；不要把CommentFeedState提到全局跨账号复用。

富文本保留原始message和UTF-16偏移，表情与@链接仅负责呈现；已知UID直接导航，缺少UID才按昵称查询，同名结果需要选择。不得从昵称猜测UID，不能把公开名片following布尔关系当成关注数量。赞踩与回复继续通过同一AccountWriteClient，不自动重试结果未知的写入。

0.2.2：`CreatorIdentity`异步显示头像与粉丝数；`CreatorScreen`统一主页搜索与当前投稿定位；`FollowingScreen`负责我的关注分页与状态更新。AppViewModel持有主页弹层和可观察短视频会话，打开主页不能释放共享播放器。投稿选择携带同UP分页和关键词，不能回退为普通推荐或普通播放器。所有已有space入口复用此页。

短视频切换会话时，收集分页StateFlow与PagerState都以ShortSession为key；不能让新的selectedIndex与上一队列的旧列表组合，否则选择非首行会被错误截到索引0。主页投稿播放方式按打开页面的路由决定，不能只看后台是否正在听音乐。
