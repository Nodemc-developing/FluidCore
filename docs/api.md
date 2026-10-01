# 开发接入

作者：ydxc20091。公共接口包：`com.ydxc20091.fluidcore.api`。版本 0.1.0-SNAPSHOT，未冻结 1.0 API。

## 依赖

本地安装开发制品后：

```kotlin
repositories { mavenLocal() }
dependencies {
    compileOnly("com.ydxc20091.fluidcore:fluidcore-api:0.1.0-SNAPSHOT")
    // 使用默认实现时才需要；运行插件已包含它。
    compileOnly("com.ydxc20091.fluidcore:fluidcore-core:0.1.0-SNAPSHOT")
    // 使用 Bukkit 服务、CE 桥接或真实槽位入口时添加。
    compileOnly("com.ydxc20091.fluidcore:fluidcore-paper-ce:0.1.0-SNAPSHOT")
}
```

```yaml
dependencies:
  server:
    FluidCore:
      load: BEFORE
      required: true
      join-classpath: true
```

制品发布前需执行 `gradlew publishToMavenLocal`，当前不假定存在公共 Maven 仓库。纯 Java 数据操作只需 API 与实际采用的默认核心实现；平台接入额外需要 `fluidcore-paper-ce` 与项目本来使用的 Paper API。直接编写 CE behavior 或 controller 的插件还需 `compileOnly` 引用固定 CE JAR。所有这些编译依赖都不应重复打包到附属插件。

## 独立储罐

```java
var owner = StorageContext.confinedToCurrentThread();
var water = FluidVariant.of("minecraft:water");
var source = new FluidTank(8000L, owner);
var destination = new FluidTank(8000L, owner);
source.fill(FluidStack.of(water, 2000), FluidAction.EXECUTE);
var result = FluidTransfers.move(source, destination, water, 1000, FluidAction.EXECUTE);
```

`confinedToCurrentThread()` 只用于独立数据和测试。世界储罐使用动态所属检查，不能以创建对象时的线程充当 Folia 区域归属。`FluidTransfers` 对非事务对象或不同上下文返回明确拒绝结果。

## 事务

```java
try (var tx = FluidTransaction.open()) {
    long taken = source.extract(water, 1000, tx);
    long inserted = destination.insert(water, taken, tx);
    if (taken == 1000 && inserted == 1000) tx.commit();
}
```

没有提交时自动恢复两端。使用 `tx.openNested()` 创建嵌套事务，子提交仍受父回滚约束。支持自定义存储时必须遵守 `TransactionParticipant` 快照、验证、恢复和提交通知契约，所有修改先登记参与者。

提交通知可能抛出 `FluidTransaction.CommitNotificationException`：此时状态已经提交，不能把它当作失败再执行一次转移。通知不得执行需要回滚的资源修改。

## 自定义流体与组件

注册表通过 `register(owner, FluidDefinition)` 注册流体，标签是 `Set<FluidKey>`。`replaceOwned(owner, definitions)` 只替换该 owner 的流体，禁止覆盖其他 owner。使用稳定插件标识作为 owner；重载前先验证完整候选集合。

组件编码器通过 `registerComponentCodec(owner, key, ComponentCodec<T>)` 注册。`ComponentValue.of(bytes)` 防御性复制载荷，`FluidVariant.withComponent(key, value)` 返回新身份。没有注册对应组件编码器的持久化记录标记为 UNKNOWN，保留原始载荷并阻止容器覆盖；重新注册编码器后可重新解析恢复。

### 标准流体属性

`FluidProperties` 位于纯 Java API 模块，完整属性不可变；`FluidDefinition` 持有名称、标签及属性。旧的三参数构造仍可使用，缺省属性从 `FluidProperties.defaults()` 获得。

```java
var juice = FluidDefinition.builder("example:juice")
    .displayName("果汁")
    .tags(FluidKey.of("example:drinkable"))
    .properties(FluidProperties.builder()
        .density(1040).viscosity(1200).temperature(300)
        .color(0xffe5a13b).texture("example:fluid/juice")
        .rarity(FluidRarity.UNCOMMON).lightLevel(0)
        .bucketItem("example:juice_bucket")
        .sound(FluidSound.CONTAINER_FILL, "minecraft:item.bucket.fill")
        .build())
    .build();
registry.register("example", juice);
```

密度为 kg/m³，黏度为 mPa·s，温度为 Kelvin；亮度范围 0—15。颜色采用 ARGB；可选值返回 `OptionalInt`／`Optional`，不存在时无需约定魔法数字。`toBuilder()` 复制属性用于调整，原属性及声音 map 不变。属性不会自动改变 Minecraft 物理或世界照明；`bucketItem` 是标识，实际容器需配置 CE 设置或注册 provider。

### 类型化组件

```java
var quality = new ComponentType<Integer>(FluidKey.of("example:quality"), ComponentCodecs.INTEGER);
registry.registerComponentCodec("example", quality);
var variant = FluidVariant.of(juice.key()).with(quality, 90);
int score = variant.get(quality).orElse(0);
var stack = FluidStack.of(variant, 2000);
var split = stack.split(250);
```

内置 codec 包含整数、long、有限浮点数、布尔值、严格 UTF-8、bytes、key 和 UUID。自定义类型提供 `ComponentCodec<T>`；注册用于持久化校验，使用类型对象本身不会自动登记。身份含组件，质量不同的流体不会合并。`grow/shrink/limitSize/split` 不修改原栈并检查非法数量与溢出。

### 标签与原料

独立标签可通过 `registerTag(owner, tag, members)` 登记，`replaceOwnedTags` 一次替换该 owner 的集合；`tagMembers`、`tagsOf` 和 `hasTag` 查询不可变快照。流体和标签同时重载时用 `replaceOwned(owner, definitions, tags)`，任意候选错误都保留旧快照。

`FluidIngredient.parse(raw, registry)` 支持 ID、`#tag`、列表，以及含 `fluid`／`tag`／`any-of`／`empty` 和 `amount` 的 map。`parseSized(raw, registry, defaultAmount)` 给缺省数量；`matches(variant)` 查询身份，`matches(stack)` 包含数量门槛，`withoutAmount()` 去掉数量门槛。`candidates(registry)` 返回当前已注册的匹配身份；组件限制使用已编码的 `ComponentValue`。

## 过滤、端口和限速

`StorageViews` 提供 `readonly`、`input`、`output`、`filter`、`sided`、`rateLimit`。限速视图应使用真实 tick 上下文；纯 Java 测试上下文的 tick 固定为 0。使用组合视图时保留同一底层对象的所有权，不复制底层资源。

## CE 与物品

运行插件注册 `com.ydxc20091.fluidcore.BukkitFluidCoreService`、`CraftEngineBridge` 和 `FluidRegistry` 服务。一般附属插件通过平台服务取得注册表和已加载储罐：

```java
var fluids = getServer().getServicesManager().load(BukkitFluidCoreService.class);
if (fluids == null) throw new IllegalStateException("FluidCore is unavailable");
var storage = fluids.storageAt(location);
```

需要 CE 扩展时，也可以通过 `ServicesManager.load(CraftEngineBridge.class)` 获取桥接，然后调用 `resolver().resolve(location)`。以上访问都在所属区域线程执行；解析成功的 storage 也不能跨卸载或重载长期缓存。公共 `fluidcore-api` 不暴露 Bukkit 或 CE 类型。

第三方方块通过 `resolver().register(plugin, BlockStorageProvider)` 接入，返回的 `AutoCloseable` 负责注销，不要求继承本库储罐。内置 CE provider 优先，外部 provider 按注册顺序查询；插件停用和配置代次变化使相关句柄失效。

需要从其他线程发起查询时，使用 `querySnapshotScheduled(location)` 获得不可变 `BlockFluidSnapshot`；需要执行操作时，使用 `executeScheduled(location, storage -> ...)` 把同步操作交给所属区域。`resolveScheduled` 返回的活对象仍只能在所属线程访问，不能把 future 完成误当作解除所有权限制。

内置 `fluidcore:container` item setting 配合 item behavior，及 `fluidcore:tank` block behavior 的配置见 [示例 pack](../examples/src/main/resources/pack/configuration/examples.yml)。`FluidStorageCommitEvent` 在储罐提交后的所属线程触发，可用于唤醒依赖该储罐的机器。操作真实玩家槽位时使用运行插件的容器转移入口，不先修改复制品再无条件写回。

### 公开物品容器与数据

通过 `BukkitFluidCoreService.containers()` 取得 resolver，`itemData()` 读取／写入本库 PDC，`containerTransfers()` 提供转移。resolver 返回 count-one 隔离副本 handler，不会修改输入栈。

```java
var container = fluids.containers().resolve(item).orElseThrow();
long projected = container.fill(FluidStack.of(water, 250), FluidAction.SIMULATE);
var result = fluids.containerTransfers().tryFillContainer(item, source, 250, FluidAction.EXECUTE);
if (result.success()) {
    ItemStack replacement = result.replacement();
    // 将返回物品安装一次；使用真实槽位入口时无需调用此离槽方法。
}
```

handler 提供 `content/capacity/accepts/fill/drain/item/readResult`。原版桶保持 1000 mB 全量与拒绝组件的规则；CE 自定义容器可部分转移。已知旧内容超过新容量或不再符合新过滤时仍允许抽取修复。离槽 `SIMULATE` 不修改源储罐和输入物品，返回预计替换物品；正式执行会提交储罐，调用者负责安装返回物品，不能宣称真实库存一起原子修改。

```java
var read = fluids.itemData().read(item);
if (read.protectedData()) {
    ItemStack losslessBackup = read.originalItem();
    return;
}
fluids.itemData().write(item, FluidStack.EMPTY);
```

状态区分 ABSENT、EMPTY、PRESENT、UNKNOWN、INVALID、WRONG_TYPE 和 CONFLICT。EMPTY 删除 `fluidcore:container_data`，保存初始化标记，防止配置的初始内容重新生成。未知／损坏／错误类型记录拒绝无意覆盖；结果含原始字节、可序列化的 PDC 和原物品备份。所有 ItemStack 访问仍在所属线程完成。

`containers().registerProvider(plugin, oneItem -> handlerOrNull)` 返回可关闭 handle，停用 owner 自动注销。handler 必须仅操作隔离副本，`SIMULATE` 不改变其状态或外部资源；默认 handler 与注销后的 provider 句柄不可继续使用。

### CE 标准配置与加载阶段

`fluidcore:fluids` 支持 `display-name/density/viscosity/temperature/light-level/color/texture/rarity/bucket-item/sounds/tags`。`fluidcore:fluid-tags` 的 `values` 支持流体 ID 和 `#tag`，嵌套引用循环或缺失成员使本次发布失败。容器／储罐 `allowed-fluids` 支持同样的 ID 和标签；省略允许所有，显式空列表拒绝所有。

CE 附属 parser 使用 `FluidCoreLoadingStages.REGISTRY_READY` 作为依赖可在解析阶段读取完整注册表。FLUIDS → FLUID_TAGS → REGISTRY_READY 是实际执行的阶段，ready 后才一次替换流体与标签。必须引用这些常量实例，不要创建同名 `LoadingStage`；固定 CE 版本按 stage 实例 ID 匹配依赖。

普通 owner 注册不能覆盖其他插件。需要配置调整已注册流体时使用独立覆盖层：`replaceOwnedOverrides(owner, definitions)`／`unregisterOverrides(owner)`；`find` 返回有效定义，`baseDefinition` 保留原始代码定义。流体、标签和覆盖一起变更可用四参数 `replaceOwned(owner, definitions, tags, overrides)` 一次发布。不同 owner 的覆盖冲突会拒绝；取消覆盖恢复基础定义。

```yaml
"fluidcore:fluids":
  minecraft:water:
    override: true
    temperature: 301
```

CE `override: true` 必须已有基础定义，只调整给出的字段，未给出名称、属性和标签继承基础定义；不把上一次覆盖当作下一次配置的默认值。省略该标志的同名跨 owner 注册仍拒绝，失败不会发布一半。

首版一个 CE 方块只允许一个 `fluidcore:tank` behavior。核心 `MultiTankStorage` 及外部 provider 可提供多个罐；不要将同一 tank behavior 在一个方块里重复配置。

普通储罐无需 ticker；有工作处理器参考示例插件的 `HeatingController`。空闲时睡眠，注册表或过滤变化、外部修改、邻更和加载等必要事件负责唤醒。
