# 验收记录

作者及维护者：ydxc20091。此文件只记录实际执行的验证。下列 2026-10-01 记录对应旧制品，其 [校验与结果清单](../verification/results/release-manifest.json) 不用于认证当前 CraftEngine 26.10 平台模块及新储罠菜单。当前源码的 Java 21 构建与测试结果见 [兼容矩阵](compatibility.md)。

当前构建和目标范围见 [主 README](../README.md) 与 [兼容矩阵](compatibility.md)。新的容器显示修正仍需要真人客户端确认；历史菜单、发包或性能记录不能代替这项验收。源码分发保留历史结构化结果，但排除测试服日志。

## Build 3 发布候选（2026-10-06）

候选 `ae4c584cf18c1c11d716fed395bb8cdcdf05a204d1fcb68b784128220933f04a` 仅修改通用未知流体数据键识别。CE 26.9.2 的 221 项平台测试通过，未变化的 core 模块复用 73 项通过的结果；4 项新增测试检查任意命名空间、自有键排除、不透明嵌套数据不变及自有数据内部字段的归属。已有桶瓶回归增加未知 `jug`／`jug_contents` 标记，确认填充与排空均拒绝且原物品保持不变。

[构建记录及 JUnit 资料](../verification/results/native-fluid-data-guard-2026-10-05/build.json) 属于该新制品。之后该冻结 JAR 与 Farmersdelight-Plugin-Pro 1.2.2 在 Paper 26.3 / CE 26.9.2、Folia 26.2 / 固定 CE 26.10 上完成 **38 项原生＋4 项加载日志检查通过**。此 42 项联合检查包含消费者业务，不是独立流体库测试数。

桶瓶采用真实 Bukkit 物品，但脱离玩家库存；原生 ItemStack 字节恢复保留水瓶与 3750/16000 mB CE 罐完整元数据、容量、流体、CE ID、玻璃颜色及 PDC。999 mB 不足整桶、已满目标的拒绝守恒和现代颜色组件保留均通过。真人客户端、完整世界重启、跨区域玩家事务、保护插件组合与性能未在本批次验证。

[Build 3 联测资料](../verification/results/build3-native-2026-10-06/manifest.json) 原样保留两份环境／报告／结果及复原证明；配置、插件、内容缓存和登记字段已经恢复，日常环境未被改动。下列先前固定组合只认证其自身记录的 JAR。

## 兼容基线候选原生联测（2026-10-05）

同一 Java 21 候选在 Paper 1.21、1.21.3、1.21.4、1.21.11、26.3 和 Folia 1.21.4、26.2 上，分别加载固定 CraftEngine 26.9.2 与 26.10 快照。7 个已登记配置档串行完成 14 个组合，均通过。FluidCore JAR SHA256 为 `cb5ae27460a4857202b840e31133ccb95c52e7684904a6af8942dfca15a16f91`，详细核心构建与 Java 版本见 [兼容矩阵](compatibility.md)。

本批次为与 Farmersdelight-Plugin-Pro、UltimateAdvancementAPI 的联测：19 项原生检查 × 14，加 2 项加载日志检查 × 14，共 **294 项通过**。此总数包含消费者插件业务，不等于独立流体库测试数；真人客户端与早期版本不存在的原版材料等未执行项保留 `SKIP`。FluidCore 的实际覆盖为：

- 真实 Bukkit 桶 1000 mB、瓶 250 mB 的脱离玩家库存模拟／执行；投影返回物与已提交返回物均保持调用方原物品不变。
- 真实水瓶与 CE 罐经 `serializeAsBytes`／`deserializeBytes` 恢复，保留物品数量与完整元数据，以及罐内 3750/16000 mB、流体类型、CE ID、玻璃颜色及附加 PDC。
- 999 mB 不足整桶和容量为 0 的已满目标拒绝转移，原物品与双方流体均保持不变。
- 早期整数 CustomModelData 保留；现代双色更新保留尾部颜色、floats、flags、strings 及无关 PDC。Paper 1.21 普通水瓶的原生空效果列表差异通过复核。

原始结构化资料保存在 [本批次目录](../verification/results/cross-version-native-2026-10-05/README.md)：每次运行的 `environment.json`、`report.json`、`result.json` 原样保存，[汇总清单](../verification/results/cross-version-native-2026-10-05/manifest.json) 记录制品与资料哈希。[复原证明](../verification/results/cross-version-native-2026-10-05/restore-proof.json) 确认没有新建服务器，7 个配置档原插件哈希及注册字段恢复、其余配置档不变，验收后没有本批次服务端进程残留。

本次未执行真人菜单／库存操作、跨 Folia 区域玩家事务、漏斗、当前 controller 世界保存与重启、真实连接发包、保护插件组合或性能基准。下列历史完整场景报告只适用于当时旧制品，不能补作当前候选这些路径的验收。

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

当前候选已完成上表 14 个固定组合的原生联测，目标范围内其余精确版本仍未验收。未获取的服务端构建不判断为兼容或不兼容，不用相邻版本替代。真人客户端菜单、保护插件组合及异常配方睡眠后的专门 CE 重载恢复场景仍有未测范围。API 尚未冻结为 1.0。

独立 [JMH 模块](../benchmarks/README.md) 提供本库操作的测量方法。正式性能结果需要固定制品与完整环境记录，行为验收不会自动形成耗时或分配量承诺。
