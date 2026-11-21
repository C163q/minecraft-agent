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
  "held": {
    "keys": {},
    "buttons": {"left": "temporary"}
  },
  "moves": [],
  "max_speed_px_per_tick": 50
}
```

- `world` 为 `paused` 表示脚本暂停了世界，`unpaused` 表示世界仍在运行，`not_needed` 表示未进入世界或游戏已因原版界面等原因暂停。
- `held.keys` 和 `held.buttons` 只列出脚本当前按住的键和按钮。`temporary` 由下一拍的输入决定是否继续；`persistent` 持续到显式释放。上例中再提交 `MousePress(left)` 会接手为 `persistent`，直接提交 `Wait(1)` 则会松开。没有移动时单独执行 `WaitMouseMove()` 占零拍，左键依然是 `temporary`。如果之前已经显式按住左键，则上例结束时仍为 `persistent`。
- `moves` 按启动顺序列出每项未完成的移动。`remaining_px` 为换算后剩余的有符号 X、Y 位移。`requested_speed_px_per_tick` 是限速前的请求速度，直线移动使用单个数值，分轴移动使用带 `x`、`y` 的速度大小对象。各项会独立保留，即使本拍的实际合位移为零。
- `max_speed_px_per_tick` 是游戏端最近接受的脚本使用的合速度上限，新世界在首次提交前为 50。下一次提交时使用提交方的 MCP 配置。

例如 `MouseMove(Px(160), Px(0), Px(80))` 执行一拍后，在默认限速下，该项为 `{"remaining_px":{"x":110,"y":0},"requested_speed_px_per_tick":80}`。追加 `Wait(1)` 后 X 余量为 60；执行 `CLEAR` 后，`moves` 为空数组。
