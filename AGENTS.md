# AI 编码约定

本文件适用于整个仓库。修改前先阅读相关实现、调用方、Mixin 配置及
[架构说明](docs/architecture.md)，理解上游约定后再动手。

## 项目边界

- 本项目是 Minecraft 1.21.1 / NeoForge 的小型兼容附属，使用 Java 21、Gradle Wrapper。
- 功能是将 AnvilCraft 存储接入 Create 打包与仓储系统。保留轻量单模块结构；新增抽象应有具体需求。
- `PackageConnect` 负责生命周期与注册；`StorageConnection` 负责存储解析和身份；
  `StorageBridge` 负责物品接口适配；`StorageCache` 负责共享索引，`ItemKey` 封装组件键；
  `StorageUnpacking` 负责容量预判及提交；`mixin` 包只负责上游注入与委托。
- `main` 仅放生产实现，`test` 放无世界的真实 handler 回归，`integrationTest` 放专用世界检查。
  世界检查入口只由 `runServer` 加载，不得重新放回正式模组或客户端。
- 修改上游方法调用或注入点时，核对当前实际依赖版本的签名及语义，不能靠方法名猜测。
- 不顺带升级 Minecraft、NeoForge、Create、AnvilCraft、Java 或发布版本。

## Java 风格

遵守 [Google Java Style Guide](https://google.github.io/styleguide/javaguide.html)。
仓库的 Spotless 和 Checkstyle 配置是自动检查入口；不能以通过工具检查代替语义审查。

- 使用 UTF-8、LF、空格缩进。Java 基础缩进为 2 空格，通常每行不超过 100 字符；
  换行、导入排序等交给固定版本的 `google-java-format`。
- 禁止通配符导入和无用导入。一行一个语句，一次声明一个变量。
- `if`、`else`、`for`、`while`、`do` 的语句体必须加花括号，即使只有一条语句。
- 类型用 `UpperCamelCase`，方法、变量、参数用 `lowerCamelCase`，常量用 `UPPER_SNAKE_CASE`。
  缩写按单词处理，例如 `storageBlockEntity`、`storageId`。可变集合即使是 `static final`，
  也不属于 Google 定义的常量，仍使用 `lowerCamelCase`。
- 新增的 Mixin 注入方法使用 `packageconnect` 加动词的 lowerCamelCase 名称，避免 `$`。
  `@Shadow` 和覆写成员必须遵循上游签名，不能为通过风格检查改坏契约。
- 项目自有代码与构建配置的说明性注释使用中英文双语，由 AI 翻译并保证语义一致。
  Javadoc、参数说明与解释性行注释均遵守此约定；标识符、日志和上游生成的 Wrapper 不机械翻译。
- 公共和受保护的类型、构造器、非显然方法应有说明职责与约束的 Javadoc；覆写方法遵循
  Google 规则的豁免。说明线程限制、模拟行为和副作用，避免只复述方法名。
- 默认缩小可见性。优先使用有意义的名称，避免暴露可变集合或内部状态。
- 不通过排除整个文件、降低失败阈值、关闭规则或添加宽泛 suppression 来消除违规。
  上游契约确实需要例外时，给出具体依据并将例外限制在最小成员与规则范围。

## 必须保持的行为约束

- 存储访问与缓存变更仅在服务器线程执行。客户端放置预测只能走只读方块解析，
  不应创建 UUID、访问服务端 SavedData 或改变库存。
- 缓存只是原始库存的索引，不是第二份库存。缓存按全服 UUID 共享；跨维度仍视为同一存储。
- 存储 handler 被替换或升级后必须重新绑定；内容回调增量更新，批量替换触发重建；
  保留缓存上限和服务器停止时的清理。
- 物品匹配包括数据组件。内部聚合计数使用 `long`，面向 Create 的数量遵守其 `INF` 上限。
- 模拟提取和模拟拆包不能改变真实库存。拆包应先判断整包容量，再遵守 AnvilCraft 的
  类型限制、空间权重、永恒物品和溢出销毁语义执行插入。
- 不修改共享的库存摘要；交给会修改参数的上游代码前先复制。
- 保留多方块主部件解析、未加载区块保护、普通容器回退和 Create 原有放置方向优先级。
- Mixin 保持小而明确；更改目标、描述符、`remap` 或注入时机后必须验证对应场景。
  不能用降低 `defaultRequire` 的方式掩盖失效注入。

## 开发与验证

从仓库根目录运行；Windows 使用 `./gradlew.bat` 替换 `./gradlew`。
Gradle JVM 和 Java 工具链均建议使用 JDK 21。

```sh
./gradlew spotlessApply
./gradlew styleCheck
./gradlew build
./gradlew runClient
```

- `check` / `build` 已包含风格检查、JUnit 回归及世界检查编译。提交前至少运行 `styleCheck`；
  不把 `spotlessApply` 接到 `check`，检查阶段不应自动修改源码。
- `styleCheck` 执行 Spotless 与全部 Java source set 的 Checkstyle，不解析游戏依赖。
  `build` 自动下载固定依赖并编译、打包；`runClient` 启动独立开发客户端。
- GitHub Actions 分别验证风格与 Linux、Windows、macOS 构建，不代表游戏集成验证通过。
- `./gradlew test` 使用真实 handler 验证索引及容量，不启动世界；测试回调替代 SavedData 通知，
  不把这些测试当作 Mixin 或世界集成已通过。
- 涉及联动行为时，在依赖齐备的专用 `run-server` 测试环境运行 `./gradlew runServer`。
  检查当次日志的 `PACKAGECONNECT SELFTEST PASS` / `FAIL` 和
  `build/reports/integrationTest/result.txt`；启动前清除旧报告，报告缺失或失败必须让任务失败。
  修改自检入口或结果验证时，以 `-PselfTestFailure=true` 验证断言失败确实返回失败，随后正常重跑。
- 开发自检会放置/移除方块、修改库存并关闭服务器，只能使用专用测试世界。
  不连接用户实际服务器，不写入 Minecraft EULA 接受文件。固定版本的上游开发启动器自行处理
  开发模式 EULA；不得为生产启动器添加绕过选项。
- 依赖不足或无法运行检查时，准确说明缺少什么、执行过什么、哪些结论尚未验证。
- 测试覆盖实际行为及边界：模拟不变性、数据组件、容量累计、大数量、缓存失效、
  多方块、跨维度身份与上游回退。纯格式调整不添加重复实现的测试。
- 不提交 `build/`、`.gradle/`、开发世界或第三方 JAR。不重新引入本地 `libs` 或手工提取脚本。
- 依赖版本集中固定在 `gradle.properties`。Create 和 AnvilCraft 使用固定 Modrinth 发布 ID，
  编译所需的 Ponder 和 AnvilLib 必须匹配发布包内嵌版本，使用 `compileOnly` 避免运行时重复。
  使用受限 Maven 仓库，不添加 `mavenLocal()`、动态版本或未经核实的仓库。

## 变更交付

保持改动聚焦；架构调整说明具体问题、收益与行为影响。开发流程或兼容范围变化时同步更新
中英文 README 与架构说明。交付时列出变更、验证命令及结果、未验证边界；不宣称未运行的检查通过。
