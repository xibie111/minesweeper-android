# 素材检查记录

五张素材均通过内置 `image_gen` 全新生成，保留生成器返回的原始透明 PNG。实际输出尺寸为 1254×1254，颜色模式 RGBA，alpha 最小值 0、最大值 255。

| 素材 | 保存位置 | 用途 |
|---|---|---|
| icon_foreground_v2.png | assets/generated/ | 应用启动图标 |
| board_flag_v2.png | assets/generated/ | 棋盘和插旗模式 |
| board_mine_v2.png | assets/generated/ | 棋盘地雷 |
| result_victory_v2.png | assets/generated/ | 胜利结果页 |
| result_defeat_v2.png | assets/generated/ | 失败结果页 |

每张原图与 `app/src/main/res/drawable-nodpi/` 资源副本逐字节一致。文件大小、SHA-256 和 alpha ≥ 128 的可见边界记录在 [validation.json](validation.json)。检查只读取图像；未通过代码编辑图像内容。

原始图已查看。棋盘标记采用半分辨率缓存和等比绘制。应用图标使用前景透明 PNG、8dp inset 和浅绿色背景，自适应定义位于 `mipmap-anydpi-v26`，包含 monochrome 图层。参考 [Android 自适应图标文档](https://developer.android.com/develop/ui/views/launch/icon_design_adaptive)。

[PREVIEW.html](PREVIEW.html) 按代码中的颜色和标记尺寸比例展示图标遮罩、24/32/44px 棋盘样张及胜负插画，可在浏览器中本地打开。浏览器自动预览工具在启动时失败，未对该 HTML 页执行实际渲染检查；该页不是 Android 应用运行截图。安卓真机、启动器主题色和小屏大字体效果仍需设备运行验证。

全部生成提示词见 [PROMPTS.md](PROMPTS.md)。
