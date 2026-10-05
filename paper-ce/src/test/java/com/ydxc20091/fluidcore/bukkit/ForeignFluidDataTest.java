/* Copyright 2026 ydxc20091. SPDX-License-Identifier: GPL-3.0-only */
package com.ydxc20091.fluidcore.bukkit;

import net.momirealms.craftengine.libraries.nbt.CompoundTag;
import net.momirealms.craftengine.libraries.nbt.IntTag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ForeignFluidDataTest {
    @Test void containerMarkersAreProtectedAcrossArbitraryNamespaces() {
        for (String key : List.of("jug", "jug_contents", "addon:jug", "addon:jug_color", "another:jug_contents",
                "another:tank_data", "libuid:data", "jug_color:data", "addon:opaque_fluid", "addon:liquid", "ADDON:JUG")) {
            assertTrue(ForeignFluidData.key(key), key);
        }
        for (String key : List.of("addon:custom_name", "addon:category", "addon:jugular", "custom:inventory")) {
            assertFalse(ForeignFluidData.key(key), key);
        }
    }

    @Test void ownedKeysAreHandledByTheirOwnCodecInsteadOfBeingClassifiedAsForeign() {
        for (String key : List.of("fluidcore", "fluidcore:container_data", "fluidcore:jug", "fluidcore:jug_color",
                "fluidcore:tank_data", "FLUIDCORE:JUG")) {
            assertFalse(ForeignFluidData.key(key), key);
        }
    }

    @Test void opaqueCompoundContainerMarkersAreRecognizedWithoutChangingTheirPayload() {
        CompoundTag nested = new CompoundTag();
        nested.put("unfamiliar:jug", IntTag.valueOf(17));
        CompoundTag root = new CompoundTag();
        root.put("unrelated:metadata", nested);
        assertTrue(ForeignFluidData.compound(root));
        assertEquals(IntTag.valueOf(17), nested.get("unfamiliar:jug"));
        assertSame(nested, root.get("unrelated:metadata"));
        assertEquals(1, root.size());
        assertEquals(1, nested.size());
    }

    @Test void ownedCompoundPayloadsDoNotTriggerForeignMatchingThroughTheirInternalFields() {
        for (String owned : List.of("fluidcore", "fluidcore:tank_data", "FLUIDCORE:CONTAINER_DATA")) {
            CompoundTag payload = new CompoundTag();
            payload.put("fluid", IntTag.valueOf(7));
            payload.put("jug_contents", IntTag.valueOf(9));
            CompoundTag root = new CompoundTag();
            root.put(owned, payload);
            assertFalse(ForeignFluidData.compound(root), owned);
            assertEquals(IntTag.valueOf(7), payload.get("fluid"));
            assertEquals(IntTag.valueOf(9), payload.get("jug_contents"));
        }
    }
}
