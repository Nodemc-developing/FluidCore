# FluidCore

A fluid API for CraftEngine, Paper and Folia plugins, maintained by **ydxc20091**.
FluidCore supplies shared fluid data, storage and container operations for addon developers. Gameplay and content are provided by addons.

Current version: `0.1.0-SNAPSHOT`. The API is still under development.

Latest prerelease: [Build 2](https://github.com/Nodemc-developing/FluidCore/releases/tag/v0.1.0-SNAPSHOT.2).
Its runtime JAR and matching source archive are identical to those bundled with
Farmersdelight-Plugin-Pro 1.2.0. See the [release notes](RELEASE-NOTES-0.1.0-SNAPSHOT.2.md)
for the fixed CraftEngine build, checksums and verification scope.

## Features

- Immutable fluid identities, stacks and typed components; quantities and capacities use `long`.
- Fluid registration, metadata, tags, filters and recipe ingredient matching.
- Single and multiple tanks, read-only views, directional ports and rate limits.
- Synchronous transactions with nested rollback, plus `SIMULATE` / `EXECUTE` operations.
- Vanilla buckets, custom CraftEngine containers and transfers through actual inventory slots.
- Versioned persistence with protection for unknown or damaged data.
- CraftEngine storage integration, provider registration and on-demand diagnostics.

One bucket is **1000 mB**. Fluid identity includes its components, so different component values cannot be merged.

Container transfers also work while sneaking. Creative players retain a filled container
when injecting fluid; extracting into an empty container delivers the filled item and
deducts the stored amount. Insufficient fluid, capacity or inventory space rolls back the transfer.
Empty-hand interaction displays stored fluid quantities; failed transfers report their reason.

Tanks can optionally update existing block properties without an idle ticker:

```yaml
behavior:
  type: fluidcore:tank
  capacity: 16000
  visual:
    level_property: fluid_level
    fluid_property: fluid_kind
```

Declare `fluid_level` as an integer property containing 0 through 16, and `fluid_kind`
as a string property containing `empty`, `water`, `milk`, `lava`, `honey` and `other`.
The content pack supplies appearances for those states. Changes refresh the existing
block and controller; display levels do not round the stored mB quantity. Non-empty
amounts occupy at least one visible level, and unknown registered fluids use `other`.

## Requirements and installation

- A compatible Paper or Folia server running CraftEngine 26.10.
- Java 25 for the FluidCore server plugin and optional examples plugin. The API and core developer artifacts use Java 21 bytecode; this does not make the server plugins compatible with Java 21.
- The pinned **CraftEngine 26.10-SNAPSHOT** build specified by `ceSha256` in [gradle.properties](gradle.properties).

Install CraftEngine and place `FluidCore-0.1.0-SNAPSHOT.jar` in the server's `plugins` directory. Restart the server. Install `FluidCore-Examples-0.1.0-SNAPSHOT.jar` separately if you want the optional example pack.

FluidCore uses bStats for basic usage metrics (plugin ID `34449`). You can disable metrics in `plugins/bStats/config.yml`.

## Building

Supply the matching CraftEngine JAR, then build the plugin and publish developer artifacts locally:

```sh
FLUIDCORE_CE_JAR=/path/to/craft-engine-paper-plugin-26.10-SNAPSHOT.jar ./gradlew distribution publishToMavenLocal
```

On Windows, set `$env:FLUIDCORE_CE_JAR` and run `.\gradlew.bat distribution publishToMavenLocal`. Plugin JARs are written to `dist/`. The build checks the CraftEngine checksum.

## Developer setup

```kotlin
repositories { mavenLocal() }
dependencies {
    compileOnly("com.ydxc20091.fluidcore:fluidcore-api:0.1.0-SNAPSHOT")
    compileOnly("com.ydxc20091.fluidcore:fluidcore-core:0.1.0-SNAPSHOT")
    compileOnly("com.ydxc20091.fluidcore:fluidcore-paper-ce:0.1.0-SNAPSHOT")
}
```

Use the core module for default storage implementations and the Paper module for Bukkit services or CraftEngine integration. Keep these dependencies out of your addon JAR and declare `FluidCore` as a required plugin dependency.

For `paper-plugin.yml`:

```yaml
dependencies:
  server:
    FluidCore:
      load: BEFORE
      required: true
      join-classpath: true
```

## Transaction example

```java
import com.ydxc20091.fluidcore.api.*;
import com.ydxc20091.fluidcore.core.FluidTank;

var context = StorageContext.confinedToCurrentThread();
var water = FluidVariant.of("minecraft:water");
var source = new FluidTank(8000L, context);
var target = new FluidTank(8000L, context);
source.fill(FluidStack.of(water, 2000L), FluidAction.EXECUTE);

try (var tx = FluidTransaction.open()) {
    long taken = source.extract(water, 1000L, tx);
    long accepted = target.insert(water, taken, tx);
    if (taken == 1000L && accepted == taken) tx.commit();
}
```

Closing without committing restores both tanks. This example uses in-memory storage; access world storage through the Bukkit service on its owning thread. Atomic transfers require transactional participants in the same valid ownership context. Transactions cannot span ticks, threads or asynchronous waits; transfers across Folia regions are rejected. Run external side effects after a successful commit.

## CraftEngine integration

Use `fluidcore:fluids` and `fluidcore:fluid-tags` for registration, `fluidcore:container` for item containers, and `fluidcore:tank` for storage blocks. See the [example pack](examples/src/main/resources/pack/configuration/examples.yml) and [API guide](docs/api.md).

Tank controllers use CraftEngine's sleeping ticker API for processors and hopper transfers. They call `sleep()` when idle and `wakeUp()` after relevant changes, so idle tanks do not poll every tick. Waking a ticker schedules work; it does not immediately execute it. Fluid metadata does not automatically change world physics or lighting.

## License

The API module is licensed under [Apache-2.0](LICENSE-API). Other project modules are licensed under [GPL-3.0-only](LICENSE). Bundled dependencies retain their own licenses; see [third-party notices](THIRD-PARTY-NOTICES.md).

Copyright © 2026 **ydxc20091**.
