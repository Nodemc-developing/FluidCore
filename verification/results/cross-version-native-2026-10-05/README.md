# 当前候选原生联测

本目录保存 2026-10-05（香港时间）完成的固定候选联测资料，原批次编号为 `20261004T165349Z`。业务 JAR SHA256：`cb5ae27460a4857202b840e31133ccb95c52e7684904a6af8942dfca15a16f91`。

7 个已登记服务端配置档各执行 CraftEngine 26.9.2 与固定 26.10 快照，共 14 个组合。每个组合 19 项原生检查、2 项加载日志检查通过，共 294 项；该数字包含 Farmersdelight-Plugin-Pro 的消费者业务，不是 294 项独立 FluidCore 测试。未执行的客户端与原版材料检查保留 `SKIP`，不计入通过数。

[manifest.json](manifest.json) 提供范围、制品哈希、报告相对路径和原始资料校验值。`summary.json`、`restore-proof.json` 以及每个组合的 `environment.json`、`report.json`、`result.json` 均从原批次按字节复制，保留当时的绝对路径、时间与结果，不改写原始检查。源码分发不包含服务端日志、世界、配置档或运行库。

流体库覆盖真实 Bukkit 物品上的桶瓶模拟／执行与守恒、原生物品字节恢复、玻璃颜色和 CE ID 保留、失败转移保护及跨版本 CustomModelData 组件。脱离玩家库存的物品操作不代表真人库存点击、跨区域玩家事务或世界 controller 保存／重启已验收。真人客户端菜单、模型、联网发包、保护插件组合与性能基准均未在该批次执行。

详细范围及历史制品区别见 [兼容矩阵](../../../docs/compatibility.md) 和 [验收记录](../../../docs/verification.md)。
