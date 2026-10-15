# 小岛扫雷 / Minesweeper Android

原生 Kotlin + Jetpack Compose 扫雷应用。简体中文、完全离线，不申请网络或存储权限。最低 Android 8.0（API 26）；包名 `com.example.minesweeper`。

[下载 v1.0.2 APK](https://github.com/xibie111/minesweeper-android/releases/download/v1.0.2/little-island-minesweeper-v1.0.2-debug.apk) · [查看 Release](https://github.com/xibie111/minesweeper-android/releases/tag/v1.0.2)

仓库包含完整应用源码、测试、生成素材和 Gradle Wrapper。安装包位于 Release，使用 debug 调试签名。

## 功能与操作

- 初级 9×9 / 10 雷、中级 16×16 / 40 雷、高级 30 列×16 行 / 99 雷。
- 自定义行列 5～30，雷数 1～总格数减 9。
- 首次有效揭格才布雷，首击及周围实际存在的八格安全。普通随机棋盘可能需要猜测。
- 单击揭格，长按插旗；可切换插旗模式。长按、拖动、双指缩放和取消手势不会附带揭格。
- 拖动平移、双指缩放；棋盘底部提供缩小、居中和放大按钮。
- 已揭数字邻旗数匹配时，默认揭格模式下点击数字进行和弦揭格。错误插旗可能导致踩雷。
- 全部非雷格揭开才获胜；结束后显示地雷、引爆格和错误旗帜，规则输入锁定，仍可移动棋盘查看。
- 首次有效揭格开始计时，退到后台暂停。自动保存当前棋盘、设置、统计与累计用时；旋转和进程重建恢复同一对局。
- 本地难度统计、胜率、最佳纪录；自定义按行列雷数分组；清空统计需要确认。
- 浅色、深色、跟随系统主题与可关闭的振动反馈。
- 格子提供坐标、状态及点击/长按无障碍动作。
- v1.0.1：内置生图生成的自适应应用图标、棋盘旗帜、地雷及胜负插画；原始透明 PNG 和完整生成提示词位于 `assets/`。
- v1.0.2：独立棋盘工具栏、紧凑与大字号布局、揭格/插旗双选项、安全区域进度，以及短揭格、插旗和胜负插图动效。详细说明移入“玩法帮助”；设置行与棋盘方向移动支持无障碍动作。

## 构建环境

| 组件 | 固定版本 |
|---|---|
| JDK | 17（此次使用 17.0.12） |
| Gradle Wrapper | 8.14.3 |
| Android Gradle Plugin | 8.13.2 |
| Kotlin / Compose Compiler plugin | 2.3.10 |
| compileSdk / targetSdk | 36 / 36 |
| Build Tools | 35.0.0（AGP 默认） |
| Compose BOM | 2025.09.01 |
| Activity Compose | 1.11.0 |
| Lifecycle | 2.9.4 |
| Core KTX | 1.17.0 |
| Coroutines Android | 1.10.2 |

版本依据：[AGP 8.13 兼容说明](https://developer.android.com/build/releases/agp-8-13-0-release-notes)、[Kotlin Gradle 兼容表](https://kotlinlang.org/docs/gradle-configure-project.html)。Wrapper 固定官方 SHA-256，首次构建需要联网下载固定依赖；应用运行无需联网。

## 导入与运行

1. 安装 JDK 17 和支持 API 36 的 Android Studio / Android SDK。
2. 用 Android Studio 打开本目录。SDK 路径由 Android Studio 写入 `local.properties`；该本机文件不包含在仓库中。
3. 确认 SDK 已安装 `platforms;android-36`、`build-tools;35.0.0` 和 Platform Tools。
4. 选择模拟器或 Android 8.0 及以上设备，运行 `app`。

Windows PowerShell 手动配置示例（替换路径）：

```powershell
$env:JAVA_HOME = '你的 JDK 17 目录'
'sdk.dir=C\:/你的/Android/Sdk' | Set-Content local.properties -Encoding utf8
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --console=plain
```

macOS / Linux：配置 JDK 17 与 SDK 后执行 `chmod +x gradlew`，再运行：

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --console=plain
```

APK 输出：`app/build/outputs/apk/debug/app-debug.apk`。debug APK 有调试签名，可安装测试；不是应用商店发布包。安装示例：

```powershell
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

连接设备后运行真实触摸测试：

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest --console=plain
```

## 代码结构

- `core/Game.kt`：纯 Kotlin、不可变棋盘、确定性布雷、零区展开、和弦和存档有效性校验。
- `data/GameRepository.kt`：版本化 JSON envelope，将棋盘、设置、统计及终局去重 ID 原子保存到应用私有 SharedPreferences。
- `data/ApplicationGameStore.kt`：应用级 IO 队列、最新内存快照与实例所有权，防止旧实例晚写覆盖新对局。
- `ui/GameViewModel.kt`：StateFlow、前台单调时钟计时、游戏动作与统计。
- `ui/MinesweeperApp.kt`：响应式界面、难度、自定义、设置、统计和结果对话框。
- `ui/GameChrome.kt`：可滚动控制区、响应式统计卡、模式选择、进度及设置语义。
- `ui/MineBoard.kt`：Canvas 棋盘、单一手势识别器、缩放命中换算、无障碍格子节点。
- `app/src/debug/.../ui/UiPreviews.kt`：Android Studio 的浅色手机、深色横屏及小屏大字号预览，直接复用实际界面组件和示例棋盘；不启动计时或存档。
- `assets/generated`：五张内置 `image_gen` 生成的原始素材；`assets/PROMPTS.md` 保留每张生成提示词。Android 用资源位于 `app/src/main/res/drawable-nodpi`。用浏览器打开 `assets/PREVIEW.html` 可查看图标遮罩及小尺寸、深浅背景样张；该页面不是应用运行截图。
- `app/src/test`：核心与存档的 JVM 测试。
- `app/src/androidTest`：真实触摸手势测试。
- `app/src/androidTest/.../ui/GameLayoutTest.kt`：小屏、大字号、长难度标题与设置/模式语义测试。

## 验证记录与限制

最终检查结果与 APK 信息见同目录 `BUILD-REPORT.md`。

当前环境 `adb devices` 无设备；模拟器检测提示缺少虚拟化驱动，未启用 Windows 功能、安装驱动或重启。真实触摸、横竖屏、后台计时、进程重建与真机视觉体验尚未进行设备运行验收。仪器测试源码随项目交付，编译结果在构建报告中说明。

存档按动作、后台切换及前台计时约每秒排入 IO 写入。系统在尚未落盘前强制终止进程时，可能恢复最近一次已成功保存的快照。写入失败会在应用中提示；磁盘损坏存档经过严格校验并安全回退。

当前仅交付 debug 签名。正式分发需另行设置自有签名、发布构建与设备验收。
