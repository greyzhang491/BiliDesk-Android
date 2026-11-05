# 构建说明

## 环境

- 完整 JDK 17，包含 `javac` 和 `jlink`。
- Android SDK Platform 35、Build Tools 35.0.0、Platform Tools。
- Gradle Wrapper 8.11.1，已随源码提供；首次运行需要联网下载 Gradle 和依赖。

在 Android Studio 打开仓库根目录，或配置环境变量 `JAVA_HOME`、`ANDROID_HOME`。本地也可创建不提交的 `local.properties`：

```properties
sdk.dir=/your/android/sdk
```

## 命令

Linux / macOS：

```bash
chmod +x gradlew
./gradlew assembleDebug testDebugUnitTest lintDebug assembleDebugAndroidTest --console=plain
```

Windows PowerShell：

```powershell
.\gradlew.bat assembleDebug testDebugUnitTest lintDebug assembleDebugAndroidTest --console=plain
```

需要备用镜像时显式加 `-PuseChinaMirrors=true`。第一次构建不能使用离线模式，之后缓存齐全时可用 `--offline`。

应用APK在 `app/build/outputs/apk/debug/app-debug.apk`。JVM报告在 `app/build/reports/tests/testDebugUnitTest/`，Lint报告在 `app/build/reports/lint-results-debug.html`。

## GitHub Actions

推送、Pull Request 和手动触发会编译应用与设备测试包、运行JVM测试和Lint，并保存开发构建产物。工作流不执行真实账号互动，也不自动发布Release。

工作流参考官方 [checkout](https://github.com/actions/checkout)、[setup-java](https://github.com/actions/setup-java)、[Gradle Actions](https://github.com/gradle/actions) 和 [upload-artifact](https://github.com/actions/upload-artifact)。编译所需 Android SDK 由 [setup-android](https://github.com/android-actions/setup-android) 准备。

## 签名与更新

本地或CI首次构建会生成该环境自己的调试签名。它与已发布预览APK的证书可能不同，不能保证覆盖安装已发布版本。CI产物不等于经过同一组设备测试的Release文件。

正式维护应使用维护者自己的发布密钥，并通过本地安全配置或仓库Secrets注入，不把私钥、口令或签名库提交到Git。现有预览版私钥也不随源码公开。安装已发布版本时优先选择Releases；不要为覆盖安装而随意清除用户数据。

## 设备测试

`assembleDebugAndroidTest`仅编译设备测试包；实际运行需要Android设备/模拟器及独立测试样片。完整方法见 [INTERACTION_TESTS.md](../validation/INTERACTION_TESTS.md)。

测试账号和测试媒体不得进入Git。没有授权时不要启用真实账号读写测试；本仓库的互动回归使用本地模拟账号。
