# 0.1.6 设备交互回归

本页保留历次方法。当前版本的实际结果见[0.2.0验证记录](../docs/VALIDATION-0.2.0.md)。

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

## 0.1.8 音乐与账号互动

`SocialMusicDeviceTest`使用离线拦截的假账号，实际点击普通播放器中的关注／取消、评论赞／取消、楼中楼、三种排序及新建收藏夹；核对写入次数、CSRF、私密标记和最早分页请求。批量歌单场景实际勾选UGC合集与分P，保存并重建存储对象、创建／改名／删除歌单，验证保存／删除不改变当前队列，切换下一首选择正确cid。

省流场景使用Media3的`AnalyticsListener.onLoadStarted`记录播放器实际读取的媒体URI，确认最初只有独立音轨，显式点唱片后才加载和解码视频，同时核对纯音乐不调用模拟视频CDN探测地址。这个场景使用本地独立音／视频文件，证明媒体源选择和实际解码行为，不测量真实运营商账单，也不证明平台当前返回哪种音质。外观场景实际选择样式、读取恢复设置并截图。

`NewInteractionsDeviceTest`在锁定4倍后用屏幕触摸暂停、确认无锁定标记、再次触摸恢复，再恢复1.25倍。原来的`MusicModeDeviceTest`继续覆盖歌词点击跳转、搜索小播放条、后台、MediaController下一首、原视频画面、媒体项身份和平板双栏；进入歌单库的步骤按新的播放器首页更新。所有互动写请求只在测试夹具内返回，不操作真实账号。

播放测试类建议分独立instrumentation进程运行，类之间对专用模拟器上的测试应用执行`am force-stop`，保留独立样片文件。`VideoTouchInput`由系统`input touchscreen swipe`生成完整轨迹，避免多个测试端Binder调用把快速移动延迟为长按；按住倍速与进度测试仍发送明确的DOWN／保持／MOVE／UP。`ShortVideoDeviceTest`失败时记录播放器状态、暂停原因与调用位置，不能通过强行恢复播放掩盖异常。

## 0.1.9 手机音乐导航

`MusicNavigationDeviceTest`实际从分区进入音乐，检查手机竖屏主导航隐藏，搜索／歌单返回后保持沉浸，播放器通知可见；旋转横屏后导航恢复，转回竖屏再次隐藏。实际点击播放器返回箭头与系统返回，核对原分区和随后进入的「我的」都能正确恢复、小播放条可用、曲目身份不变且音频持续解码。平板同场景保留导航并检查横屏左歌单／右播放器。

本次继续运行`MusicModeDeviceTest`和`SocialMusicDeviceTest#audioSourceDoesNotLoadVideoUntilRequestedAndAppearanceIsSaved`，分别覆盖歌词／后台／媒体会话，以及三种样式、底部按钮对齐、纯音频和显式视频切换。每类独立进程，不能把这三个方法表述为重新通过全部0.1.8社交和缓存回归。

## 0.2.0 界面与主题

`VisualRefreshDeviceTest`在原有离线夹具上仅拦截推荐、分区与搜索列表，生成标注`UI DEMO`的独立几何插图。实际点击标签、搜索、头像与个人页、主题选项和音乐控制，检查暗色主题的系统图标明暗标志、截图布局及音频持续解码。原包ARM64库只做本地静态检查；演示不依赖原包代码、素材或运行环境。

`FeedDensityDeviceTest`继续验证实际重排／保存、标题随列表滚走和分页错误停止重试；手机两个方法、平板密度与列数方法分别运行。音乐导航／歌词／后台／样式、普通全屏手势、短视频互动和平板播放设置分别在独立进程回归，统计以本轮各方法实际通过结果为准。

## 0.2.1 评论与长按提示

用`CommentsDeviceTest`检查元数据、赞踩、楼中楼指定回复、彩色链接、提及查找、头像主页、投稿播放、原图、分页与滚动恢复。`InteractionFixture(richComments = true)`使用CommentTestData独立绘制头像／配图和模拟用户；数据仅在androidTest。失败场景模拟评论读取被拒和发出写入后丢失响应，断言没有自动重试与重复发布。

分别运行普通手机、平板；短视频场景旋转到横屏，核对评论与主页没有替换当前MediaItem且播放持续。NewInteractionsDeviceTest与ImmersiveGesturesDeviceTest在真实长按加速后检查临时速度文字框不存在，再完成下拉锁定／解除或横滑调进度。不可用关闭手势来让“没有提示”断言通过。
