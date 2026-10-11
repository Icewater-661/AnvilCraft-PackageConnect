# AnvilCraft: PackageConnect

[English](README.md)

一个连接铁砧工艺与机械动力仓储系统的小型附属，让打包器直接访问普通板条箱、大型板条箱、潜影集装箱和超维存储站。

## 功能

- 普通红石打包、仓储订单按量提取和拆包入库。
- 支持多方块存储的主部件与非主部件，无需额外存储端口。
- 放置打包器时自动将连接面朝向存储。
- 多个打包器共享同一存储的库存缓存，订单按物品及数据组件查找对应槽位。
- 识别同一存储 UUID，避免重复统计库存和向自身请求物品。
- 保留铁砧工艺的容量、物品限制和原有溢出销毁行为。

## 版本与安装

适用于 Minecraft 1.21.1、NeoForge、Java 21。当前构建使用 NeoForge 21.1.251，验证使用 Create 6.0.10 和 AnvilCraft 1.6.0+snapshot.2465。

将本附属 JAR 放入实例的 `mods` 目录，并安装 Create、AnvilCraft 及其前置，重启后生效。将打包器背面的容器连接面贴住存储，再按 Create 原有方式配置仓储连接器、终端与仪表。

核心逻辑运行在服务端。客户端安装可让放置预览与最终朝向保持一致。本附属没有注册新物品、方块或专用网络数据包，按实现预计支持仅服务端安装，但尚未验证客户端不安装的实际联机场景。单人游戏需要在本地实例安装，因为该实例同时运行内置服务器。

## 性能设计

缓存只是实际库存的目录，物品仍保存在铁砧工艺存储中。第一次访问建立索引；正常存取只更新变化槽位，整批替换时重建。库存摘要在变化后按需生成，并由多个打包器复用。

最多保留 256 份缓存，淘汰后下次访问重新建立，服务器停止时清理。内部数量使用 `long`；Create 显示的单种物品数量按其原生 INF 值限制为 10 亿。

完整库存摘要、到货通知、终端目录传输和铁砧工艺原生插入仍有开销。

管理员可使用 `/packageconnect stats` 查看缓存建立、扫描槽位、增量更新、索引提取与摘要命中次数。这些计数不是耗时测量。

## 从源码构建

安装 JDK 21，将 `JAVA_HOME` 指向该 JDK，然后克隆仓库。Gradle Wrapper 会自动下载 Gradle 和固定版本的模组依赖。首次构建需要联网，无需已有 Minecraft 实例、本地 `libs` 目录或依赖准备脚本。

Linux / macOS：

```sh
./gradlew build
./gradlew runClient
```

Windows PowerShell：

```powershell
.\gradlew.bat build
.\gradlew.bat runClient
```

`build` 的成品位于 `build/libs/`。`runClient` 在 `run-client/` 启动开发客户端，加载 Create、AnvilCraft 和本附属。IDE 直接将仓库根目录作为 Gradle 项目导入，并选择 JDK 21。

依赖版本统一固定在 [gradle.properties](gradle.properties)：

| 依赖 | 固定版本 | 发布来源 |
| --- | --- | --- |
| Create | 适用于 Minecraft 1.21.1 的 6.0.10 | Modrinth 发布 ID `UjX6dr61` |
| AnvilCraft | 适用于 Minecraft 1.21.1 的 1.6.0+snapshot.2465 | Modrinth 发布 ID `85SOrOGM` |
| Ponder | 1.0.82+mc1.21.1 | Create Maven，仅编译时使用 |
| AnvilLib | 2.0.0+snapshot.546 | AnvilLib Maven，仅编译时使用 |

两个 Modrinth 依赖均为完整发布 JAR，NeoForge 在运行时发现其中内嵌的库。编译时额外声明相同版本的 Ponder 和 AnvilLib，使 Java 编译器能够访问嵌套类，且不额外加载运行时副本。第三方依赖不打包进本附属。更新模组版本时，也应核对它们内嵌库的版本。

## 编码规范与开发检查

Java 遵循 [Google Java Style Guide](https://google.github.io/styleguide/javaguide.html)。项目自有代码的说明性注释和 Javadoc 使用中英文双语，由 AI 辅助翻译。AI 与贡献者约束见 [AGENTS.md](AGENTS.md)，架构及后续改进建议见 [docs/architecture.md](docs/architecture.md)。

```sh
./gradlew spotlessApply
./gradlew styleCheck
./gradlew build
```

Spotless 8.1.0 使用 `google-java-format` 1.28.0；Checkstyle 12.3.0 使用其内置的完整 Google 规则，违规视为错误，警告容忍数为零。`spotlessApply` 自动修复排版，花括号、命名和缺失文档等问题需按检查报告修复。Checkstyle 的 XML 和 HTML 报告位于 `build/reports/checkstyle/`。

`styleCheck` 只检查源码，不编译模组，也不解析游戏依赖。`check` 和 `build` 都会执行风格检查，检查阶段不会自动改写源码。GitHub Actions 独立检查风格，并在 Linux、Windows、macOS 上构建。

`./gradlew runServer` 保留专用功能自检行为：在 `run-server/` 修改测试世界、执行断言，然后关闭服务器。首次运行前，请自行阅读并接受 Minecraft EULA，配置该专用服务器目录；不要使用真实游玩存档。Windows 将上述命令中的 `./gradlew` 替换为 `.\gradlew.bat`。

此前实现记录过 111 项功能断言通过，覆盖存储连接、多方块、仓储连接器、订单、拆包、容量模拟、物品组件、大数量、库存身份和放置方向。这是历史功能结果，并非性能测试或后续改动的验证结果。应检查本次日志中的 `PACKAGECONNECT SELFTEST PASS` / `FAIL`：当前自检捕获失败后只写日志，不能仅凭 Gradle 成功退出判断通过。

## AI 辅助代码说明

**本项目的部分源代码和文档由 OpenAI Codex 辅助生成。** 功能需求与设计方向由项目维护者提出。

## 许可证

本项目使用 **MIT 协议**，完整文本见 [LICENSE](LICENSE)。Create、AnvilCraft、NeoForge 及其他依赖仍使用各自的许可证。本项目是独立兼容附属。
