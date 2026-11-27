# 查询当前输入状态

调用 `inspect_input`，参数为 `{}`。它在两次脚本提交之间读取游戏端的脚本输入状态，消耗零个输入 tick。处于暂停时可连续查询，世界时间、按键状态和移动余量都会保留。正在流逝的世界仍按原速运行。

在普通单人世界中，从所有按键已松开的状态提交下面的脚本：

```text
BEGIN
Mouse(left)
END
```

随即查询，默认配置的结果是：

```json
{
  "world": "paused",
  "pause_reason": "end",
  "held": {
    "keys": {},
    "buttons": {"left": "temporary"}
  },
  "moves": [],
  "listeners": [],
  "max_speed_px_per_tick": 50,
  "queue": {"total": 0, "entries": [], "next_cursor": null}
}
```

- `world` 为 `paused` 表示脚本暂停了世界，`unpaused` 表示世界仍在运行，`not_needed` 表示未进入世界或游戏已因原版界面等原因暂停。
- `held.keys` 和 `held.buttons` 只列出脚本当前按住的键和按钮。`temporary` 由下一拍的输入决定是否继续；`persistent` 持续到显式释放。上例中再提交 `MousePress(left)` 会接手为 `persistent`，直接提交 `Wait(1)` 则会松开。没有移动时单独执行 `WaitMouseMove()` 占零拍，左键依然是 `temporary`。如果之前已经显式按住左键，则上例结束时仍为 `persistent`。
- `moves` 按启动顺序列出每项未完成的移动。`remaining_px` 为换算后剩余的有符号 X、Y 位移。`requested_speed_px_per_tick` 是限速前的请求速度，直线移动使用单个数值，分轴移动使用带 `x`、`y` 的速度大小对象。各项会独立保留，即使本拍的实际合位移为零。
- `max_speed_px_per_tick` 是游戏端最近接受的脚本使用的合速度上限，新世界在首次提交前为 50。下一次提交时使用提交方的 MCP 配置。

例如 `MouseMove(Px(160), Px(0), Px(80))` 执行一拍后，在默认限速下，该项为 `{"remaining_px":{"x":110,"y":0},"requested_speed_px_per_tick":80}`。追加 `Wait(1)` 后 X 余量为 60；执行 `CLEAR` 后，`moves` 为空数组。

## 队列预览、分页和导出

默认返回至多 5 个待执行段。`queue.total` 是段数，一个长等待占一段。`entries` 按提交块、块内行序排列。`block` 是游戏会话中的块编号，`line` 是源文件行号，`source` 标明待执行的指令。固定时长段的 `remaining_ticks` 是十进制字符串，可准确表示 64 位剩余拍数。行首等待、输入和行末暂停可各占一段。块末隐含的 END 不计入段数。不同块剩余的首拍会同时执行，详见[暂停与继续](pause.md)。

| 调用参数 | 返回内容 |
| --- | --- |
| `{}` | 当前状态、停止原因及队列预览 |
| `{"queue":"inline","limit":20}` | 第一页，默认 20 段，每页支持 1 至 100 段 |
| `{"queue":"inline","cursor":"<next_cursor>","limit":20}` | 同一张快照的下一页，期间游戏可以恢复或清除队列；最后一页的游标为 null |
| `{"queue":"file","path":".tmp/pending.json"}` | 将含全部段的 JSON 快照原子写入 MCP 主机上的新文件，工具结果返回状态摘要、进行中的移动项数（`move_count`）、待执行段数和文件绝对路径 |

文件相对路径以 MCP 工作目录为基准，使用已存在的父目录和新的文件名。游标属于当前 MCP 进程，保留最近 8 个需要翻页的快照；旧游标失效时可重新从第一页查询。导出的等待指令采用标准写法（例如 `Wait(0005)` 记为 `Wait(5)`），已经开始的长等待以 `remaining_ticks` 表示尚未执行的长度。`pause_reason` 描述停在什么位置，在正常运行时为 null。

## 事件来源

涉及[函数调用](functions.md)的条目和监听来源带 `call_stack`。每层的 `id` 区分不同次调用，`name` 是函数名，`line` 是调用点所在的脚本行。尚未进入的函数只显示调用点。

有活动调用时，`active_calls` 按执行分支列出块号、调用栈、当前函数上一步的 `last_line` 和接下来的 `next`，有源行时附 `next_line`。例如函数的最后一行是 `PAUSE`，暂停后 `next` 为 `END`：即便 `queue.total` 已是 0，该调用仍在等待恢复后返回。触及函数递归或零拍执行的保护上限时，`pause_reason` 为 `error`，`last_error` 保留到下次恢复。

支持事件的 Mod 返回 `listeners`，每项包含 `event`、所属顶层 `block`、声明的 `line` 和 `state`。状态 `ready` 表示可响应事件，`handling` 表示正在执行上一次处理块。来自事件的队列段带 `listener` 对象，其中的块和行号指向原声明。清除监听后，仍待执行的段保留这个来源。预览、分页快照和完整导出沿用同一格式。详见[玩家事件](events.md)。
