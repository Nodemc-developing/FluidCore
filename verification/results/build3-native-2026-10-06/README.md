# Build 3 原生联测

冻结 FluidCore JAR：`ae4c584cf18c1c11d716fed395bb8cdcdf05a204d1fcb68b784128220933f04a`。Paper 26.3 / CE 26.9.2、Folia 26.2 / 固定 CE 26.10 各完成 19 项原生检查及 2 项加载日志检查，合计 42 项通过。此联合总数包括 Farmersdelight-Plugin-Pro 1.2.2 的消费者业务，不是独立流体库测试数。

`summary.json`、`restore-proof.json` 及两组 `environment.json`、`report.json`、`result.json`、`native-profile-restore-proof.json` 从原运行记录逐字节复制，保留原路径、时间及结果。[manifest.json](manifest.json) 列出范围、制品哈希及原始资料校验值。不包含服务端日志、世界、数据库、原配置或缓存副本。

原生桶瓶操作使用脱离玩家库存的真实 Bukkit 物品。保存恢复验证原生物品字节、CE ID、流体、容量和玻璃颜色；失败转移检查物品与流体守恒。真人界面、实际连接发包、跨区域玩家事务、完整世界 controller 保存／重启及性能均未在本批次验证。未执行项保留 `SKIP`，不会计入通过数。

完整说明见 [兼容矩阵](../../../docs/compatibility.md) 和 [验收记录](../../../docs/verification.md)。此前 14 个固定组合属于其记录的旧候选 JAR，不改写为 Build 3 运行结果。
