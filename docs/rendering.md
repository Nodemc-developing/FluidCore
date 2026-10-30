# Optional fluid rendering

`BukkitFluidCoreService.renders()` supplies reusable GUI items and optional models for an existing display renderer.
The service has no default styles and does not extract resource packs from other plugins. Register a style explicitly,
including during the addon's `onLoad`, and rebuild the CraftEngine resource pack normally:

```java
var style = FluidRenderStyle.builder("myaddon:storage")
    .worldModels(true)
    .overlay("myaddon:gui/tank_frame")
    .build();
var registration = service.renders().registerStyle(this, style);
ItemStack meter = service.renders().icon(style.id(), contents, capacity);
FluidRenderGrid grid = service.renders().grid(style.id(), 3, 3, layersBottomToTop, capacity);
ItemStack cell = grid.item(0, 0);
int selectedInputLayer = grid.layerAt(0, 0);
```

Each grid cell is an actual `ItemStack`; the top row has index zero. `layerAt` selects the input layer occupying the
most visible bands in that cell, or `-1` for an empty cell. Repeated reads return detached stacks. Amounts remain mB;
rendering never changes storage, block states, collisions, models already used by another pack, or inventories.

The grid is bounded to 9 columns, 6 rows, and 64 input layers. Each cell uses 16 horizontal bands. Cumulative amount
arithmetic uses `BigInteger`, so even several `Long.MAX_VALUE` inputs do not overflow. Excess content is visually clipped
at capacity and reported by `grid.layout().overflow()`; the input content is retained. A zero-capacity layout is empty.

Styles can supply pure `textures(definition -> resourceId)` and `colors((stack, definition) -> argb)` functions. The
default texture and color come from registered fluid metadata; absent textures use FluidCore's own white tile. Color
tints use the RGB channels; transparency comes from the referenced texture. An
optional overlay references a texture in an existing content pack without copying it. Resolvers must be thread-safe
and must not access worlds or inventories: the texture resolver also runs during asynchronous pack compilation.

Only explicitly registered styles generate assets, all in the `fluidcore` namespace beneath a hashed style path.
Writes use new temporary files and atomic replacement; old and unrelated files are retained. Generation is bounded to
16 registered styles, 128 registered fluids, and 32768 compiled files. Invalid resolver output prevents publication.
Registering a style does not force a server reload. Before a successful pack build, after incompatible registry changes,
and on Minecraft 1.21–1.21.3, GUI methods return colored glass with exact quantities in the tooltip. Older item formats
cannot show separate fluid layers within a single GUI cell; selection and exact data remain available through the layout.

For world displays, enable `worldModels(true)` and call `worldModel(style.id(), contents, capacity)`. The returned item
selects an own-namespace liquid-box model at one of 16 levels. The addon installs it on its existing display element and
controls transforms and tracking; FluidCore does not spawn an entity or replace the appliance's block state. When assets
are unavailable it returns the ordinary colored item fallback. An empty generated model has no geometry.

Create items on the Paper main thread or the relevant Folia inventory/display owner thread. Close the registration when
an addon disposes the style; plugin-disable also removes styles owned by that plugin. Runtime shutdown closes the service
and unregisters its listeners. Retained generated files are deliberately not deleted while cached packs may refer to them.
