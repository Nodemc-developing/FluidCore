/* Copyright 2026 ydxc20091. SPDX-License-Identifier: GPL-3.0-only */
package com.ydxc20091.fluidcore.bukkit;

import com.ydxc20091.fluidcore.api.FluidAction;
import com.ydxc20091.fluidcore.api.FluidVariant;
import com.ydxc20091.fluidcore.core.FluidRegistry;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionType;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class VanillaWaterBottleCompatibilityTest {
    private final ItemContainerTransfers transfers = new ItemContainerTransfers(new FluidRegistry(), item -> null);

    @Test void emptyEffectListRemainsOrdinaryWaterWhenLegacyPresenceFlagIsTrue() {
        var original = new PotionStack(PotionType.WATER, true, List.of());
        var handler = transfers.resolve(original).orElseThrow();
        assertEquals(250, handler.content().amount());
        assertEquals(FluidVariant.of("minecraft:water"), handler.content().variant());
        assertEquals(250, handler.drain(250, FluidAction.SIMULATE).amount());
        assertEquals(250, handler.content().amount());
        assertEquals(250, handler.drain(250, FluidAction.EXECUTE).amount());
        assertTrue(handler.content().isEmpty());
        assertEquals(Material.GLASS_BOTTLE, handler.item().getType());
        assertEquals(Material.POTION, original.getType());
        assertEquals(2, original.getAmount());
    }

    @Test void actualNonemptyEffectListIsNeverDrainedAsPlainWater() {
        // Only the list's presence matters to classification; no effect contents are interpreted.
        for (boolean presenceFlag : new boolean[]{false, true}) {
            var original = new PotionStack(PotionType.WATER, presenceFlag, Collections.singletonList(null));
            assertTrue(transfers.resolve(original).isEmpty());
            assertEquals(Material.POTION, original.getType());
            assertEquals(2, original.getAmount());
        }
    }

    @Test void effectFreeNonWaterPotionDoesNotBecomeWater() {
        assertTrue(transfers.resolve(new PotionStack(PotionType.MUNDANE, false, List.of())).isEmpty());
    }

    private static final class PotionStack extends ItemStack {
        private final ItemContainerTransfersTest.DataStack delegate;
        private final PotionType base;
        private final boolean presenceFlag;
        private final List<?> effects;

        PotionStack(PotionType base, boolean presenceFlag, List<?> effects) {
            this(new ItemContainerTransfersTest.DataStack(Material.POTION, 2), base, presenceFlag, effects);
        }
        private PotionStack(ItemContainerTransfersTest.DataStack delegate, PotionType base, boolean presenceFlag, List<?> effects) {
            super(); this.delegate = delegate; this.base = base; this.presenceFlag = presenceFlag; this.effects = effects;
        }
        @Override public Material getType() { return delegate.getType(); }
        @Override public void setType(Material material) { delegate.setType(material); }
        @Override public int getAmount() { return delegate.getAmount(); }
        @Override public void setAmount(int amount) { delegate.setAmount(amount); }
        @Override public boolean hasItemMeta() { return true; }
        @Override public io.papermc.paper.persistence.PersistentDataContainerView getPersistentDataContainer() {
            return delegate.getPersistentDataContainer();
        }
        @Override public ItemMeta getItemMeta() {
            ItemMeta shared = delegate.getItemMeta();
            if (getType() != Material.POTION) return shared;
            return (PotionMeta) Proxy.newProxyInstance(PotionMeta.class.getClassLoader(), new Class<?>[]{PotionMeta.class},
                    (proxy, method, arguments) -> switch (method.getName()) {
                        case "getBasePotionType" -> base;
                        case "hasCustomEffects" -> presenceFlag;
                        case "getCustomEffects" -> effects;
                        default -> method.invoke(shared, arguments);
                    });
        }
        @Override public PotionStack clone() { return new PotionStack(delegate.clone(), base, presenceFlag, effects); }
    }
}
