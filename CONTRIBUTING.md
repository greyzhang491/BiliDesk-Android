# 参与开发

请先阅读 [README](README.md)、[架构导览](docs/ARCHITECTURE.md)、[开发指南](docs/DEVELOPMENT.md)、[构建说明](docs/BUILD.md)、[上游来源](NOTICE.md) 和 [GPL-3.0许可](LICENSE)。

## 报告问题

在Issue中写明版本、设备型号与安卓版本、具体入口及复现步骤、BV号或视频链接、完整错误信息，以及官方B站能否完成同一操作。

不要提交密码、Cookie、SESSDATA、bili_jct、登录令牌、有效登录二维码、签名私钥或未脱敏的个人数据。截图也请先检查。涉及账号安全的详细信息不要放入公开Issue，请按 [安全报告说明](SECURITY.md) 私下报告。

## 提交修改

1. 从当前默认分支建立自己的开发分支，保持改动范围清晰。
2. 保留已有用户设置、缓存和升级兼容；默认不增加后台下载或真实账号写入。
3. 代码变更运行 `./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest`，记录与改动有关的验证；纯文档变更核对链接、排版和描述与代码的一致性。
4. 播放器、手势、缓存或平板布局变化请按设备测试文档回归，不把模拟接口结果写成真实平台验收。
5. PR说明具体问题、修改后的行为、验证方法和未验证部分。涉及第三方逻辑或资源时补充来源与许可。

不提交SDK/JDK、构建缓存、`local.properties`、APK、测试媒体或签名库。需要发布二进制时使用Releases附件，并提供相应源码和校验值。

新增网络接收方、权限或数据用途时同步修改 [隐私说明](PRIVACY.md)；修改第三方资源和许可时维护 [NOTICE](NOTICE.md) 与必要声明。使用与分发边界见 [免责声明](DISCLAIMER.md)。
