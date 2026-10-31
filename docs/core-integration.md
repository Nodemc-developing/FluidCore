# Java API and shared storage additions

Copyright 2026 ydxc20091. The API remains `0.1.0-SNAPSHOT`; these additive contracts do not freeze a 1.0 release. The API module retains Apache-2.0 and the core module retains GPL-3.0-only. The implementations below extend FluidCore's existing transactions and storage types independently.

## Negotiate API contracts

```java
FluidCoreApi.requireApi(1);
FluidCoreApi.requireCapabilities(
    FluidCoreApi.Capability.SHARED_CAPACITY_STORAGE,
    FluidCoreApi.Capability.COMPOSABLE_TRANSFERS);
```

`API_LEVEL` describes the Java API/core contracts in this distribution. It is independent of the plugin artifact version and does not claim server-version, GUI, resource-pack or optional-addon availability. A consumer using core implementations must also declare the core compile dependency; the API artifact alone does not supply implementation classes.

## Share a capacity across fluid identities

```java
var storage = new SharedCapacityFluidStorage(16000L, 4, context);
storage.fill(FluidStack.of(FluidVariant.of("minecraft:water"), 10000), FluidAction.EXECUTE);
// Only 6000 mB remains for every other identity together.
long accepted = storage.fill(FluidStack.of(FluidVariant.of("minecraft:lava"), 10000), FluidAction.EXECUTE);
```

The slot count limits simultaneously stored variants. Equal fluid IDs with different components occupy distinct slots. The total stored quantity, rather than each slot separately, is limited by `capacity()`. `capacity(index)` returns that same physical shared limit; summing it over slots does not describe available capacity. `amount()` and `space()` report the shared budget. Internal slot storage is not exposed, and wrapped input/filter/rate-limit or concatenated views still enter the same capacity checks.

Use `contents()` to obtain an immutable fixed-slot record and `restore(List<FluidStack>)` to load it. Existing contents survive a smaller configured capacity or `resize(long)`, including registered-later identities and components. Overfull storage can drain but cannot accept more until space exists. A mismatched slot count, null entry or a combined amount outside the `long` range rejects the entire load before mutation; retain the original encoded record when handling that failure. Loading and resizing are forbidden while a transaction is open.

`MultiTankStorage` continues to concatenate independent storages with their existing capacities. It has not changed into shared-capacity storage. A custom machine can expose the new storage through a provider; existing CE single-fluid tanks keep their current save format and behavior.

## Compose transfers in one transaction

```java
try (var transaction = FluidTransaction.open()) {
    var first = FluidTransfers.move(source, intermediate, water, 1000,
        FluidAction.EXECUTE, transaction);
    var second = FluidTransfers.move(intermediate, destination, water, 1000,
        FluidAction.EXECUTE, transaction);
    if (first.successful() && first.amount() == 1000
            && second.successful() && second.amount() == 1000) transaction.commit();
}
```

The new overloads take a final parent transaction, with or without an explicit fluid variant. Each call uses a child transaction: a rejection or simulation rolls back only that operation, and a successful execution remains rollbackable by the parent. A successful result does not commit the parent or send its notifications. The caller checks the transferred quantities before committing the complete operation.

`FluidTransaction.current()` exposes this thread's innermost transaction as an `Optional`; pass it explicitly when composing with an operation already in progress. Existing overloads still create independent transactions and still reject an implicit nested open. Transactions cannot cross threads, ticks or asynchronous waits. Parent commit notification failures retain the existing `CommitNotificationException` meaning: state has committed and the operation must not be retried as an uncommitted failure.

## Observe registry publications

```java
FluidRegistry.Registration subscription = registry.subscribe(myOwner, change -> {
    var oldDefinitions = change.before();
    var newDefinitions = change.after();
    // Invalidate detached metadata caches here; schedule world changes on their owner.
});
// On consumer unload:
subscription.close();
registry.unregisterListeners(myOwner);
```

Listeners see complete immutable before/after snapshots for fluid, tag, metadata-overlay and component-codec publications. Failed candidate validation sends no change. Callbacks are serialized outside the registry monitor on an updating thread; they are not a Bukkit or Folia ownership context. Reentrant publications are queued in generation order. Closing a subscription suppresses queued callbacks for it. A listener's runtime exception is logged without converting an already published state into a failed update, and other listeners still run.
