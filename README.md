# FluidCore

A fluid API for CraftEngine, Paper and Folia plugins, maintained by **ydxc20091**.
FluidCore supplies shared fluid data, storage and container operations for addon developers. Gameplay and content are provided by addons.

Current version: `0.1.0-SNAPSHOT`. The API is still under development.

Prepared prerelease: **Build 3** (`v0.1.0-SNAPSHOT.3`), paired with Farmersdelight-Plugin-Pro 1.2.2.
It targets Minecraft **1.21–26.3**, with Java 21 bytecode and CraftEngine **26.9.2 / 26.10** APIs.
See the [Build 3 release notes](RELEASE-NOTES-0.1.0-SNAPSHOT.3.md) for changes and actual verification coverage.
[Build 2](https://github.com/Nodemc-developing/FluidCore/releases/tag/v0.1.0-SNAPSHOT.2) remains available as a historical Java 25 build.

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

- A compatible Paper or Folia server running CraftEngine 26.9.2 or the supported 26.10 snapshot.
- Build 3 targets Paper/Folia 1.21–26.3 using Java 21 bytecode: run 1.21.x on Java 21 and 26.x on Java 25. Build 2 predates this compatibility work and still requires Java 25. See the [compatibility matrix](docs/compatibility.md) for the exact versions and paths tested.
- CraftEngine 26.9.2 builds are accepted by version and required APIs, including version-preserving `-suffix` / `+build` labels. The existing 26.10 API path remains supported; unknown version families are not added. Recorded hashes in [gradle.properties](gradle.properties) identify historical verification inputs, not a build or runtime allowlist.

Install CraftEngine and place `FluidCore-0.1.0-SNAPSHOT.jar` in the server's `plugins` directory. Restart the server. Install `FluidCore-Examples-0.1.0-SNAPSHOT.jar` separately if you want the optional example pack.

FluidCore uses bStats for basic usage metrics (plugin ID `34449`). You can disable metrics in `plugins/bStats/config.yml`.

## Building

Use JDK 25 and supply the stable baseline plus the snapshot used to compile optional native sleep support:

```sh
./gradlew distribution publishToMavenLocal -PceJar=/path/to/craft-engine-paper-plugin-26.9.2.jar -PceNativeJar=/path/to/craft-engine-paper-plugin-26.10-SNAPSHOT.jar
```

On Windows, use `.\gradlew.bat` with the same two parameters. Plugin JARs are written to `dist/`; `-PfluidcoreDistributionRoot=/path/to/output` selects another artifact directory. The build checks CraftEngine metadata and required API entries; compilation validates the actual method signatures. A different checksum for the same supported version does not block the build. At runtime install one CraftEngine JAR; the 26.10-only adapter loads only when its native API exists. FluidCore does not fingerprint the installed CraftEngine JAR at startup.

Run `./gradlew testCeArtifactCompatibility` to check the build validator's version boundaries, distinct artifacts with the same version, and missing APIs. These document/API-entry fixtures do not replace compilation against the real dependency or server verification.

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

On CraftEngine 26.10, tank controllers use the native sleeping ticker API for processors and hopper transfers. On 26.9.2, a software gate suppresses idle business work while CraftEngine still checks a lightweight entry. Relevant slot, fluid and neighbor changes wake the processor; waking does not immediately execute it. Fluid metadata does not automatically change world physics or lighting.

## License

The API module is licensed under [Apache-2.0](LICENSE-API). Other project modules are licensed under [GPL-3.0-only](LICENSE). Bundled dependencies retain their own licenses; see [third-party notices](THIRD-PARTY-NOTICES.md).

Copyright © 2026 **ydxc20091**.
