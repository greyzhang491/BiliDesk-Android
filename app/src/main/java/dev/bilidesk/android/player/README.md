# player：播放引擎、音乐与弹幕

| 文件 | 责任 |
| --- | --- |
| [PlayerEngine.kt](PlayerEngine.kt) | 共享 ExoPlayer、播放状态、轨道与清晰度、恢复/重试、字幕和历史同步 |
| [MusicController.kt](MusicController.kt) | 歌单读取、音乐会话、字幕缓存与展示状态 |
| [MusicQueue.kt](MusicQueue.kt) | 队列、下一首优先、顺序/循环/随机与恢复数据 |
| [MusicLibrary.kt](MusicLibrary.kt) | 本机多歌单、分P身份、独立持久化与合集选曲模型 |
| [MusicSessionPlayer.kt](MusicSessionPlayer.kt) | 系统媒体命令与音乐上一首/下一首的连接 |
| [PlaybackService.kt](PlaybackService.kt) | MediaSessionService、前台播放与通知 |
| [DanmakuView.kt](DanmakuView.kt) | Canvas 绘制、轨道避让、过滤与播放器时钟同步 |

视频、音乐、小播放条与系统媒体控制共用同一播放引擎。修改展示或页面导航时保留当前 MediaItem、进度和音频会话；歌曲切换应明确更新队列，而非额外创建播放器。后台、取消与释放行为需覆盖 Activity 和服务的生命周期。

纯队列与模式逻辑由 `MusicAndRefreshTest` 等 JVM 测试验证；解码、歌词跳转、后台音频与真实系统下一首由 `MusicModeDeviceTest` 验证；弹幕设置变化还需 `DanmakuRenderDeviceTest` 检查绘制结果。

0.1.8纯音乐只建立音频媒体源，不测速视频CDN。显式显示原视频时才合并视频源，主音轨保持为合并源的第一条时间线，以复用媒体项身份；切源保留进度和暂停状态，允许短暂缓冲。视频解码失败不得在纯听歌时触发视频编码回退或视频备用源请求。

`MusicLibraryState`保存歌单，`MusicQueueState`保存正在播放的队列，两者不可相互隐式覆盖。歌单修改使用串行事务，在I/O线程确认写入后发布状态；添加相同视频的不同cid必须保留，相同歌曲重复添加保持原顺序。本机歌单不归自动缓存淘汰管理，也不随B站退出而删除。
