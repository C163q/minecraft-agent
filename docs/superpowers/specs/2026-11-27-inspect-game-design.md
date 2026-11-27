# 查询当前游戏状态

本规格适用于 Minecraft Java Edition 26.3 本地单人实例。新增的只读 MCP 工具 `inspect_game` 供模型在决定下一次输入前读取玩家状态和原版准星目标。例如牛被草挡住时，工具应能告诉调用方准星命中了哪一格草。一次点击后门是否打开、是否造成伤害属于交互结果，超出本次查询范围。

## 调用与返回

`inspect_game({})` 返回结构化状态和相同内容的文本；`inspect_game({"include_screenshot":true})` 还返回该帧 PNG 图片、`screenshot_id`、`width` 和 `height`。图片 ID 可直接交给 [cursor_hint](../../input-script/cursor-hint.md)。唯一可选参数 `include_screenshot` 为布尔值，默认 `false`。默认查询跳过像素读回、PNG 编码和图片缓存。

| 字段 | 含义 |
| --- | --- |
| `world` | 沿用输入工具的暂停状态 `paused`、`unpaused`、`not_needed` |
| `screen` | 接收输入的原版界面类型，例如 `InventoryScreen`。遮罩覆盖界面时返回遮罩类型；普通世界视图为 `null` |
| `player` | 当前世界的本地玩家。标题页、加载或退出过程中缺少世界或玩家时为 `null` |
| `target` | 该帧原版准星选择的对象或不可用原因，见下表 |

`player` 含以下字段。

| 字段 | 内容与单位 |
| --- | --- |
| `dimension`、`position` | 维度资源 ID；脚底世界坐标 `{x,y,z}`，保留小数 |
| `rotation` | `{yaw,pitch}`，玩家的绝对朝向，单位为度。向右转 yaw 增加，向下看 pitch 增加 |
| `main_hand`、`off_hand` | 每只手分别返回 `{id,count}`，ID 使用原版注册名。空手为 `null` |
| `health` | `{current,max,absorption}`，单位为生命点，每颗完整红心为两点，金心单列 |
| `food` | 饥饿点数，范围 0 至 20 |
| `air` | `{current,max}`，保留原版剩余气量和上限，单位为游戏 tick |

## 准星目标

26.3 的客户端攻击入口 `Minecraft.startAttack()` 读取 `Minecraft.hitResult`。Mod 使用同一个命中结果，并在命中位置读取方块注册名。选择结果反映该帧的目标；世界运行时，实体在后续点击前仍可能移动。

| 情况 | `target` 示例 |
| --- | --- |
| 方块 | `{"kind":"block","id":"minecraft:short_grass","position":{"x":8,"y":64,"z":-2},"face":"west"}`。面名为六个原版方向的小写英文 |
| 实体 | `{"kind":"entity","type":"minecraft:cow","entity_id":42}`。数值 ID 用于在当前已加载世界内区分同类实体 |
| 准星落空 | `{"kind":"miss"}` |
| 背包、聊天、箱子、暂停菜单等界面或遮罩打开 | `{"kind":"unavailable","reason":"screen_open"}`，仍返回可取得的玩家状态 |
| 缺少世界或本地玩家 | `{"kind":"unavailable","reason":"no_world"}`。此规则优先于界面规则，标题页同时给出 `screen` |
| 已进入世界但客户端尚无命中结果 | `{"kind":"unavailable","reason":"no_hit_result"}` |

## 采样与验收

选择独立查询，使模型可以单独取数或同时看图，也避免为每次脚本提交增加返回内容。直接复用上一次停止帧会遗漏玩家手动操作和世界自然推进；本工具在处理查询后的一次客户端渲染完成时采样。在客户端线程固定字段值，同时按需发起该帧的像素读回，异步编码期间沿用已采样的数据。查询保持当时的暂停状态，不提交脚本、推进输入或触发暂停同步。旧版 Mod 缺少查询能力时，MCP 提示更新 Mod 并重启实例。

验收通过 MCP 查询独立实例的标题页、运行中的世界、脚本暂停点及打开的背包。对照已知场景核实玩家数值、双手物品、方块面、实体和落空；同一暂停点重复查询，核对服务端模拟和待续输入。默认查询只返回状态，显式请求的图片需与目标相符并能由 `cursor_hint` 引用。另核对 GUI 内的目标与上述世界外结果可区分。
