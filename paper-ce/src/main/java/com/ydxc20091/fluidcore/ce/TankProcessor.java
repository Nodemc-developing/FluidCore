package com.ydxc20091.fluidcore.ce;

/** Invoked exclusively on the tank owner, every tick while a recipe is in progress. */
public interface TankProcessor {
    record Progress(String recipe, long elapsed, long total) {
        public int percent() { return total == 0 ? 100 : (int) Math.min(100, Math.floor(elapsed * 100.0 / total)); }
    }
    /** True means work can progress; false permits the native ticker to sleep until an external change. */
    boolean tick(FluidTankController tank, int elapsedTicks);
    /** Explicit handheld container recipes; false guarantees no committed item or fluid changes. */
    default boolean interact(FluidTankController tank, org.bukkit.entity.Player player, int slot) { return false; }
    default Progress progress(FluidTankController tank) { return null; }
    default void retired(FluidTankController tank) {}
}
