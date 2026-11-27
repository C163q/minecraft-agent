# 查询当前游戏状态的实施与验证

当前状态：已完成三项。Rust 的 60 项测试及 26.3 独立单人实例的完整实机套件通过。实机核对了同帧截图、准星结果、查询期间的暂停与运行状态，以及实际保存退出期间查询直至返回标题页。

按[设计规格](../specs/2026-11-27-inspect-game-design.md)在 26.3 本地单人实例实现只读查询。实施采用 subagent-driven-development，独立工作通过工具契约衔接。Mod 响应包含 `state` 对象，图像按原桥接协议在同一响应携带 `png` 和 `camera`。

1. [x] **游戏端采样。** 在 `mod/src/main/java/mcagent/` 添加游戏状态查询，通过 `GameBridge` 的能力 `inspect_game` 和同名操作接收请求，在客户端实际渲染完成后读取玩家和准星，用 `FrameFiles` 编码可选图片。核对空字段、过渡界面、取消和请求重叠。编译 Mod。
2. [x] **Rust MCP 适配。** 在 `src/bin/minecraft-mcp.rs` 及其同名模块目录注册工具，校验参数，复用截图缓存，填充 `structuredContent` 和文本。先验证旧程序缺少工具，再用测试覆盖图片、无图、类型错误和缺失能力。运行 Rust 测试与 Clippy。
3. [x] **实机与用户文档。** 在 `tests/game/` 从 stdio 调用工具比对标题页、运行和暂停中的玩家、方块面、实体、落空及界面结果。验证查询期间已暂停世界的计数器、输入队列、截图和 `cursor_hint`。同步 README、开发文档与工具索引，复核后合并。
