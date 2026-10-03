# FluidCore 0.1.0-SNAPSHOT — Build 2

Release tag: `v0.1.0-SNAPSHOT.2`. This is a prerelease; the API has not reached 1.0.
The plugin and Maven artifact version remain `0.1.0-SNAPSHOT` so that this release
uses the exact dependency already distributed with Farmersdelight-Plugin-Pro 1.2.0.
The original `v0.1.0-SNAPSHOT` release is retained and has different binaries.

## Included behavior

- Immutable fluid stacks, typed components, tags and strict ingredient matching.
- Transactional storage and actual inventory-slot, bucket and bottle transfers.
- Native CraftEngine tanks with normal and waterlogged variants, preserved fluid
  contents and color, configurable menus, visual levels and hopper processing.
- Sleeping tank tickers and owner-thread access, with reserved item handoffs for
  cross-region operations. A transaction never spans an asynchronous wait.
- Inventory menu previews retain item metadata; failed transfers roll back instead
  of consuming items or fluid. Unknown saved data is protected from overwriting.
- Configurable interaction messages, diagnostics and addon storage interfaces.

## Installation and compatibility

Install `FluidCore-0.1.0-SNAPSHOT.jar` in `plugins/` alongside the required CraftEngine
build. Install addon plugins separately. Developer API and core JARs are compile-time
dependencies and must not be installed as server plugins.

- Server plugin: **Java 25**, targeting **Paper 26.3** and **Folia 26.2**.
- Required CraftEngine: **26.10-20260929.192451-4**, SHA-256
  `46ebe45f31f3e3f0965179a85cb1f308d8729f53281d6c64f4ef2af5c23d99f6`.
- API and core developer artifacts use Java 21 bytecode; the server plugin still
  requires Java 25. Older CraftEngine versions are not supported by this build.
- Back up existing worlds and plugin configuration before replacing the older
  snapshot. Foreign or unrecognized stored data is retained; no migration of
  unrelated plugins' old world or item data is provided.

This release does not bundle CraftEngine. The optional examples plugin is separate
from the runtime library. bStats usage metrics use plugin ID `34449` and can be
disabled in `plugins/bStats/config.yml`.

## Verification and exact artifacts

Existing verification results for this build contain **278 tests**: 73 core and
205 Paper/CraftEngine tests, with zero failures, errors or skipped tests. The release
reuses those validated binaries and does not restart a test server or rerun performance
measurements. Real-client confirmation of container rendering remains outstanding;
passing automated tests is not a claim that every client or protection-plugin
combination has been tested.

The two artifacts below are byte-for-byte identical to the corresponding dependencies
in the Farmersdelight-Plugin-Pro 1.2.0 release. The fixed corresponding-source archive
is retained unchanged, including its build files, tests, dependency sources and
licenses; release documentation added afterward is available in this tag.

| Artifact | SHA-256 |
| --- | --- |
| `FluidCore-0.1.0-SNAPSHOT.jar` | `263752b800d2bde758433e2a380ce2bd082f72fee8ec87bd461e7f62090cd3f5` |
| `FluidCore-0.1.0-SNAPSHOT-sources.zip` | `c7d6ffca3f455804c0edd1686a12704106a1475884587fdf6a8d40b8ad833b4e` |

## Source and licenses

Maintained by **ydxc20091**. The API module uses [Apache-2.0](LICENSE-API);
the core, server adapter, examples and other project modules use
[GPL-3.0-only](LICENSE). Bundled dependencies retain their own licenses;
see [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md).
The matching complete source is included with this release. Test-server logs,
CraftEngine binaries, private attachments and build caches are excluded.

FluidCore is free and open source. There is no paid feature tier.
