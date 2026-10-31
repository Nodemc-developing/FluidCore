# 平台接入扩展（未发布）

本页描述开发分支的新接口，不代表历史 Build 3 已具备这些能力。已有 Java API、单罐存档和配方处理器保持兼容。

## 统一查找

通过 `BukkitFluidCoreService.lookups()` 获取 `FluidStorageLookup`。`block(Location, BlockFace)`、`entity(Entity, BlockFace)` 和 `furniture(FurnitureTarget, BlockFace)` 返回所属线程上的活存储；`item(ItemStack, StorageContext)` 返回隔离副本容器，不会自动写回背包。操作实际玩家槽位继续使用 `containerTransfers()`。

附属插件可以通过 `registerBlock`、`registerEntity`、`registerFurniture` 和 `registerItem` 注册自己的 provider，并保留返回的 `Registration`。关闭句柄、插件停用、重载代次变化或目标失效后，之前取得的视图不能继续使用。多个独立存储可以组合；provider 不应重复返回指向同一底层罐的不同包装对象。

`FurnitureTarget` 使用实例 UUID、位置和有效性检查。调用方负责让检查在家具移除或迁移时返回 false；提供入口不等于自动适配所有 CE 家具。内置单罐的 NBT 数据键保持不变，不允许在同一方块重复挂载该行为来覆盖数据。

从其他线程查询时，使用 `queryBlockScheduled`、`queryEntityScheduled` 或 `queryFurnitureScheduled`。结果只含不可变流体数据和容量，可跨异步边界读取。不得把活存储、实体或世界对象发送到后台执行器。

原版空炼药锅、满水炼药锅和岩浆炼药锅提供整桶 1000 mB 适配；水的一层是三分之一桶，不能用本库 250 mB 瓶单位代替。部分水层当前明确不解析为可操作储罐。状态变化参与原生事务，回滚恢复原方块，提交后才更新物理状态。

## 交互事件

运行服务的 `FluidContainerTransferEvent` 在真实槽位转移的事务提交前触发。通过 `transfer()` 读取方向、实际计划转移量和存储；玩家与目标位置在低层槽位入口中可能为空。取消后流体和槽位替换都回滚，返回 `ItemContainerTransfers.Result.CANCELLED`。该事件不用于离槽模拟，也不能替代附属插件的独立权限逻辑。

事件在 Paper 主线程或 Folia 当前所有者线程执行。监听器不得等待异步任务、越区访问或直接修改不参与事务的资源。需要联动资源时，用 `FluidTransaction.current()` 取得当前事务并加入嵌套操作；监听器不可另开根事务。

原有 `FluidStorageCommitEvent` 继续同步通知每次实际提交。注册表监听使用 `registry.subscribe(owner, listener)`，只接收完整的 before/after 快照；监听可能来自更新注册表的线程，世界操作仍需投递到对应所有者。

## 保存与刷新

已提交的流体、输入槽、输出槽和颜色一起发布为不可变保存检查点。CE 的保存 worker 读取同一个检查点，不读取活库存或拼接不同代次的字段。此机制不承诺玩家文件与区块文件在进程崩溃时跨文件原子落盘。

提交后立即设置区块脏标记，避免刷新前卸载时漏存；这个操作没有磁盘 I/O。外观和菜单更新按区块合并，同一任务窗口中不为每次修改重复安排刷新。每轮有处理数量上限；卸载、拆除、重载和停用会使过期控制器回调失效。流体本身仍同步提交，旧提交事件不延迟，持久化的变化字段只在所属线程编码。
