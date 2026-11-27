# 查询玩家和准星目标

调用 `inspect_game` 并传 `{}`，可以在当前单人游戏中直接读取客户端所见的状态。游戏运行时目标可能移动，查询结果描述采样时的那一帧。脚本停在 `PAUSE` 时也可以查询，待续输入会保留。

| 字段 | 怎么读 |
| --- | --- |
| `world` | `paused` 表示脚本或快捷键已暂停世界，`unpaused` 表示运行中，`not_needed` 表示当前无需脚本暂停，例如标题页或原版暂停菜单 |
| `screen` | 当前界面或遮罩类型，例如 `InventoryScreen`。普通世界视图为 `null` |
| `player.dimension`、`player.position` | 当前维度 ID 和脚底的世界坐标 `{x,y,z}` |
| `player.rotation` | 玩家绝对朝向 `{yaw,pitch}`，单位为度。向右转时 yaw 增加，向下看时 pitch 增加 |
| `player.main_hand`、`player.off_hand` | 各返回物品注册名 `id` 和数量 `count`，空手为 `null` |
| `player.health` | `current` 和 `max` 为普通生命点数，`absorption` 为额外生命点数。两点相当于一颗心 |
| `player.food`、`player.air` | 饥饿值为 0–20 点。氧气的 `current`、`max` 是原版气量，以游戏 tick 计 |

根据 `target.kind` 判断下一次世界内点击对准的对象。

- `block` 携带方块注册名 `id`、整数格坐标 `position` 和命中面 `face`（`up`、`down`、`north`、`south`、`east`、`west`）。
- `entity` 携带类型注册名 `type` 和当前世界中的数值 `entity_id`，例如 `{"kind":"entity","type":"minecraft:cow","entity_id":42}`。
- `miss` 表示原版准星落空。
- `unavailable` 时查看 `reason`。背包、箱子或聊天等界面接收输入时为 `screen_open`，仍可查询玩家数值。在标题页等缺少世界或玩家的场合为 `no_world`，此时 `player` 为 `null`。世界内尚无原版命中结果时为 `no_hit_result`。

传 `{"include_screenshot":true}` 时，返回状态的同时还会收到同一帧的 PNG、原始像素宽高和 `screenshot_id`，可在同一 MCP 会话中交给 [cursor_hint](input-script/cursor-hint.md) 标点。文本和 MCP 的 `structuredContent` 包含相同的状态字段。
