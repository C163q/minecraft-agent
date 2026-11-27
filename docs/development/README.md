# 开发环境与验证入口

当前验证基线是本地单人 Minecraft Java Edition 26.3 和 Fabric Loader 0.20.1。本机安装位置见工作区的 `docs/local.md`。

## Rust 脚本库

构建与实机验证入口见[输入 Mod 与 Rust MCP](keyboard-bridge.md)。`minecraft-input` 的核心逻辑位于仓库根目录的 Rust 库中。用户和 LLM 使用[脚本语法参考](../input-script/README.md)。`Script::parse` 验证内联文本，`ScriptSource::load` 统一处理文本和文件，文件的相对路径基准由调用方传入。入口见[解析器](../../src/parser.rs)。

多文件提交由 [IMPORT 加载器](../../src/parser/imports.rs) 在 MCP 主机端读取和绑定。编译器向游戏发送根入口和全提交的函数表，同时把库入口出现的键名加入现有的按键校验表。Mod 在恢复游戏前验证键名，因此更新支持函数的实例时，IMPORT 只需更新 MCP。用户示例见[从文件导入函数](../input-script/imports.md)。

每个游戏会话复用一个 [Keyboard](../../src/keyboard.rs)。`play(&script)` 返回惰性迭代器，对固定拍数的输入，每次取下一项推进一个输入 tick，得到源行号、本拍按下、释放和保持的键集合。这个固定拍预览跳过零拍控制并把动态鼠标等待视为空闲；游戏端负责真正的中断、恢复和增量合并。MCP 的 `Script::compile` 复用同一键状态逻辑，压缩连续等待并交给 Mod 在游戏线程逐拍派发。

在仓库根目录运行 `cargo test --offline` 验证语法、逐拍状态与示例。运行 `cargo clippy --offline --all-targets -- -D warnings` 检查代码。

使用变量、运行时表达式、函数或 `RETURN` 的脚本需要游戏端执行。Rust 负责解析、词法绑定和参数个数检查，Mod 在接收前校验常量表达式与物理键名，在实际输入边界处理动态值。运行时求值、等待和出错时停止的例子见[变量、表达式与输出](../input-script/values.md)。对于来源可能包含这些语法的输入，使用 `Keyboard::try_play` 检查是否支持固定拍预览，或调用 `Script::compile` 生成正式派发程序。

## 核对游戏接口

以目标实例的版本 JSON 确认 `mainClass`、Loader 和游戏要求的 Java 版本。26.3 的游戏 JAR 保留类与方法名称，可用 JDK 自带的 `javap` 核对签名和调用顺序。在 shell 中将 `MC_JAR` 设为目标客户端 JAR 的绝对路径后执行：

```sh
javap -p -c -classpath "$MC_JAR" net.minecraft.client.Minecraft
javap -p -c -classpath "$MC_JAR" net.minecraft.client.gui.Gui
javap -p -c -classpath "$MC_JAR" net.minecraft.client.server.IntegratedServer
```

单人游戏的客户端和内置服务器各有自己的线程。验证暂停时，在对应线程采样客户端当前界面和服务端世界时间；用附近实体的 tick 计数交叉核对模拟是否推进。恢复后再次检查界面和计数。已核对的调用路径和实测范围见[箱子界面暂停调研](../research/2026-11-21-container-pause.md)。

## END 的返回条件

`END` 结束当前输入段。脚本中的 `PAUSE` 和运行中的暂停快捷键沿用同一个停止帧边界。在需要暂停的本地单人世界中，游戏输入链路使用以下返回条件。

1. 停止消费当前段之后的输入，等待内置服务端确认世界已暂停。保留缓冲区中的待续输入。
2. 在服务端线程同步本次观察所涉及、应当对玩家可见的待发变更。在箱子界面，同步当前容器的槽位。随后在同一游戏连接上发送属于本次请求的标记。
3. 客户端处理该标记后完成一帧新的游戏与界面渲染，若本次请求截图，从该帧读回图片后一起返回世界状态和 PNG；关闭截图时，在该帧渲染后返回状态。

客户端收到该次暂停的服务端标记后，在 GUI 左上角绘制双竖线，背包、箱子与返回给 MCP 的停止帧也能看到。恢复输入或离开世界时清除标记；原版暂停菜单沿用游戏界面。`mod/art/pause.svg` 是图标源文件，更新后用 `rsvg-convert -o mod/src/main/resources/mcagent/pause.png mod/art/pause.svg` 生成游戏使用的精灵纹理。

工具区分已暂停、未暂停、无需暂停。世界仍在模拟的背包、箱子和聊天界面适用上述暂停流程。标题页等无需暂停的场景通过客户端取得当前界面的新帧。同步标记覆盖同一连接上排在它之前的更新，屏幕内容仍遵循游戏自身的呈现与动画。箱子槽位的同步与截图证据见[停止画面调研](../research/2026-11-21-stop-frame.md)。

在世界内同步生命值可让红心随新帧更新。实体移动还可能有等待后续客户端 tick 推进的位置插值，停止图中会保留玩家当时所见的位置。实测范围见[红心与实体停止帧](../research/2026-11-21-world-stop-frame.md)。

通过 stdio 接入 Codex 时，最终截图可直接作为 MCP 工具结果的 `image` 内容块与暂停状态一同返回。模型可直接观察该图，详情见 [Codex 单图实机盲测](../research/2026-11-21-codex-image.md)。

GUI 点选使用原始截图的像素坐标。输入端根据实际窗口逻辑尺寸换算鼠标位置，再由 Minecraft 按当前 GUI 缩放处理命中。标点副本与原图保持相同尺寸。已测范围见[两种 GUI 缩放下的箱子点选](../research/2026-11-21-codex-gui-click.md)。

第一人称下，鼠标被游戏捕获，相对输入经玩家的灵敏度设置转换为视角变化。实机验证可以分别记录实际转角、左键按下时的准星命中、逐拍攻击键状态和服务端方块变化，再与模型看到的停止帧核对。见[第一人称瞄准与持续挖掘](../research/2026-11-21-first-person-mining.md)。
