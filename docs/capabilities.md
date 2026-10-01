# 标准接口与能力

作者及维护者：ydxc20091。版本：0.1.0-SNAPSHOT。以下列出正式接口及当前实现边界；1.0 API 尚未冻结。

## 流体、存储与容器

| 能力 | FluidCore 的正式入口 | 行为与边界 |
| --- | --- | --- |
| 命名空间标识、注册及查询 | `FluidKey`、`FluidRegistry` | 所有权注册、不可变快照；禁止重载覆盖其他 owner |
| 代码定义与配置覆盖分离 | `baseDefinition`、`replaceOwnedOverrides`、CE `override: true` | 显式元数据覆盖层保留原 owner 和基础定义，取消覆盖可恢复；整批候选失败保留旧快照 |
| 名称、颜色、纹理、密度、黏度、温度、亮度、稀有度、声音、桶物品标识 | `FluidProperties`、`FluidRarity`、`FluidSound`、`FluidDefinition` | 不可变标准属性和 builder，CE 配置，内置水／岩浆／牛奶定义 |
| 不可变数量及组件 | `FluidVariant`、`FluidStack`、`ComponentType<T>`、`ComponentCodecs` | `long` 数量；类型化读写、拆分、限量、溢出检查 |
| 标签、成员与反向查询 | `FluidRegistry.registerTag/replaceOwnedTags/tagMembers/tagsOf/hasTag` | 代码与 CE 独立标签定义；配置支持嵌套引用并拒绝环和缺失成员 |
| 储罐容量、内容及过滤查询 | `FluidStorage`、`FluidTank` | 填充、按身份或数量抽取、模拟／执行；恢复不截断旧存量 |
| 流体转移与物品转移 | `FluidTransfers`、`ItemContainerTransfers` | 同上下文原生事务；支持指定身份或从源储罐选择身份 |
| 原料单项、标签、候选、解析、数量门槛 | `FluidIngredient` | 身份与带数量匹配、组件条件、空原料和组合 |
| 完整数据编码 | `FluidStackCodec`、`FluidReadResult` | ID／数量／组件、格式版本、CRC32C、迁移、原始记录保护 |
| 原版桶 | `ItemFluidContainerRegistry.resolve` | 水／岩浆／牛奶 1000 mB 全量转移；拒绝组件；保留名称和外部 PDC |
| CE 动态容器与白名单 | `fluidcore:container`、`ContainerDefinition` | 部分转移，ID 或 `#tag` 过滤；缩容后可排出已知旧内容修复 |
| 物品 PDC 读写与诊断 | `ItemFluidData`、`ItemFluidReadResult` | EMPTY 移除数据键并记录初始化；拒绝覆盖未知、损坏或错误类型数据 |
| 独立物品 handler 与替换结果 | `ItemFluidContainer`、`ItemContainerTransferResult` | count-one 副本、替换物品、实际转移量；输入不变；模拟返回预计替换物品 |
| CE 加载顺序扩展 | `FluidCoreLoadingStages` | 实际 FLUIDS → FLUID_TAGS → REGISTRY_READY；附属解析器可依赖 ready 阶段 |
| 方块与物品同步 | `fluidcore:tank`、`fluidcore:preserve_tank` | controller 保存、实际掉落／重新放置完整数据，外部 provider 接入 |

属性是附属插件共用的数据契约。密度单位 kg/m³，黏度 mPa·s，温度 Kelvin，亮度 0—15；负密度可描述浮力。颜色为 ARGB，六位十六进制配置补不透明 alpha。它们不会自动修改原版水流、世界照明、资源包材质或播放声音。`bucketItem` 是逻辑物品标识，实际自定义桶通过 CE 容器设置或 provider 接入。

本库一桶为 1000 mB、瓶容量常量为 250 mB；瓶常量是数量约定，不表示已实现原版药水瓶交互。

## 事务与平台扩展

| 能力 | FluidCore 的具体内容 |
| --- | --- |
| 原生事务 | 首次修改快照、嵌套提交、关闭回滚、提交前验证、提交后通知 |
| 存储组合 | 默认多罐、只读／输入／输出／过滤／侧面／速率限制视图 |
| 真实槽位 | 槽位与流体同一事务、堆叠拆分、满背包回滚、明确创造模式语义 |
| 插件扩展 | 方块及物品 provider 注册与注销，无须继承默认储罐类 |
| CE 配套 | 现成储罐、掉落保护、零空闲 ticker、独立休眠处理器示例 |
| 运维 | 标准属性查询、按需图形检查、有界后台队列、过期结果拒绝 |

普通储罐不创建 ticker；需要持续工作的处理器通过 CE 休眠接口和明确的事件唤醒。

## 接入边界

真实槽位原子操作使用 `containerTransfers().transfer(...)`。`tryFillContainer/tryEmptyContainer` 是离槽替换结果接口：正式执行提交储罐，调用者必须接收并安装返回物品一次，不能声称真实背包一起原子修改。provider 必须只操作自己的隔离副本并遵守模拟契约，不能在 handler 中改变外部库存或世界资源。

公共 API 不暴露 CE、Bukkit、Sparrow 或 Minecraft 内部类型。平台桥接位于独立模块，附属插件应通过服务和 provider 接入；接口名称、配置和持久化格式以本项目文档为准。
