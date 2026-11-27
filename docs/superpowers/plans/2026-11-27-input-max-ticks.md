# input_script 单次调用拍数上限实施计划

当前状态：两项均已完成。63 项 Rust 测试、格式与 Clippy 检查、Java 测试和 Minecraft 26.3 完整游戏回归已通过。

依据[已确认的规格](../specs/2026-11-27-input-max-ticks-design.md)，在 Rust stdio MCP 接受 `max_ticks`，由 Minecraft 26.3 Fabric Mod 的客户端线程根据请求已执行拍数停止。默认 600，输入块和鼠标移动继续使用原队列。

- [x] **工具参数与协议。** 在 `src/bin/minecraft-mcp.rs` 测试并实现参数校验、工具描述和握手能力检查。验证缺省值、合法正整数及错误类型或越界值。旧 Mod 无法保证上限时，在派发前提示升级。
- [x] **暂停与恢复。** 在 `Playback.Request` 保存额度，在 `resolveControls` 已结算本拍原有边界后检查额度。通过 `tests/game/run.py` 的正式 stdio 和游戏实例覆盖默认 600、文件提交、保留剩余等待、手动恢复、重叠块、截图落盘、动态鼠标屏障以及同拍事件和切换。更新使用参考，运行 Rust 与实机回归后提交。
