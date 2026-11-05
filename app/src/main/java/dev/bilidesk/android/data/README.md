# data：平台接口与本机数据

| 文件 | 责任 |
| --- | --- |
| [BiliApi.kt](BiliApi.kt) | 平台读取、登录流程、WBI、播放计划、字幕、片段查询与历史上报；共用异步请求适配 |
| [AccountWriteClient.kt](AccountWriteClient.kt) | 账号写请求、身份参数、CSRF 与错误分类 |
| [AccountSession.kt](AccountSession.kt) | 保存会话的启动恢复、只读验证、暂时失败与明确过期区分、并发与迟到响应隔离 |
| [AccountNetworkMonitor.kt](AccountNetworkMonitor.kt) | 前台默认网络变化提示；不以网络事件本身判定登录过期 |
| [Models.kt](Models.kt) | 视频、分P、轨道、字幕、收藏和设置等模型与纯策略 |
| [SocialModels.kt](SocialModels.kt) | 评论／回复、排序游标、关系标记与UGC合集解析 |
| [MusicPreferences.kt](MusicPreferences.kt) | 音乐样式／配色设置校验、实际音轨选择与流量估算 |
| [Store.kt](Store.kt) | 设置、历史、缓存索引与文件；`SecureCookies` 的 Keystore 加密存储 |
| [VideoFeedPager.kt](VideoFeedPager.kt) | 分页、刷新串行化、去重、未见内容优先与有界回退 |
| [CachePolicy.kt](CachePolicy.kt) | 自动缓存限额、过期淘汰和保护规则 |
| [DanmakuParser.kt](DanmakuParser.kt) | 弹幕数据解析及边界检查 |

增加接口时复用请求与错误处理，保留平台权限检查和有界重试；不要把 Cookie 附到任意媒体或第三方域名。修改模型或存储时兼容已有本机数据；自动缓存清理不得删除手动下载或当前播放文件。

相关 JVM 测试：`AccountSessionTest`、`CoreTest`、`AccountWriteTest`、`MusicAndRefreshTest`。Android 的 `SessionRecoveryDeviceTest`、`AccountReconnectUiDeviceTest` 检查加密持久化、系统重连与可见账号状态。服务返回与账号操作使用模拟数据验证；真实平台验收需单独记录。

评论最早排序须通过总数定位服务端末页并反向读取，不能只逆序当前页；关注关系不能把拉黑标记当成已关注。新建收藏夹出现未知写入结果时保留错误，让用户刷新确认，不能自动重放创建请求。新增模型与音质／歌词规则由 `MusicLibraryAndSocialTest` 检查。

评论与主页模型见`SocialModels.kt`和`CommentContent.kt`：解析缺省字段、表情、图片与提及，保持64位ID、root／parent关系和互斥赞踩；公开账号资料不写入登录账号缓存。
