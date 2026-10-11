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

缓存只是实际库存的目录，物品仍保存在铁砧工艺存储中。第一次访问建立索引；正常存取只更新变化槽位，整批替换时重建。同类型数量变化复用原有的物品组件键和槽位集合；订单提取只复制本次需要的槽位，单个槽位足够时无需复制集合。库存摘要在变化后按需生成，由多个打包器复用；摘要中的物品与私有索引键互相独立。

最多保留 256 份缓存，淘汰后下次访问重新建立，服务器停止时清理。内部数量使用 `long`；Create 显示的单种物品数量按其原生 INF 值限制为 10 亿。

完整库存摘要、到货通知、终端目录传输和铁砧工艺原生插入仍有开销。

管理员可使用 `/packageconnect stats` 查看缓存建立、扫描槽位、增量更新、索引提取、实际提取槽位与摘要命中次数的不可变快照。这些计数不是耗时测量。索引复用 Minecraft 自带的 fastutil 原生类型集合，附属不额外打包运行时库。

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

Java 遵循 [Google Java Style Guide](https://google.github.io/styleguide/javaguide.html)。项目自有代码的说明性注释和 Javadoc 使用中英文双语，由 AI 辅助翻译。AI 与贡献者约束见 [AGENTS.md](AGENTS.md)，职责划分、上游契约及验证边界见 [docs/architecture.md](docs/architecture.md)。

```sh
./gradlew spotlessApply
./gradlew styleCheck
./gradlew build
```

Spotless 8.1.0 使用 `google-java-format` 1.28.0；Checkstyle 12.3.0 使用其内置的完整 Google 规则，违规视为错误，警告容忍数为零。`spotlessApply` 自动修复排版，花括号、命名和缺失文档等问题需按检查报告修复。Checkstyle 的 XML 和 HTML 报告位于 `build/reports/checkstyle/`。

`styleCheck` 检查三个 Java source set 的源码，不编译模组，也不解析游戏依赖。`check` 和 `build` 都会执行风格检查、JUnit 回归测试，并编译世界集成检查；检查阶段不会自动改写源码。GitHub Actions 独立检查风格，并在 Linux、Windows、macOS 上构建。

`./gradlew test` 使用真实 AnvilCraft handler 执行 JUnit 测试，不启动服务器或访问世界。覆盖组件差异、模拟不变性、大数量、物理空洞与重复条目、缓存失效与淘汰、实时订单队列、原生空间权重、溢出销毁和永恒物品。固定种子的差分测试还会将容量预判与真实原生插入逐一对照，共 360 组场景。报告位于 `build/reports/tests/test/`。

`./gradlew runServer` 在专用 `run-server/` 世界中放置和移除方块、修改库存、执行集成断言，然后关闭服务器。它验证真实 Mixin 回调、连接、多方块、仓储连接器、打包、拆包、全服存储身份、存储替换、未加载区块保护、普通容器回退和放置方向。不要指向真实游玩存档。固定版本的 NeoForge 开发启动器使用上游开发模式的 EULA 处理，本项目不写入接受协议的文件；若所用启动器要求接受，请自行阅读并接受 Minecraft EULA。Windows 使用 `.\gradlew.bat`。

世界检查及其启动监听器位于 `src/integrationTest/`，只由 `runServer` 加载，不进入正式 JAR 或 `runClient`。Gradle 在启动前删除旧结果，要求 `build/reports/integrationTest/result.txt` 包含本次的 `PASS <断言数>`；结果缺失或为 `FAIL` 都会导致任务失败。日志仍保留 `PACKAGECONNECT SELFTEST PASS` / `FAIL`。可在专用世界运行 `./gradlew runServer -PselfTestFailure=true` 验证失败路径（预期失败），随后恢复正常运行。

编译成功或启动准备成功不能替代世界检查。自动检查也不能证明客户端渲染、客户端与服务端安装组合兼容性，或以毫秒计量的性能提升。

## AI 辅助代码说明

**本项目的部分源代码和文档由 OpenAI Codex 辅助生成。** 功能需求与设计方向由项目维护者提出。

## 许可证

本项目使用 **MIT 协议**，完整文本见 [LICENSE](LICENSE)。Create、AnvilCraft、NeoForge 及其他依赖仍使用各自的许可证。本项目是独立兼容附属。
