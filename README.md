# Minecraft Agent

通过 Rust MCP 和 Fabric Mod，让 LLM 根据 Minecraft Java Edition 的游戏画面操作键盘和鼠标。Linux 上的 `input_script` 读取脚本文本或 UTF-8 文件，逐拍执行输入，返回世界暂停状态，默认附带 PNG 截图。`inspect_input` 可查询仍按住的键、按钮、未完成的移动及待执行队列。`cursor_hint` 可以在截图上标点，并给出第一人称瞄准所需的转角。Mod 确认世界暂停后，游戏画面左上角会显示双竖线图标，继续运行时图标消失。玩家可用 F8 暂停或继续，也可在按键设置中改绑。支持本机未向局域网开放的单人游戏。

## 使用入口

1. 按[构建、安装与连接](docs/development/keyboard-bridge.md)准备 Minecraft 26.3、Fabric Loader 0.20.1、Mod 和 MCP 程序。
2. 在启动器中进入单人世界，使用支持 stdio MCP 和图像结果的客户端调用 `input_script`。仅查看当前停止帧时可传入 `{"script":"BEGIN\nEND"}`。
3. 编写输入脚本或在截图上标点时按需查阅[脚本语法参考](docs/input-script/README.md)。暂停和恢复后的输入合并见[暂停与继续](docs/input-script/pause.md)。其余开发与实测资料见[文档入口](docs/README.md)。
