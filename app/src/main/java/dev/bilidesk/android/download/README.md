# download：离线任务与续传

| 文件 | 责任 |
| --- | --- |
| [Downloads.kt](Downloads.kt) | 画质/音频任务键、队列持久化、暂停/继续/取消、地址刷新、合流、缓存登记及下载前台服务 |
| [RangeDownloader.kt](RangeDownloader.kt) | 有界 Range、断点侧车、ETag 校验、有限重试、平台返回的备用线路、取消阻塞读取与字节进度 |

请求中的画质与类型必须对应实际授权轨道。保持视频/音频及不同画质的文件键独立；任务恢复不得长期保存带签名的媒体 URL。HTTP 200 忽略 Range 时安全重启文件，异常 Range 或 ETag 变化时不能拼接不同资源。

暂停保留可续传文件，取消清理所属任务的部分文件；合流完成后才登记最终缓存。总大小未知时显示已下载字节，避免制造百分比。不要用无限重试、任意镜像或绕过平台限制解决失败。

`RangeDownloaderTest` 验证中断偏移、精确文件内容、ETag、备用源和取消。设备侧 `CacheRecoveryDeviceTest`、`CacheOptionsDeviceTest` 检查任务恢复、轨道、合流和离线实际播放。模拟媒体结果不能代替真实 CDN 下载验收。
