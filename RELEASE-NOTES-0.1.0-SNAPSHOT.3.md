# FluidCore 0.1.0-SNAPSHOT — Build 3

Prepared prerelease for Farmersdelight-Plugin-Pro 1.2.2. The API remains a development snapshot.

- Java 21 bytecode targets Paper/Folia 1.21–26.3. Run 1.21.x servers on Java 21 and 26.x on Java 25.
- Supports pinned CraftEngine 26.9.2 and 26.10. The optional 26.10 adapter retains native sleep and chunk subscriptions; 26.9.2 uses an idle business gate and indexed chunk-load notifications.
- Adds Bukkit tank menus for 1.21–1.21.3. Newer servers retain Sparrow UI. Delayed clicks check the actually displayed items, and ordinary player-inventory operations keep their native behavior.
- Preserves early integer CustomModelData and stored glass colors. Modern color updates retain additional colors, floats, flags, strings and unrelated persistent data. Dynamic multi-color rendering requires a compatible client and content pack.
- Recognizes ordinary water bottles with empty native effect lists on Paper 1.21; actual effect-bearing and non-water potions remain rejected.
- Protects unknown container payloads through generic data keys across namespaces. Rejected transfers preserve the original item and fluid; damaged or unknown data is never treated as an empty container.

## Verification

221 platform tests passed on Java 21 with CraftEngine 26.9.2. The unchanged core module retains its 73 passing tests. The same frozen runtime JAR passed joint native checks with Farmersdelight-Plugin-Pro 1.2.2 on Paper 26.3 / CE 26.9.2 and Folia 26.2 / pinned CE 26.10: 38 native checks and 4 load-log checks. This combined count includes consumer features, rather than 42 independent FluidCore tests.

The native fluid checks cover detached Bukkit bucket/bottle simulation and execution, native ItemStack byte persistence for water bottles and a 3750/16000 mB CE tank, CE identity and glass-color retention, insufficient-fluid/full-target rejection, and modern component preservation. Real-player menus, actual network delivery, cross-region player transactions, full world-controller save/restart, protection-plugin combinations and performance were not tested in this batch. Earlier seven-server/two-CE results belong to their recorded prior candidate, not this Build 3 JAR. See the [compatibility matrix](docs/compatibility.md) and [verification records](docs/verification.md).

## Fixed artifacts and dependencies

| Artifact | SHA256 |
| --- | --- |
| FluidCore runtime JAR | `ae4c584cf18c1c11d716fed395bb8cdcdf05a204d1fcb68b784128220933f04a` |
| CraftEngine 26.9.2 | `1f9e0935a11e7d6c7a979f2d521ec24efb715375c876a57ef3cc11e9fb9895aa` |
| CraftEngine 26.10 snapshot | `46ebe45f31f3e3f0965179a85cb1f308d8729f53281d6c64f4ef2af5c23d99f6` |

Install one supported CraftEngine JAR separately. Release assets include corresponding source and license records; `SHA256SUMS.txt` supplies their checksums. Build 2 remains available as the historical Java 25 release. This build does not convert foreign world or item records.
