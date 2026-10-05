# 独立示例插件

作者：ydxc20091。`FluidCore-Examples-0.1.0-SNAPSHOT.jar` 为独立可选插件，依赖 FluidCore 和固定的 CraftEngine 26.9.2 或 26.10 快照。首次加载会将内嵌 pack 安装到 `plugins/CraftEngine/resources/fluidcore-examples`，不覆盖已有文件。更新示例 pack 时自行检查已有配置。

包含 8000 mB 普通储罐、4000 mB 自定义水壶，以及组合储罐 controller 的休眠水处理器。普通储罐无 ticker；处理器每次唤醒最多运行一个事务，将不少于 1000 mB 的整罐水变为等量 `fluidcoreexample:heated_water`，保留组件，然后 `sleep()`。没有配方时同样睡眠。

示例通过 `FluidStorageCommitEvent` 响应来自容器和其他插件 API 的流体提交，并在必要邻更、空手操作及加载时唤醒。受保护数据或配方流体未注册时事务回滚、处理器睡眠，每个 controller 最多报告一次拒绝；CE 重载后在各方块所属区域重新检查并唤醒仍有效的处理器。不使用遍历世界的定时任务，也不异步操作世界。可查看 `HeatingController` 学习自定义 CE controller 的 `SleepingBlockEntityTicker` 用法。

安装步骤：

1. 将主插件、示例插件和 CraftEngine JAR 放入 `plugins` 后启动。
2. 检查 CE pack 解析日志；使用 CE 的物品给予命令获得 `fluidcoreexample:tank`、`fluidcoreexample:heater`、`fluidcoreexample:canteen`。
3. 储罐使用原版水桶或水壶存取。处理器放入一桶水后会转换为热水，用水壶抽出；原版桶不装自定义流体。
4. 检查空处理器睡眠；通过实际 API 或水壶改变储罐内容后恢复工作。
5. 破坏并重放储罐检查数据保留；满背包、取消事件、创造模式及区块卸载按主项目验收场景检查。

pack 使用 `fluidcore:preserve_tank` loot function 保留掉落流体。示例安装和编译不等于实服行为通过，运行验证见根文档。
