# 文档入口与职责

本文面向参与 Minecraft Agent 开发的人。编写与维护文档遵循[文档规范](documentation.md)。

| 位置 | 职责 |
| --- | --- |
| [脚本语法参考](input-script/README.md) | 供用户与 LLM 编写输入脚本时按需查阅 |
| [查询玩家和准星目标](inspect-game.md) | 查看游戏世界状态、玩家数值和原版命中结果 |
| [项目协作约定](../AGENTS.md) | 开发阅读入口与游戏验证约定 |
| [文档规范](documentation.md) | 文档读者、分类、命名及维护规则 |
| `docs/ai/` | 对应 AI 开发环境的专项约定 |
| [开发文档](development/README.md) | 持续维护开发环境、构建、接口及验证方法 |
| [调研目录](research/) | 带日期的调研记录，保留适用版本、证据、结论与尚待核实的行为 |
| `docs/superpowers/` | 按 Superpowers 技能约定放置工作文档，包括 `specs/` 下的设计规格和 `plans/` 下的实施计划 |
| `docs/local.md` | 仅在本机维护的安装路径、实例和工具链信息，由 Git 忽略 |
| `.tmp/` | 工作区内跨重启保留的临时文件，例如探针、测试实例和日志，由 Git 忽略 |

## 调研入口

- [26.3 单人世界与箱子界面暂停](research/2026-11-21-container-pause.md)
- [26.3 输入后端与 MCP 图片传输](research/2026-11-21-input-and-mcp.md)
- [普通键盘输入与 END 的停止边界](research/2026-11-21-keyboard-tick.md)
- [暂停确认、箱子状态同步与停止截图](research/2026-11-21-stop-frame.md)
- [END 停止帧中的玩家红心和实体位置](research/2026-11-21-world-stop-frame.md)
- [26.3 玩家受伤与客户端生命值更新](research/2026-11-22-hp-lost.md)
- [Codex 通过 Rust MCP 直接观察 Minecraft 停止帧](research/2026-11-21-codex-image.md)
- [Codex 在两种 GUI 缩放下看图取出钻石](research/2026-11-21-codex-gui-click.md)
- [Codex 第一人称瞄准与持续挖掘](research/2026-11-21-first-person-mining.md)
- [未完成的鼠标移动跨暂停续跑与清除](research/2026-11-21-buffered-mouse.md)

## 设计记录

- [input_script 的时间与键盘输入](superpowers/specs/2026-11-21-input-script-design.md)
- [鼠标输入与截图瞄准](superpowers/specs/2026-11-21-mouse-input.md)，[实施与验证计划](superpowers/plans/2026-11-21-mouse-input.md)
- [暂停、手动继续与增量队列](superpowers/specs/2026-11-22-pause-queue.md)，[实施与验证](superpowers/plans/2026-11-22-pause-queue.md)
- [输入事件的注册、触发与查询](superpowers/specs/2026-11-22-input-events-design.md)，[实施与验证](superpowers/plans/2026-11-22-input-events.md)
- [玩家死亡与分轴碰撞事件](superpowers/specs/2026-11-22-death-collision-events.md)
- [输入脚本的函数与提前返回](superpowers/specs/2026-11-27-input-functions-design.md)，[实施与验证](superpowers/plans/2026-11-27-input-functions.md)
- [输入脚本的 IMPORT 设计](superpowers/specs/2026-11-27-input-import-design.md)，[实施与验证](superpowers/plans/2026-11-27-input-import.md)
- [查询当前游戏状态](superpowers/specs/2026-11-27-inspect-game-design.md)，[实施与验证](superpowers/plans/2026-11-27-inspect-game.md)
