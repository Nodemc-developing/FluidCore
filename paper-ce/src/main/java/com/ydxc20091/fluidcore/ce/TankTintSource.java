package com.ydxc20091.fluidcore.ce;

import net.momirealms.craftengine.core.block.entity.render.tint.BlockEntityTintSource;
import net.momirealms.craftengine.core.block.entity.render.tint.BlockEntityTintSourceConfig;
import net.momirealms.craftengine.core.item.Item;
import java.util.UUID;

/** CE's typed renderer extension consumes only immutable render snapshots. */
final class TankTintSource implements BlockEntityTintSource {
    private final CraftEngineBridge bridge; private final UUID world; private final int x, y, z;
    private TankTintSource(CraftEngineBridge bridge, UUID world, int x, int y, int z) {
        this.bridge = bridge; this.world = world; this.x = x; this.y = y; this.z = z;
    }
    static BlockEntityTintSourceConfig<TankTintSource> configuration(CraftEngineBridge bridge) {
        return (chunk, position) -> new TankTintSource(bridge, chunk.world().uuid(), position.x(), position.y(), position.z());
    }
    @Override public void applyTint(Item item) {
        TankRenderState state = bridge.renderState(world, x, y, z);
        if (state != null) state.apply(item);
    }
}
