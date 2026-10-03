# 验收记录

作者及维护者：ydxc20091。此文件只记录实际执行的验证。下列 2026-10-01 记录对应旧制品，其 [校验与结果清单](../verification/results/release-manifest.json) 不用于认证当前 Java 25、CraftEngine 26.10 平台模块及新储罠菜单。

当前构建和目标范围见 [主 README](../README.md) 与 [兼容矩阵](compatibility.md)。新的容器显示修正仍需要真人客户端确认；历史菜单、发包或性能记录不能代替这项验收。源码分发保留历史结构化结果，但排除测试服日志。

## 历史自动化与独立接入（2026-10-01）

开发候选版已建立 12 个测试套件，共 136 项行为与回归测试，覆盖以下范围：

- 流体模型、标准属性、类型化组件、`long` 边界、溢出、标签与配方。
- 数量守恒、模拟无副作用、异常回滚、嵌套事务、存储视图、过滤及限速。
- 注册表整批发布、元数据覆盖、取消恢复、组件编码、损坏与未知记录保护。
- 真实槽位验证、堆叠与满背包、公开容器 handler、初始化内容及 PDC 数据保护。
- provider 注销和重载失效、非法数量与模拟副作用拒绝、配置及有界后台队列取消／过期状态。

2026-10-01：`ydxc20091` 包名与署名的本次构建和测试复核通过，12 个套件共 **136 项测试，失败／错误／跳过均为 0**。当前 [JUnit XML](../verification/results/tests/) 与发布清单保存该制品的实际运行结果。

独立消费者源代码位于 [verification/consumer](../verification/consumer/)。它只通过 Maven 坐标引用 API、core 和 Bukkit 桥接，不引用项目源文件或 CE 类型。2026-10-01 本地发布后离线编译并执行通过，成功标记为 `FLUIDCORE_MAVEN_CONSUMER PASS`；[结果记录](../verification/results/maven-consumer-author-release.json) 随源码提供。

## 历史固定服务端（2026-10-01）

该历史批次使用以下固定构建。每份结果记录 Java、服务端、CE 和 FluidCore JAR 的校验值；这些结果不扩展到当前新制品。

| 固定组合 | 本次状态 | 原始结果 |
| --- | --- | --- |
| Paper 1.21.4 build 232 / Java 21.0.6 | 标准 API＋完整场景＋重启通过 | [author-release](../verification/results/server-paper-1.21.4-author-release.json) |
| Paper 1.21.11 build 132 / Java 21.0.6 | 标准 API＋完整场景＋重启通过 | [author-release](../verification/results/server-paper-1.21.11-author-release.json) |
| Paper 26.3 build 140 / Java 25.0.2 | 标准 API＋完整场景＋重启通过，实验构建 | [author-release](../verification/results/server-paper-26.3-author-release.json) |
| Folia 1.21.4 build 6 / Java 21.0.6 | 标准 API＋完整场景＋重启通过 | [author-release](../verification/results/server-folia-1.21.4-author-release.json) |
| Folia 26.2 build 7 / Java 25.0.2 | 标准 API＋完整场景＋重启通过 | [author-release](../verification/results/server-folia-26.2-author-release.json) |

完整场景包括标准属性继承、显式元数据覆盖、嵌套标签、真实 CE 水壶和 ItemStack／PDC、部分填充与抽取、模拟输入保护、EMPTY 数据键删除及错误类型保护；另检查 CE 放置、实际 loot 掉落／重新放置、controller 保存／加载、零 ticker 储罐、处理器睡眠唤醒及所属区域约束。重启检查此前保存的 3000 mB 水恢复。

首次启动的预期标记为 `FLUIDCORE_STANDARD_API PASS`、`FLUIDCORE_VERIFY PASS`、`FLUIDCORE_CE_VERIFY PASS`，重启为 `FLUIDCORE_PERSISTENCE PASS`。程序化验收不代替真人客户端、GUI 或保护插件组合；各目标版本的范围见 [兼容矩阵](compatibility.md)。

## 当前边界

尚未完成逐版本运行验收。未获取的服务端构建不判断为兼容或不兼容，不用相邻版本替代。真人客户端菜单、保护插件组合及异常配方睡眠后的专门 CE 重载恢复场景仍有未测范围。API 尚未冻结为 1.0。

独立 [JMH 模块](../benchmarks/README.md) 提供本库操作的测量方法。正式性能结果需要固定制品与完整环境记录，行为验收不会自动形成耗时或分配量承诺。
