# 构建、安装与连接输入 Mod

当前基线为 Linux、Minecraft Java Edition 26.3、Fabric Loader 0.20.1。游戏 Mod 和 Rust MCP 通过本机 Unix 域流式套接字连接。可以从已有的 HMCL 安装构建，测试使用独立游戏目录。

## 构建

需要 Rust（2024 edition）、Cargo、Python 3 和支持 `--release 25` 的 JDK（已验证 OpenJDK 27）。确保 HMCL 已安装 26.3 Fabric 实例及 Linux 所需依赖。以下命令在项目根目录运行，参数须替换为本机已解析的 Fabric 版本 JSON，其同目录有同名 JAR，游戏根目录有 `libraries/`。

```sh
cargo build --release --bin minecraft-mcp
python3 mod/build.py /absolute/game-root/versions/26.3-Fabric/26.3-Fabric.json
```

产物为 `target/release/minecraft-mcp` 和 `mod/build/minecraft-agent.jar`。Python 构建脚本从实例清单组装类路径，使用 JDK 自带的 `javac` 和 `jar`，运行时依赖游戏和 Loader。将 Mod JAR 复制到目标 Fabric 实例实际使用的 `mods/`，重启实例。新 MCP 在发送鼠标操作前会检查 Mod 能力。

## 连接

先选择一个当前用户可写的私有目录，在其中指定一个尚未被占用的短套接字路径。例如，在提供 `XDG_RUNTIME_DIR` 的 Linux 桌面中，从同一终端启动 HMCL：

```sh
export MINECRAFT_AGENT_SOCKET="$XDG_RUNTIME_DIR/minecraft-agent.sock"
echo "$MINECRAFT_AGENT_SOCKET"
hmcl
```

游戏进程须继承该变量；未配置变量时 Mod 不启动通信服务。正常启动的游戏日志包含 `Input bridge listening on` 和实际路径。一个路径对应一个游戏实例。如果上次进程异常终止留下同名套接字，选择新的路径并同步修改双方配置。

以 Codex 为例，MCP 配置使用下列条目，把 `command` 换成构建产物的绝对路径，把套接字换成终端打印的实际绝对路径。配置中的字符串直接传给进程。

```toml
[mcp_servers.minecraft]
command = "/absolute/project/target/release/minecraft-mcp"

[mcp_servers.minecraft.env]
MINECRAFT_AGENT_SOCKET = "/run/user/1000/minecraft-agent.sock"
```

可在同一 MCP 环境表中添加 `MINECRAFT_AGENT_MOUSE_MAX_SPEED = "50"`，单位为 px/tick，默认 50。该值须为有限正数，每次提交时传给 Mod，作用于本拍所有鼠标移动的合速度。

进入单人世界后调用 `input_script`。传入 `{"script":"BEGIN\nEND"}` 时，检查返回的 `ticks` 为 0、`world` 为 `paused` 且同时有一张图片。也可传 `path`；相对路径从 MCP 进程的工作目录解析。首次接入建议使用绝对路径。非交互 Codex 会话需要允许调用这个会改变游戏状态的工具。语法按需查阅[脚本参考](../input-script/README.md)。

## 执行与状态

- Rust 先验证整段语法。每次调用建立独立连接，从 Mod 取得当前会话显式保持的键，再发送带行号、按键增量和控制指令的紧凑序列。Fabric 客户端在 [InputQueue](../../mod/src/main/java/mcagent/InputQueue.java) 中逐拍合并新旧块，在实际执行时更新显式保持状态。长等待占一个区段，游戏每运行一个输入 tick 消费一拍。
- Mod 开始前核对整段涉及的所有键名，包括只在释放指令中出现的键。脚本键名映射到 26.3 的 `key.keyboard.*`，客户端只在键状态变化时调用 `KeyboardHandler.keyPress`。同拍先释放不再需要的键，再按下新增键；组合键先按修饰键、最后释放修饰键。SDL 根据物理键和修饰键取得布局相关的逻辑键码。动作使用玩家当前键位设置。
- Mod 在游戏实例内保留已按键和显式保持状态，下一次调用可由新的 MCP 进程续接。单个实例串行处理提交。切换世界或中断正在执行的连接时，结束当前段、松开脚本保持的键与鼠标按钮，并清除移动余量。取消不会回滚游戏已执行的拍。
- 本地世界中的脚本暂停和 END 等待内置服务端确认暂停，同步当前容器与玩家生命/食物状态，再发送请求标记。客户端收到标记后渲染停止帧，默认读回 PNG。传 `include_screenshot: false` 时，在同一渲染边界完成请求，跳过像素读回、PNG 编码和 MCP 图像缓存。旧 Mod 仍可能向新版 MCP 发送 PNG，此时 MCP 会丢弃该图片并只返回状态。标题页及原本就暂停的界面返回 `not_needed`。游戏世界仍在流逝的界面适用普通暂停。已开放的局域网世界及远程多人世界会返回错误。发生通信错误时先确认游戏状态，再决定是否提交新的动作。
- 鼠标移动、按下与释放由游戏客户端处理。文本输入法和混合人工与脚本输入尚未作为正式操作验证。游戏图像中的插值、动画与异步区块渲染仍遵循原版行为。

鼠标的逐拍合成由 [Movement](../../mod/src/main/java/mcagent/Movement.java) 处理，[MouseDispatch](../../mod/src/main/java/mcagent/MouseDispatch.java) 在指令生效时换算单位并调用原版鼠标事件。游戏端在停止帧渲染时采集相机信息，Rust MCP 将其与原始 PNG 一起保存，供 [cursor_hint](../input-script/cursor-hint.md) 使用。GUI 和外部 PNG 也可标点。

`inspect_input` 在游戏线程一次性复制键和按钮的实际脚本保持状态、活动位移与限速。它通过独立的游戏只读请求读取状态，和脚本提交共用实例的串行连接。Rust MCP 在主机端对不可变队列快照分页或导出文件。MCP 返回相同的 JSON 文本及 `structuredContent`。较旧的 Mod 缺少查询能力时，MCP 会提示更新。字段和使用示例见[查询当前输入状态](../input-script/inspect.md)。

## 开发验证

从项目根目录运行 `cargo test --offline` 和 `cargo clippy --offline --all-targets -- -D warnings`。首次获取依赖需先执行常规 Cargo 构建。图形验证需要 Python Pillow、已安装的游戏资源、可用的图形桌面和本地套接字权限：

```sh
python3 tests/game/run.py /absolute/game-root/versions/26.3-Fabric/26.3-Fabric.json
```

该测试在项目 `.tmp/` 内创建新实例和存档，构建正式 Mod 与独立的测试观察 Mod，并通过正式 Rust MCP 提交脚本。观察端从玩家的 `KeyboardInput.tick` 结果读取前进、跳跃和左移，从原版事件入口记录鼠标及键盘的按下和释放，并核对游戏截图与服务端的箱子及物品归属。日志与每段 END 的图片保存在测试打印的实例目录中。

加上 `--codex` 可在同一测试世界运行独立 Codex CLI 会话。它从语法参考查找写法，通过正式 MCP 的图片选中红色方块，使用返回的角度瞄准，再观察停止帧。需要已配置的 Codex CLI 和模型访问权限。可以通过 `MINECRAFT_AGENT_CODEX_MODEL` 选择模型。测试把该会话的图片和工具调用保存在当次测试目录的 `codex-mouse/`。

PAUSE 和跨块覆盖需要同时更新 MCP 与 Mod。旧 MCP 发送每拍的目标键集合，Mod 可以执行旧脚本；若在其 END 前用实体快捷键中断，旧协议不足以区分本块中新出现的临时和显式按住。此时使用新版客户端以 `CLEAR` 丢弃旧块，能保留此前已确认且尚未释放的显式状态。新版协议逐拍发送每个键的操作，能完整区分这两种情况。
