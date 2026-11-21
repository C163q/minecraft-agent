# Codex 通过 Rust MCP 直接观察 Minecraft 停止帧

2026-11-21 使用 Codex CLI 0.171.0、模型 `gpt-6.2-sol`、Rust 1.99.0 和 Minecraft Java Edition 26.3（Fabric Loader 0.20.1）验证工具结果中的 PNG 能否作为模型的视觉输入。

## 实机盲测

Codex 在空工作目录中通过 stdio 连接本地 Rust MCP 探针。测试提示要求它调用一次 `input_script`，报告箱子第一行各个非空格内的物品和格号，从左起数为 1。提示和工具描述均未提供物品名称、格号、截图路径或日志路径。测试进程使用只读沙箱，禁用 shell 工具。

Rust 探针仅接受 `BEGIN`、`ScreenShot()`、`END` 三行组成的固定请求。收到请求时，独立游戏实例才随机选择两个不同的格子并放入物品。Mod 确认内置服务器暂停，同步当前容器，发送请求标记，在客户端处理标记后的新帧中读回 PNG。Rust 通过本地通信目录取得该 PNG，将其编码为 `type: image`、`mimeType: image/png` 的 MCP 内容块。同一工具结果的文字只有 `World paused.`。

| 检查 | 实际结果 |
| --- | --- |
| 服务端随机布局 | 苹果第 7 格，钻石第 9 格 |
| Codex 最终回答 | `{"apple":7,"diamond":9}` |
| 工具调用轨迹 | 唯一一次已执行的调用是 `mcprobe.input_script` |
| 图片一致性 | 从 Codex 事件记录的图像块解码出 204573 字节，逐字节等于游戏生成的 1600 × 960 PNG |
| 两端退出状态 | 独立游戏实例和 Codex 进程均正常退出 |

图片 SHA-256 为 `62ef80e47a96e442e90c124acc5c394296e39fd755d192ef52ec3097651a2cea`。这次实际请求、返回内容和答案共同验证了直接返图；模型无需再调用文件工具。哈希核对覆盖游戏输出至 Codex 收到的图像块，模型内部的图像缩放未经测量。

## 非交互调用与复核

首次运行时，`codex exec` 发现了 MCP 工具并生成了正确参数，但当前非交互审批策略拒绝了这次调用。该次没有触发游戏截图。第二个临时会话仅对测试服务器的固定截图工具设置 `-c 'mcp_servers.mcprobe.tools.input_script.approval_mode="approve"'`，成功执行。该设置只用于本次探针；将来能够执行任意游戏输入的同名工具需要按其实际权限配置。

本机 `.tmp/codex-image/` 中的 `rust/src/main.rs` 为最小 JSON-RPC/stdio 探针，`src/probe/` 为 Fabric 端。游戏侧答案在 `run-zm1tolht/console.log`，成功调用的事件及最终回答在 `codex-9_306_pv/`，原始图片在 `ipc/result.png`。复测时使用独立空通信目录及对应的探针副本，避免复用上一轮请求和图片。该验证覆盖单张 PNG 的实机传输及识别，通用输入脚本、多图返回、图片坐标精度和正式 MCP 服务仍待实现或验证。
