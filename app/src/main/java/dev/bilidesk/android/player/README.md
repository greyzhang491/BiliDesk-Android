# player：播放引擎、音乐与弹幕

| 文件 | 责任 |
| --- | --- |
| [DashStreams.kt](DashStreams.kt) | SegmentBase到MPD、自动码率范围与完整备用URL选择 |
| [ResilientMediaDataSource.kt](ResilientMediaDataSource.kt) | 正偏移Range检查、独立音视频断点续传与有限线路切换 |
| [PlaybackFallbackPolicy.kt](PlaybackFallbackPolicy.kt) | 视频配置／解码错误与网络错误分类，一次性兼容回退 |
| [PlayerEngine.kt](PlayerEngine.kt) | 共享 ExoPlayer、播放状态、轨道与清晰度、恢复/重试、字幕和历史同步 |
| [MusicController.kt](MusicController.kt) | 歌单读取、音乐会话、字幕缓存与展示状态 |
| [MusicQueue.kt](MusicQueue.kt) | 队列、下一首优先、顺序/循环/随机与恢复数据 |
| [MusicLibrary.kt](MusicLibrary.kt) | 本机多歌单、分P身份、独立持久化与合集选曲模型 |
| [MusicSessionPlayer.kt](MusicSessionPlayer.kt) | 系统媒体命令与音乐上一首/下一首的连接 |
| [PlaybackService.kt](PlaybackService.kt) | MediaSessionService、前台播放与通知 |
| [DanmakuView.kt](DanmakuView.kt) | Canvas 绘制、轨道避让、过滤与播放器时钟同步 |

视频、音乐、小播放条与系统媒体控制共用同一播放引擎。修改展示或页面导航时保留当前 MediaItem、进度和音频会话；歌曲切换应明确更新主队列；仅用户主动查看原视频时创建独立静音画面播放器。后台、取消与释放行为需覆盖 Activity 和服务的生命周期。

纯队列与模式逻辑由 `MusicAndRefreshTest` 等 JVM 测试验证；解码、歌词跳转、后台音频与真实系统下一首由 `MusicModeDeviceTest` 验证；弹幕设置变化还需 `DanmakuRenderDeviceTest` 检查绘制结果。

0.1.8纯音乐只建立音频媒体源，不测速视频CDN。显式显示原视频时才合并视频源，主音轨保持为合并源的第一条时间线，以复用媒体项身份；切源保留进度和暂停状态，允许短暂缓冲。视频解码失败不得在纯听歌时触发视频编码回退或视频备用源请求。

`MusicLibraryState`保存歌单，`MusicQueueState`保存正在播放的队列，两者不可相互隐式覆盖。歌单修改使用串行事务，在I/O线程确认写入后发布状态；添加相同视频的不同cid必须保留，相同歌曲重复添加保持原顺序。本机歌单不归自动缓存淘汰管理，也不随B站退出而删除。

0.2.2：`DashStreams`将服务器SegmentBase索引转换为静态MPD，自动模式限制普通1080P并按编码分组，固定画质不混入其他质量。`ResilientMediaDataSource`按原DataSpec已读偏移续传，每个音视频请求独立处理至多3个原始备用URL；`MediaRangeInterceptor`拒绝不正确的正偏移响应。不得通过替换域名、拼接签名参数或下载完整前缀来模拟seek。纯音乐只构建音频源，兼容源仍保持主音轨媒体项身份。

`PlaybackFallbackPolicy`识别被包装为IO错误的HEVC视频配置异常（例如HevcConfig空VPS列表），一次性回退H.264；不可把所有2000系列都当成编码失败，也不可将音频解析错误归为视频编码问题。

0.2.5：`MusicPicture`按需准备静音视频源，跟随主音轨的播放／暂停、进度与倍速。画面准备、失败与重试不得替换音频MediaItem；关闭画面、切歌和后台需释放画面播放器。没有独立音轨的内容使用明确的兼容路径，不能宣称省去视频流量。`MusicController`提供唱片／歌词／原视频显式状态，并按候选字幕读取与过滤纯声音标记。

`MusicQueue`将下一首优先、历史和随机待播与常规排列分别维护。`MusicQueuePanel`只做拖动预览，drop时通过一次事务持久化；跨协程修改时保留新增、排除已移除歌曲，取消拖动不提交。队列修改与本机歌单修改均由同步协调器观察，但不能替换当前播放媒体。
