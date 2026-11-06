# 0.2.2 验证记录

139项JVM测试通过，无失败或跳过；Lint 0错误／38警告。手机11项、平板9项独立场景在同一最终主APK上通过，其中手机包含匿名真实网络音视频解码及30秒拖动；其他平台账号操作均为本地模拟响应。APK版本0.2.2 / code13，SHA256 `2586d565989ef9bd90fd41457a53fdf8aa18652311d27cce1e75d2795399915d`，与0.2.1同升级证书，16KB文件对齐验证通过。

## 本版检查

- `NetworkAndCreatorTest`：头像与日期持久化、精确索引范围与XML转义、自动画质限制与编码分组、显式备用线路、失败快照与账号隔离／限额／过期、投稿定位请求上限、GET超时／阻塞DNS总超时与不重试拒绝、HEVC配置异常及网络错误分类、服务器忽略Range时拒绝读取视频前缀。
- `NetworkPlaybackDeviceTest`：真实索引MP4音视频解码，高低码率自适应、音视频各自中断后切备用地址、指定Range字节完整性、65秒／12秒／82秒跨段拖动、断线恢复并保持暂停、纯音乐不读取视频以及原视频切换的媒体项身份。
- `CreatorAndFollowingDeviceTest`：作者头像／粉丝数、主页浏览不断播、限定UP的投稿搜索与当前视频定位、短视频队列保持、我的关注入口与取消关注后的更新。
- 原评论返回保位、楼中楼、音乐、短视频横屏与手势继续回归。

## 可复现条件

设备检查使用Android15/API35的自建手机与平板模拟器。所有账号响应和写入均由`androidTest`中的本地拦截器提供；测试不使用真实账号、不发送真实评论、关注或投币操作。

播放网络测试使用Big Buck Bunny公开样片，由FFmpeg生成95秒的独立AAC和两档H.264索引MP4。样片不进入应用APK或源码归档。示例转换参数：

```sh
ffmpeg -i interaction-sample.mp4 -t 95 -an -vf scale=640:-2 -c:v libx264 -b:v 700k -g 48 -keyint_min 48 -sc_threshold 0 -movflags +dash+global_sidx -frag_duration 2000000 high.mp4
ffmpeg -i interaction-sample.mp4 -t 95 -an -vf scale=320:-2 -c:v libx264 -b:v 160k -g 48 -keyint_min 48 -sc_threshold 0 -movflags +dash+global_sidx -frag_duration 2000000 low.mp4
ffmpeg -i interaction-sample.mp4 -t 95 -vn -c:a aac -b:a 64k -movflags +dash+global_sidx -frag_duration 2000000 audio.m4a
```

将三个文件放入测试应用外部文件目录的`network-media/`，原始样片放在同目录的`interaction-sample.mp4`。以`-e playback_fixture true`运行对应测试类。网络传输由测试拦截器按Range读取真实文件，注入限速和读到一半断开，使用真正的Media3解析器、索引、码率选择和解码器；它没有模拟真实DNS、TLS、运营商路由或哔哩哔哩CDN调度。JVM的HTTP Range测试另外使用本地MockWebServer。

## 边界

没有完成K60／Pad6物理设备上的Wi-Fi、移动数据测速，也没有官方App同条件对照。测试通过不意味着任意网络下瞬时起播、任意时间点零缓冲或保证官方客户端同等性能。登录权限、平台风控、CDN、编码与线路仍会影响体验。

资料与关注列表为模拟数据，头像和封面由测试代码绘制并带`UI DEMO`标记。样片来源与许可见[NOTICE](../NOTICE.md)。真实公开视频验证中发现Media3 1.6.1的HevcConfig解析异常被包装为2000；回归策略只对该视频配置解析栈或解码错误回退H.264，不对普通网络异常重载编码。

网络实现参考[Media3网络栈](https://developer.android.com/media/media3/exoplayer/network-stacks)和[固定1.6.1版DashMediaSource](https://github.com/androidx/media/blob/1.6.1/libraries/exoplayer_dash/src/main/java/androidx/media3/exoplayer/dash/DashMediaSource.java)。
