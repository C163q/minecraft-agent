# 开发环境与验证入口

当前验证基线是本地单人 Minecraft Java Edition 26.3 和 Fabric Loader 0.20.1。本机安装位置见工作区的 `docs/local.md`。

## 核对游戏接口

以目标实例的版本 JSON 确认 `mainClass`、Loader 和游戏要求的 Java 版本。26.3 的游戏 JAR 保留类与方法名称，可用 JDK 自带的 `javap` 核对签名和调用顺序。在 shell 中将 `MC_JAR` 设为目标客户端 JAR 的绝对路径后执行：

```sh
javap -p -c -classpath "$MC_JAR" net.minecraft.client.Minecraft
javap -p -c -classpath "$MC_JAR" net.minecraft.client.gui.Gui
javap -p -c -classpath "$MC_JAR" net.minecraft.client.server.IntegratedServer
```

单人游戏的客户端和内置服务器各有自己的线程。验证暂停时，在对应线程采样客户端当前界面和服务端世界时间；用附近实体的 tick 计数交叉核对模拟是否推进。恢复后再次检查界面和计数。已核对的调用路径和实测范围见[箱子界面暂停调研](../research/2026-11-21-container-pause.md)。
