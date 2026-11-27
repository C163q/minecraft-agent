# Minecraft Agent

让 LLM 根据 Minecraft Java Edition 的游戏画面、玩家状态和准星目标操作键盘和鼠标。Fabric Mod 在游戏中执行输入并采样，Rust MCP 通过本机 Unix 套接字把结果返回给 Codex 等 MCP 客户端。

## 使用前提

- Linux 桌面环境。游戏和 MCP 在本机以同一用户运行，游戏使用未对局域网开放的单人世界。
- 能从启动器运行 Minecraft Java Edition **26.3**，并已为目标实例安装 Fabric Loader **0.20.1**。下面以 HMCL 的独立实例 `26.3-Agent` 为例；构建会读取实例的版本 JSON、游戏 JAR 和启动器下载的库。Mod 仅依赖游戏和 Loader，无需另装 Fabric API。
- 从源码构建需要 **JDK 25 或更高版本**（含 `javac` 和 `jar`，已验证 JDK 27）、**Python 3**、系统链接器（如 `cc`）以及 **Rust 和 Cargo**（锁定依赖要求 Rust 至少 1.88）。首次构建时 Cargo 需要下载尚未缓存的依赖。
- 下例使用已配置好的 **Codex CLI**。其他支持 stdio MCP 和图片工具结果的客户端也可以连接。

## 编译和安装

在项目根目录执行。如果实例名或 HMCL 游戏目录不同，修改前两行。下例假定 HMCL 为实例开启了独立运行目录，实例的 `mods/` 就在同名 JSON 旁边。

```sh
instance=26.3-Agent
game_root="$HOME/.config/hmcl/.minecraft"
instance_dir="$game_root/versions/$instance"

python3 mod/build.py "$instance_dir/$instance.json"
install -Dm644 mod/build/minecraft-agent.jar "$instance_dir/mods/minecraft-agent.jar"

cargo build --release --locked --bin minecraft-mcp
install -Dm755 target/release/minecraft-mcp "$HOME/.local/bin/minecraft-mcp"
```

## 连接 HMCL 和 Codex

在同一个终端生成本机连接地址，并注册 Codex 的用户级 MCP 服务：

```sh
socket_path="${XDG_RUNTIME_DIR:?}/minecraft-agent.sock"
codex mcp add minecraft --env "MINECRAFT_AGENT_SOCKET=$socket_path" -- "$HOME/.local/bin/minecraft-mcp"
printf 'MINECRAFT_AGENT_SOCKET=%s\n' "$socket_path"
```

将最后一行命令**输出的整行**（例如 `MINECRAFT_AGENT_SOCKET=/run/user/1000/minecraft-agent.sock`）填入 HMCL **目标实例的设置 → 高级选项 → 环境变量**。对此项使用实例自己的设置，保留原有环境变量，然后启动或重启该实例。

如果通过脚本或编辑器修改了 HMCL 的实例配置文件，先保存并退出游戏，再完全退出 HMCL（包括托盘）并重新打开。一直开着的启动器可能仍会用内存中的旧设置启动游戏。

进入单人世界后，从项目目录打开新的 Codex 会话，输入：

> 请调用 minecraft 的 input_script，传入 `{"script":"BEGIN\nEND"}`，告诉我停止画面里有什么。

连接成功后，这个空脚本返回 `ticks: 0`、`world: "paused"` 和一张游戏截图。游戏左上角会出现暂停图标。按 **F8** 可以继续或暂停，也可以直接让模型提交下一段脚本。若工具已注册但连不上游戏，用 `codex mcp get minecraft` 检查配置，再查看实例的 `logs/latest.log` 中是否有带同一地址的 `Input bridge listening on`。

## 工具

| 工具 | 用途 |
| --- | --- |
| `input_script` | 从文本或 UTF-8 文件逐拍执行输入。每次最多执行 `max_ticks` 拍（默认 600），[到上限可续跑](docs/input-script/pause.md#限制单次调用的拍数)。可在方块挖掉、玩家离地或界面打开时暂停；返回命中的监听及其现场数据，默认附截图。[事件参考](docs/input-script/events.md) |
| `inspect_input` | 查看保持的键、鼠标移动、监听和待执行队列，消耗零拍。 |
| `inspect_game` | 查询玩家坐标、朝向、双手物品、生命、饥饿、氧气及原版准星目标。默认只返回数据，传 `{"include_screenshot":true}` 同时取得该帧截图。 |
| `cursor_hint` | 在截图的指定坐标标点；第一人称截图还可给出瞄准所需的转角。 |

例如让模型调用 `inspect_game({})` 查看面前的牛是否被草挡住。命中草时，`target` 包含方块 ID、坐标和命中面；命中牛时会给出 `minecraft:cow` 和实体 ID。查询保持游戏当前的运行或暂停状态。[游戏状态字段](docs/inspect-game.md)

模型通过菜单进入单人存档或保存退出时，`input_script` 会返回切换状态、实际界面和旧输入的清理结果。进入存档后，模型从新世界的暂停帧继续操作。[进出存档的返回结果](docs/input-script/pause.md#进入存档与保存退出)

脚本可按事件数据和变量计算输入，用 `Print` 返回日志，用顶层 `RETURN` 返回 JSON 值。[变量、表达式与输出](docs/input-script/values.md)

编写脚本时按需查阅[脚本语法参考](docs/input-script/README.md)。手动配置 MCP、调整鼠标限速和实机验证见[开发文档](docs/development/keyboard-bridge.md)。

## 许可证

本项目采用 [Apache License 2.0](LICENSE)。
