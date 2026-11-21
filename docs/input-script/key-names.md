# 按键名对照表

空格键写作 `space`。按住一拍用 `Key(space)`，持续按住用 `KeyPress(space)`，显式松开用 `KeyRelease(space)`。三个指令共用下面的键名。

| 键盘上的键 | 脚本键名 |
| --- | --- |
| 字母 A 至 Z | `a` 至 `z` |
| 主键区数字 0 至 9 | `0` 至 `9` |
| 空格、Tab、主键区回车 | `space`、`tab`、`enter` |
| Esc、退格、Delete | `escape`、`backspace`、`delete` |
| 左 Shift、右 Shift | `left_shift`、`right_shift` |
| 左 Ctrl、右 Ctrl | `left_control`、`right_control` |
| 左 Alt、右 Alt | `left_alt`、`right_alt` |
| 左系统键、右系统键（Win / Super / Command） | `left_win`、`right_win` |
| 上、下、左、右方向键 | `up`、`down`、`left`、`right` |
| Insert、Home、End、Page Up、Page Down | `insert`、`home`、`end`、`page_up`、`page_down` |
| F1 至 F24 | `f1` 至 `f24` |
| Caps Lock、Num Lock、Scroll Lock | `caps_lock`、`num_lock`、`scroll_lock` |
| Print Screen、Pause | `print_screen`、`pause` |
| 减号、等号、左右方括号 | `minus`、`equal`、`left_bracket`、`right_bracket` |
| 分号、单引号、反引号 | `semicolon`、`apostrophe`、`grave_accent` |
| 逗号、句点、正斜杠、反斜杠 | `comma`、`period`、`slash`、`backslash` |
| 小键盘数字 0 至 9 | `keypad_0` 至 `keypad_9` |
| 小键盘回车、小数点 | `keypad_enter`、`keypad_period` |
| 小键盘加、减、乘、除 | `keypad_add`、`keypad_subtract`、`keypad_multiply`、`keypad_divide` |

按物理键位填写键名；该键在游戏中执行的动作取决于玩家的键位设置。表中拼写采用 Minecraft Java Edition 26.3 的键盘标识（`InputConstants.Type` 注册的 `key.keyboard.*`），去掉前缀并将名称中的点换成下划线。例如 `key.keyboard.left.shift` 对应 `left_shift`。

Rust 解析器按原样保留键名，接受 ASCII 字母、数字和下划线，区分大小写。编写脚本时使用表中的小写拼写。正式游戏端的物理键位派发仍待接入。
