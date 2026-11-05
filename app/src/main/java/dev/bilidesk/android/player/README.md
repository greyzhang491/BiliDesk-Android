# player：播放引擎、音乐与弹幕

| 文件 | 责任 |
| --- | --- |
| [PlayerEngine.kt](PlayerEngine.kt) | 共享 ExoPlayer、播放状态、轨道与清晰度、恢复/重试、字幕和历史同步 |
| [MusicController.kt](MusicController.kt) | 歌单读取、音乐会话、字幕缓存与展示状态 |
| [MusicQueue.kt](MusicQueue.kt) | 队列、下一首优先、顺序/循环/随机与恢复数据 |
| [MusicSessionPlayer.kt](MusicSessionPlayer.kt) | 系统媒体命令与音乐上一首/下一首的连接 |
| [PlaybackService.kt](PlaybackService.kt) | MediaSessionService、前台播放与通知 |
| [DanmakuView.kt](DanmakuView.kt) | Canvas 绘制、轨道避让、过滤与播放器时钟同步 |

视频、音乐、小播放条与系统媒体控制共用同一播放引擎。修改展示或页面导航时保留当前 MediaItem、进度和音频会话；歌曲切换应明确更新队列，而非额外创建播放器。后台、取消与释放行为需覆盖 Activity 和服务的生命周期。

纯队列与模式逻辑由 `MusicAndRefreshTest` 等 JVM 测试验证；解码、歌词跳转、后台音频与真实系统下一首由 `MusicModeDeviceTest` 验证；弹幕设置变化还需 `DanmakuRenderDeviceTest` 检查绘制结果。
