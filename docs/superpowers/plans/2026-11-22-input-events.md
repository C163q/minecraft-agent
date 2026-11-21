# 输入事件的实施与验证

当前状态：五项任务均已完成。28 项 Rust 测试、Java 队列测试、独立 Fabric 实例与 Codex 验收通过。独立审查发现的三个边界问题均已复现、修正并加入回归。

目标是在本地单人 Minecraft 26.3、Fabric Loader 0.20.1 中交付[输入事件规格](../specs/2026-11-22-input-events-design.md)。Rust MCP 负责编译和保存结果，Fabric 客户端负责事件资格、队列和停止边界，内置服务器报告已接受的伤害。各项先运行能复现问题的断言，再实现并复测，按独立变更提交。执行时可选 `superpowers:executing-plans` 或 `superpowers:subagent-driven-development`。

## 1. 编译监听声明与处理块

- [x] 在 `tests/keyboard.rs` 覆盖内联和文件输入：声明占零拍，处理块首行不会在注册时执行，声明行号与处理块内部行号分别保留。未知事件、缺少结束标记、处理块内的错误键名均应在恢复前报告原始行。
- [x] 在 `src/parser.rs`、`src/keyboard.rs` 表示和编译 `ON(Hurt)`、`ON(HpLost)`、`CLEAR_LISTENERS`。在 `src/bin/minecraft-mcp.rs` 的能力检查、编解码以及 `GameBridge.java` 握手中增加事件能力；旧 Mod 收到含事件的程序前应由 MCP 报错。普通脚本继续走现有路径。
- [x] 运行 `cargo test --offline`，检查实际发送的程序包含处理块的键名及源行号，注册不会改变当前按键状态。

## 2. 队列、事件与输入边界

- [x] 扩展 `tests/queue/InputQueueTest.java`。同一 W、A、S 序列分别在 W 派发前后触发释放 W 的处理块，断言每一拍的键状态。覆盖按注册顺序合并、处理中的重复触发、不同声明独立触发、暂停后恢复、两种清除指令及监听所属块结束后的待执行处理块。
- [x] 在 `InputQueue.java` 中将注册项与已触发的块分别保存，用顶层块号和 `ON` 源行标识来源。在 `Playback.beforeTick/afterTick` 的既有派发边界处理客户端已观察到的事件。收到通知时确定有资格的声明，以免随后注册的监听消费旧事件。处理块只允许覆盖尚未派发的输入。
- [x] 三个段尾测试共用一拍 W：首行 `Key(s)` 返回 `end`、S 待执行；首行 `PAUSE` 返回 `pause` 和来源；首行 `Key(s) PAUSE` 返回 `end`，恢复后一拍执行 S 并暂停。每例均断言首次调用 `ticks == 1`，再核对实际剩余队列。通过游戏验证入口编译并运行 Java 队列测试。

## 3. 接入原版两种信号

- [x] 根据[26.3 字节码记录](../../research/2026-11-22-hp-lost.md)，为 `ServerPlayer.hurtServer` 成功返回和本地玩家普通生命值字段更新各接入一个 Mixin，在 `minecraft_agent.mixins.json` 注册。服务端通知交给对应会话的客户端线程。进入世界、重生和换存档时验证身份和生命值基线。
- [x] 扩展 `tests/game/java/mcagenttest/Observer.java` 和实机测试，分别记录服务器接受的命中、客户端普通生命值序列和执行的监听。覆盖 0.1 点伤害、仅掉金心、完全格挡、冷却中拒绝或按差额结算、20 → 16 → 20、同值重发。服务端通知晚于输入边界时不得回写已派发的键。
- [x] 用独立 26.3 Fabric 实例验证首包和重生不触发虚假的 `HpLost`，旧世界的待收通知不会触发新世界的监听。

## 4. MCP 停止结果与快照

- [x] 更新 `Playback.java`、`GameBridge.java`、Rust 的 `stop_result` 和 `src/bin/minecraft-mcp/queue.rs`。显式事件暂停报告本边界实际执行暂停的监听；同一边界两个监听暂停时保留注册顺序。查询输出 `listeners` 及事件队列项来源。返回图片与只返回文本时，停止原因和监听来源一致。
- [x] 对上一项的真实处理块执行 `KeyRelease(w) PAUSE` 后查询，再仅执行 `CLEAR_LISTENERS`。断言后一次返回零拍、监听列表为空，剩余的 S 在预览、分页快照和导出文件中仍能追溯到原声明。分页读取期间修改队列，原游标仍返回原快照。

## 5. 显式截图与端到端验收

- [x] 在解析器、MCP 与游戏截图链路接入规格所列的显式截图组合。覆盖 `ScreenShot("hit.png") PAUSE` 与分行的 `PAUSE`、`ScreenShot("hit.png")`。前者核对文件和返回图为同一停止帧，并在 `include_screenshot=false` 时核对写盘成功。后者核对暂停时截图仍待执行、恢复后先截图再派发下一拍。同行反序必须整块报错。
- [x] 用 `tests/game/run.py` 的独立实例和 `--codex` 入口，通过 stdio MCP 让模型读取事件来源、查询队列、恢复并确认同一拍按键。观测客户端输入、服务端暂停与实际返回帧；额外核对到达停止同步后才观察到的变化不会追溯到已经结束的监听。
- [x] 将实测支持的行为写入 `docs/input-script/` 的事件参考及索引，同步暂停、时间、查询和开发文档。运行 `cargo fmt --check`、`cargo test --offline`、`cargo clippy --offline --all-targets -- -D warnings` 与[实机验证](../../development/keyboard-bridge.md)，记录结果后合并任务分支。

## 验证结果

在 26.3 Fabric 0.20.1 独立单人存档中，0.1 点伤害使普通生命降至 19.9；只掉金心时仅触发 Hurt。完全格挡返回未受伤，部分格挡仍有伤害时触发 Hurt。实测冷却期间较弱的命中被拒绝，较强的命中按差额触发。玩家重生建立新的生命值基线，切换维度清空原世界的队列与监听。

停止同步标记到达时注入的晚到生命值下降没有触发末拍已到期的监听。仅清除监听保留了旧队列的截图和暂停。Java 队列测试还覆盖了两个事件以相反顺序到达时，查询所得处理块编号保持稳定。显式截图与自动返图的 PNG 字节一致，同名截图可更新最近一帧。

Codex 通过 stdio MCP 注册 HpLost，收到带声明来源的暂停，随后查询队列并通过停止帧完成瞄准。用例入口为[事件实机测试](../../../tests/game/events.py)和[Codex 验证](../../../tests/game/codex_mouse.py)。
