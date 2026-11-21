# 开发环境与验证入口

当前验证基线是本地单人 Minecraft Java Edition 26.3 和 Fabric Loader 0.20.1。本机安装位置见工作区的 `docs/local.md`。

## Rust 脚本库

`minecraft-input` 在仓库根目录构建，无第三方依赖。用户和 LLM 使用[脚本语法参考](../input-script.md)。`Script::parse` 验证内联文本，`ScriptSource::load` 统一处理文本和文件，文件的相对路径基准由调用方传入。入口见[解析器](../../src/parser.rs)。

每个游戏会话复用一个 [Keyboard](../../src/keyboard.rs)。`play(&script)` 返回惰性迭代器，每次取下一项才推进一个输入 tick，得到源行号、本拍按下、释放和保持的键集合。暂停后继续同一段时保留迭代器；进入下一段时复用键盘状态。将这些集合派发给 Fabric Mod 的游戏线程是后续接入工作。

在仓库根目录运行 `cargo test --offline` 验证语法、逐拍状态与示例。运行 `cargo clippy --offline --all-targets -- -D warnings` 检查代码。

## 核对游戏接口

以目标实例的版本 JSON 确认 `mainClass`、Loader 和游戏要求的 Java 版本。26.3 的游戏 JAR 保留类与方法名称，可用 JDK 自带的 `javap` 核对签名和调用顺序。在 shell 中将 `MC_JAR` 设为目标客户端 JAR 的绝对路径后执行：

```sh
javap -p -c -classpath "$MC_JAR" net.minecraft.client.Minecraft
javap -p -c -classpath "$MC_JAR" net.minecraft.client.gui.Gui
javap -p -c -classpath "$MC_JAR" net.minecraft.client.server.IntegratedServer
```

单人游戏的客户端和内置服务器各有自己的线程。验证暂停时，在对应线程采样客户端当前界面和服务端世界时间；用附近实体的 tick 计数交叉核对模拟是否推进。恢复后再次检查界面和计数。已核对的调用路径和实测范围见[箱子界面暂停调研](../research/2026-11-21-container-pause.md)。

## END 的返回条件

`END` 结束当前输入段。在需要暂停的本地单人世界中，已确定的返回条件如下，正式 MCP 尚待实现。

1. 停止消费当前段之后的输入，等待内置服务端确认世界已暂停。保留缓冲区中的待续输入。
2. 在服务端线程同步本次观察所涉及、应当对玩家可见的待发变更。在箱子界面，同步当前容器的槽位。随后在同一游戏连接上发送属于本次请求的标记。
3. 客户端处理该标记后完成一帧新的游戏与界面渲染，从该帧读回图片。图像读回完成后，一起返回世界暂停状态和最终截图；脚本中途截取的图片仍对应其各自的执行时刻。

工具区分已暂停、未暂停、无需暂停。世界仍在模拟的背包、箱子和聊天界面适用上述暂停流程。标题页等无需暂停的场景通过客户端取得当前界面的新帧。同步标记覆盖同一连接上排在它之前的更新，屏幕内容仍遵循游戏自身的呈现与动画。箱子槽位的同步与截图证据见[停止画面调研](../research/2026-11-21-stop-frame.md)。

在世界内同步生命值可让红心随新帧更新。实体移动还可能有等待后续客户端 tick 推进的位置插值，停止图中会保留玩家当时所见的位置。实测范围见[红心与实体停止帧](../research/2026-11-21-world-stop-frame.md)。

通过 stdio 接入 Codex 时，最终截图可直接作为 MCP 工具结果的 `image` 内容块与暂停状态一同返回。模型可直接观察该图，详情见 [Codex 单图实机盲测](../research/2026-11-21-codex-image.md)。

GUI 点选使用原始截图的像素坐标。输入端根据实际窗口逻辑尺寸换算鼠标位置，再由 Minecraft 按当前 GUI 缩放处理命中。标点副本与原图保持相同尺寸。已测范围见[两种 GUI 缩放下的箱子点选](../research/2026-11-21-codex-gui-click.md)。

第一人称下，鼠标被游戏捕获，相对输入经玩家的灵敏度设置转换为视角变化。实机验证可以分别记录实际转角、左键按下时的准星命中、逐拍攻击键状态和服务端方块变化，再与模型看到的停止帧核对。见[第一人称瞄准与持续挖掘](../research/2026-11-21-first-person-mining.md)。
