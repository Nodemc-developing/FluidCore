# 可复现验收

作者：ydxc20091。此目录保存历史测试结果与隔离服务端启动器。2026-10-01 结果对应旧制品；不能用它们宣称当前 Java 25 和固定 CraftEngine 26.10 构建已经完成相同运行验收。

启动器只在项目自身 `.local/verification/servers` 创建测试服务器，绑定 `127.0.0.1`，使用 `fluidcore-verification` 世界。官方服务端与 Mojang payload 均校验 SHA256；网络失败有限重试，校验失败的文件不能启动。每个目录持有进程锁，避免两个测试同时写同一世界。

## 构建与单元测试

```powershell
$env:FLUIDCORE_CE_JAR = 'C:/dependencies/craft-engine-paper-plugin-26.10-SNAPSHOT.jar'
.\gradlew.bat build distribution '-PceJar=C:/dependencies/craft-engine-paper-plugin-26.10-SNAPSHOT.jar'
.\gradlew.bat collectVerificationResults
```

`collectVerificationResults` 应在测试完成后单独执行。完整源码包包含构建脚本、示例、许可证与结构化历史结果；不包含测试服日志、CE JAR 或本地工作缓存。

API、核心和桥接制品已实际通过本地 Maven 发布，独立消费者用这些坐标离线编译通过，没有直接引用项目源文件或 CE 类型。

[独立消费者源码](consumer/src/main/java/com/ydxc20091/consumer/Consumer.java) 随源码包提供；与主工程没有 `project(...)` 依赖。先发布本地制品，再用相同坐标验证：

```powershell
.\gradlew.bat :api:publishToMavenLocal :core:publishToMavenLocal :paper-ce:publishToMavenLocal
.\gradlew.bat -p verification/consumer clean verifyStandardApi --offline
```

该消费者实际运行标准 metadata、typed component、配方标签、override 恢复及 native transfer，并编译 Bukkit 容器入口；不会通过编译消费者推导真实客户端兼容性。

## 实际 CE 方块和重启

需要服务器所有者已接受的 EULA 文件。`--eula-file` 只接受包含 `eula=true` 的现有文件；启动器不代替所有者接受协议。

```powershell
python -X utf8 verification/run_server.py --project paper --version 26.3 --full --ce-jar 'C:/dependencies/craft-engine-paper-plugin-26.10-SNAPSHOT.jar' --eula-file 'C:/your-test-server/eula.txt' --java 'C:/Program Files/Java/jdk-25/bin/java.exe' --port 25790
```

26.x 需使用 Java 25 路径。Folia 将 `--project` 改为 `folia`，并指定官方可获取版本。`--server-jar` 可使用既有固定官方构建，结果记录其 SHA256；没有此参数时从官方 Fill API 下载。`--ce-library-cache` 可复制既有 CE 库缓存，`--bundler-cache` 可复制已有 bundler 缓存，仅复制到项目隔离目录。

基础检查验证模拟、守恒、回滚、编码和服务注册。`--full` 另外检查真实 CE 配置加载、储罐放置、controller 保存与加载、真实 ItemStack/PDC 与槽位替换、破坏掉落和标准 CE 物品重新放置、空闲储罐原生休眠与修改后唤醒，以及停止后再次启动的数据恢复。Folia 还在真实远隔区域验证跨区域拒绝及两端守恒。

当前 full 夹具还核验 `FLUIDCORE_STANDARD_API PASS`：标准元数据配置、原版水显式 overlay 和基础定义保留、嵌套标签、CE 容器标签过滤、公开部分转移及物品数据保护。`--result-label author-release` 给本次 JSON／日志添加验收批次标记；`--refresh-example-pack` 只把示例 JAR 中的 pack 文件刷新到本项目的隔离服务器目录，校验每个条目不能越出该目录。已有示例配置不会在一般插件启动时自动覆盖。

测试命令需要 `plugins/FluidCore/verification.enabled` 文件及上述专用世界。普通服务器不应创建此文件。玩家库存夹具使用真实 Bukkit 物品和 PDC，但并非真实客户端、保护插件或 GUI 端到端测试。

具体通过状态、未测范围和结果链接见 [验收记录](../docs/verification.md) 与 [兼容矩阵](../docs/compatibility.md)。
