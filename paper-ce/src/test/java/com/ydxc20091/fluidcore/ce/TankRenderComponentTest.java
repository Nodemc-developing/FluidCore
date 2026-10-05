package com.ydxc20091.fluidcore.ce;

import net.momirealms.craftengine.libraries.nbt.CompoundTag;
import net.momirealms.craftengine.libraries.nbt.IntTag;
import net.momirealms.craftengine.libraries.nbt.ListTag;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TankRenderComponentTest {
    @Test void recoloringPreservesUnrelatedComponentsAndDoesNotMutateTheSource() {
        CompoundTag original = new CompoundTag();
        original.putString("extension", "retained");
        ListTag colors = new ListTag();
        colors.add(IntTag.valueOf(1)); colors.add(IntTag.valueOf(2)); colors.add(IntTag.valueOf(3));
        original.put("colors", colors);
        ListTag floats = new ListTag(); floats.add(new net.momirealms.craftengine.libraries.nbt.FloatTag(100));
        original.put("floats", floats);
        CompoundTag updated = TankRenderState.colorData(original, 0xff112233, 0xff445566);
        ListTag changedColors = (ListTag) updated.get("colors");
        assertEquals(IntTag.valueOf(0x112233), changedColors.get(0));
        assertEquals(IntTag.valueOf(0x445566), changedColors.get(1));
        assertEquals(IntTag.valueOf(3), changedColors.get(2));
        assertEquals(original.get("floats"), updated.get("floats"));
        assertEquals(original.get("extension"), updated.get("extension"));
        assertEquals(IntTag.valueOf(1), colors.get(0));
    }
    @Test void anUnexpectedModelPayloadIsNotSilentlyReplaced() {
        assertThrows(IllegalStateException.class, () -> TankRenderState.colorData(IntTag.valueOf(77), 0, 0));
    }
}
