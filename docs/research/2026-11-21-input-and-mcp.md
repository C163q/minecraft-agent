# Minecraft 26.3 输入后端与 MCP 图片传输

## 游戏侧输入

26.3 的窗口与输入后端为 SDL3，物理键位使用 SDL scancode，与键盘布局相关的文本编辑快捷键使用 SDL keycode。鼠标按钮常量也随版本改变。设计脚本键名时可保留用户可读名称，将底层转换留在 Mod 内，复用游戏的 InputConstants。第一人称视角所需的相对移动量、GUI 内的位置与截图的像素坐标，需要分别确认单位和缩放关系。本次暂停实验没有注入键鼠或验证坐标换算。[Mojang 26.3 发布说明](https://www.minecraft.net/en-us/article/minecraft-java-edition-26-3) · [Fabric 26.3 开发说明](https://fabricmc.net/2026/09/15/263.html)

Mojang 以保留原始标识符的形式发布游戏 JAR。可用 JDK 工具直接查找目标版本的类与方法，箱子界面的[暂停实验](2026-11-21-container-pause.md)已使用该途径核对实际调用关系。[Mojang 关于取消混淆的说明](https://www.minecraft.net/en-us/article/removing-obfuscation-in-java-edition)

## MCP 侧图片

MCP 的工具结果可以同时携带文本和图像块。图像块包含媒体类型和 Base64 编码的图像正文；支持视觉工具结果的宿主可以直接将图像交给模型。保存 PNG 和返回图像是独立操作。后续坐标标注可以用截图标识引用同一张原图，也可接受普通图片。Rust 有官方 SDK 和 stdio 传输实现。图片在目标宿主中的实际转交、保留和缩放行为尚未实测。[MCP 工具结果规范](https://modelcontextprotocol.io/specification/2025-11-25/server/tools) · [MCP Rust SDK](https://github.com/modelcontextprotocol/rust-sdk)
