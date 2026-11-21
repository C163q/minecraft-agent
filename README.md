# Minecraft Agent

通过 Rust MCP 和 Fabric Mod，让 LLM 根据 Minecraft Java Edition 的游戏画面操作键盘。当前 Linux 版本提供 `input_script`：指定脚本文本或 UTF-8 文件，让游戏逐拍处理物理键位输入，并在段尾返回暂停状态和 PNG 截图。支持本机未向局域网开放的单人游戏。鼠标操作仍处于探针验证阶段。

## 使用入口

1. 按[构建、安装与连接](docs/development/keyboard-bridge.md)准备 Minecraft 26.3、Fabric Loader 0.20.1、Mod 和 MCP 程序。
2. 在启动器中进入单人世界，使用支持 stdio MCP 和图像结果的客户端调用 `input_script`。仅查看当前停止帧时可传入 `{"script":"BEGIN\nEND"}`。
3. 编写键盘输入时按需查阅[脚本语法参考](docs/input-script/README.md)。其余开发与实测资料见[文档入口](docs/README.md)。
