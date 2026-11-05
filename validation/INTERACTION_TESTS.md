# 0.1.6 设备交互回归

JVM测试与Lint不需要测试账号。真实B站播放测试默认使用测试参数指定的BV或测试源码中的公开视频；若平台返回412，应停止重复请求，不能把该运行计为通过。

交互可以通过显式离线夹具参数独立验证。`InteractionFixture`只在`androidTest`中，主APK不包含模拟响应、独立样片、测试代理或DNS设置。夹具使用真实Media3解码、原生播放器及系统触摸输入，API返回值在测试客户端中注入；它验证播放器交互，不能证明B站网络接口成功。最终回归在模拟器断网环境进行，避免启动页面的后台读取接触平台。

样片来自Media3公开测试目录：<https://storage.googleapis.com/exoplayer-test-media-0/BigBuckBunny_320x180.mp4>。大小64,657,027字节，SHA256 `f78f39603e6774907f2faafabf26a667f4a6fc31769ec304a8a8f7c62d280508`。该媒体不随源码或应用分发。仅需运行设备交互回归时自行下载并校验。

安装debug与androidTest APK后，将该文件推到测试应用的外部私有目录：

```powershell
adb shell mkdir -p /sdcard/Android/data/dev.bilidesk.android/files
adb push interaction-sample.mp4 /sdcard/Android/data/dev.bilidesk.android/files/interaction-sample.mp4
adb shell am instrument -w -r -e playback_fixture true -e class dev.bilidesk.android.ShortVideoDeviceTest dev.bilidesk.android.test/androidx.test.runner.AndroidJUnitRunner
```

同样的可选参数适用于`ImmersiveGesturesDeviceTest`与`TabletInteractionDeviceTest`。`DanmakuRenderDeviceTest`直接扫描生产绘制器输出的像素；`FeedDensityDeviceTest`与`FeedAndStorageDeviceTest`使用明确的测试列表、文件和失败响应，检查真实滚动/下拉/重排/重试与文件保护。

`ShortActionsDeviceTest`另外使用明确的模拟账号，仅在本地拦截器中记录点赞/取消、收藏、模拟投币等请求与状态，系统分享由测试监视器拦截；不接触真实账号或平台。普通播放交互使用只读模拟响应。

`CacheRecoveryDeviceTest`使用`InteractionFixture(downloadMedia=true)`把同一独立样片作为限速的模拟206响应。运行真实前台下载服务，点击暂停，重新创建下载管理器与Activity验证任务和文件保留，再点击继续，检查首个续传偏移、合流后的AVC/音轨和离线实际解码；另外验证取消删除部分文件并保护已完成缓存。这不验证B站真实CDN下载。10项`RangeDownloaderTest`通过MockWebServer验证断线、备用源、Range忽略/位置异常、ETag变化、未知总量、取消阻塞读取及平台412停止重试。

第一次进入沉浸模式时，设备可能显示系统“Got it”提示，测试按正常方式确认。播放器属性在主线程读取，触摸通过设备输入发送；没有真实投币、评论发送、点赞、关注或私信操作。夹具在结束时释放临时播放器及媒体会话，恢复原Graph对象。

验证结果及实际限制见 [验证说明](../docs/VALIDATION-0.1.6.md)。物理K60、Pad6的系统手势、系统节电、解码器、分屏/自由窗口仍需要真机复核。

## 音乐、缓存选择与推荐刷新

`MusicModeDeviceTest`在同一播放器上验证音频解码、字幕时间戳、点歌词跳转、搜索页小播放条、下一首优先队列、真实MediaController标准下一首命令、退到系统桌面仍有音频输出、无字幕原视频画面、切换前后MediaItem保持及平板横屏左右布局。测试返回应用时保留MAIN/LAUNCHER Intent，避免ActivityScenario因onNewIntent后的Intent不匹配而丢失生命周期跟踪；生命周期查询在测试线程进行。

`MusicTestMedia`只在测试机上从上述样片提取45秒视频轨和音轨，以便检查DASH双流、视频轨开关与音频单独缓存。两种画质标识的模拟响应都使用同一个320×180样片，仅验证选择参数、缓存标识与实际轨道，不声称样片本身达到720P。测试封面是两个纯色小图；字幕是三句明确标记的模拟数据。

`CacheOptionsDeviceTest`实际点击缓存画质和类型，核对请求qn、文件键、MediaExtractor音视频轨，分别播放本地M4A和MP4并确认解码输出。与原有CacheRecoveryDeviceTest一起覆盖新选项和旧续传机制，均不是B站真实CDN下载验收。

`NewInteractionsDeviceTest`用真实长按/下拉/继续移动/松手验证选择4倍及恢复1.25倍，检查选择时无互动按钮覆盖、没有常驻教学文案、标题接近底部。刷新用固定的旧/新列表响应，检查新内容出现在第一屏；平板使用真实1600×2560/320dpi并旋转横屏。JVM回归另验证等待追加请求、刷新去重/未见优先和有界后续读取。

系统桌面切换必须等待桌面可见并停留，不能刚按Home立即重新启动而把过渡当作后台播放。模拟触摸再次长按已锁定的速度时也需等待长按时间，不能因当前速度已等于锁定值就马上发送下拉。

## 0.1.7 登录恢复

`AccountSessionTest` 是纯JVM状态回归；`SessionRecoveryDeviceTest` 使用专用偏好文件和假账号检查离线恢复、加密资料、读取失败保留、退出隔离。`AccountReconnectUiDeviceTest` 会实际切换测试设备的 Wi-Fi/移动数据开关，验证系统默认网络回调自动恢复可见账号；所有平台响应仍在本地拦截，不能用作真实平台会话验收。仅在项目专用模拟器上运行网络开关场景。

`savedIdentityPersistsAcrossAnActualProcessRestart` 需要分两次运行，不能只在同一进程重建对象。先以 `-e sessionPhase seed` 运行该方法，确认成功；对专用测试应用执行 `am force-stop dev.bilidesk.android`，再以 `-e sessionPhase restore` 运行同一方法。后者在离线模拟传输下检查前一进程保存的加密资料，并清理假账号。不要将跳过/假设不满足的测试算作通过，也不要把测试产生的应用数据打入源码或APK。
