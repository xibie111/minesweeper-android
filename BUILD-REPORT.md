# 构建与验证记录

日期：2026-10-11 17:34（北京时间，UTC+8）。版本：1.0.2。

## 最终结果

执行命令：

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest --console=plain
```

实际结果：`BUILD SUCCESSFUL`，退出码 0。

| 检查 | 实际结果 |
|---|---|
| 核心规则 JVM 测试 | 24 项通过，0 失败 |
| JSON 存档与统计 JVM 测试 | 15 项通过，0 失败 |
| 应用级存档队列 JVM 测试 | 5 项通过，0 失败 |
| 总计 | 44 项通过，0 失败、0 错误 |
| Android lint | 通过，0 错误、13 个非阻断警告 |
| 应用 debug APK | 构建成功 |
| 界面测试 APK | 构建成功，包含 16 项 instrumentation 测试，未设备运行 |
| Android Studio 预览代码 | 浅色手机、深色横屏、小屏大字号组件编译成功；未实际渲染验收 |
| 改动范围对比 | 规则、计时、存档、MainActivity 文件与 1.0.1 逐字节一致；手势识别块忽略空白后相同 |
| APK 签名 | `apksigner verify --verbose` 通过，APK Signature Scheme v2 有效 |
| 包信息 | `aapt dump badging` 确认包名、版本、最低 API 和应用标签 |

lint 警告包括固定依赖有新版、target API 的新版提示、自适应图标的 v26 文件夹限定符建议，以及 SharedPreferences 的 `commit`/KTX 建议。`commit` 在专用 IO actor 中调用，以获得实际落盘失败结果，不阻塞游戏主线程。未禁用检查，未添加忽略错误的 baseline。

本页保留 2026-10-11 的实际验证汇总。包含机器路径或主机名的原始构建日志与测试 XML 留在本地，没有提交到公开仓库。此次先修正新增仪器测试的语义 API 引用，再完成全套编译、单元测试、lint 和打包，未声称第一次编译零错误。本次上传 GitHub 时重新检查了安装包版本、文件哈希和签名，没有重新构建或执行单元测试。

## 应用安装包

- 文件：[little-island-minesweeper-v1.0.2-debug.apk](https://github.com/xibie111/minesweeper-android/releases/download/v1.0.2/little-island-minesweeper-v1.0.2-debug.apk)
- 应用名：小岛扫雷
- 包名：`com.example.minesweeper`
- 版本：1.0.2 / versionCode 3
- 最低系统：Android 8.0 / API 26
- targetSdk / compileSdk：36 / 36
- 文件大小：22,602,942 字节（约 21.56 MiB）
- SHA-256：`D067E5C23C2F54C34EC09343944AC5EE778F73AE853E9D457C3B060E90664EFD`
- debug 调试签名；非应用商店发布包。

## 本次界面升级

本轮按用户指定的界面、布局、动效和素材细节完善，沿用 1.0.1 的绿色与暖橙色素材：

- 缩放/居中工具栏移到棋盘外，保留 48dp 触摸区域，显示缩放比例。
- 480dp 起的宽横屏侧栏、小屏独立滚动控制区、大字号统计布局与长难度标题省略保护。
- 明确的揭格/插旗选项、圆角统计卡、安全格进度、帮助弹窗、难度/成绩/结果排版。
- 180ms 揭格和插旗反馈、260ms 结果插画入场；动画只改局部绘制，不改变落子、计时、手势或统计，恢复不重播揭格动效。
- 设置整行带标签的单一动作；棋盘方向无障碍平移，半露格子语义节点裁在实际可见区域，避免窄视窗放大后的导航盲区。
- 旋转时保留新局候选配置；增加三个 Android Studio 预览组件。

发布前已将规则、ViewModel、两份存档实现和 MainActivity 与上一版源码 ZIP 逐字节对比，结果一致；手势块仅有布局嵌套造成的缩进变化，五张生成原图未修改。详细对比记录留在本地。素材记录与完整提示词见 `assets/`。静态素材 HTML 不是应用运行截图。

编译的 16 项仪器测试包含原有 9 项触摸契约、2 项工具栏/放大后无障碍导航用例、5 项小屏/横屏/大字号/长标题/设置和模式语义用例。代码编译成功不表示这些设备用例已经通过。

## 尚未完成的设备验收

本机 `adb devices` 无连接设备，未配置 AVD，模拟器加速检查返回 6，提示缺少 Android Emulator hypervisor driver。未改变系统虚拟化设置、安装驱动或重启。

因此没有进行模拟器或真机安装启动验收，也没有实际运行 16 项界面仪器测试或 Android Studio 预览渲染。触屏、视觉布局、动效、系统动画比例为零、旋转、后台计时、进程重建与 TalkBack 仍需设备验证。源码中保留完整测试；连接设备后执行：

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest --console=plain
```

仓库保留完整 Wrapper、源码、测试、素材及本验证汇总，不包含本机 SDK 路径、Gradle 缓存、构建临时文件、原始机器日志或签名私钥。
