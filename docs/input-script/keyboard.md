# 键盘操作

将键名填入 `Key`、`KeyPress` 或 `KeyRelease` 的括号中。指令名区分大小写，命令间用空白分隔，括号内直接写参数。具体键名见[按键名对照表](key-names.md)。

| 写法 | 效果 |
| --- | --- |
| `Key(w)` | 本拍保持 W。相邻拍重复声明只产生一次按下；第一拍省略 W 时松开，除非 W 仍被显式保持 |
| `KeyPress(w)` | 从本拍起持续按住 W，经过空行和等待也保持 |
| `KeyRelease(w)` | 从本拍起松开显式保持的 W |

```text
BEGIN
Key(w)
KeyPress(w)
Wait(5)
KeyRelease(w)
END
```

第 1 拍按下 W，第 2 拍由显式保持接手，交接时不产生松开或重复按下。第 3 至 7 拍保持，第 8 拍释放。若省略第一行 `Key(w)`，则 W 恰好按住 6 拍，在第 7 拍释放。

## 同一拍的组合与冲突

同一拍可以按住多个键，如 `Key(w) Key(space)`。同一拍同时要求同一个键按下和释放是语法错误，包括 `KeyPress(space) KeyRelease(space)`，交换两者顺序也会报错。要让游戏看到一次完整的按下和释放，将两条指令放在相邻的两拍：

```text
BEGIN
KeyPress(space)
KeyRelease(space)
END
```

第 1 拍空格处于按下状态，第 2 拍开始时释放。鼠标按钮的对应写法见[鼠标输入](mouse.md)。
