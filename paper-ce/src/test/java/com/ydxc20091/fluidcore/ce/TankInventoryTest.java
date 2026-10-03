package com.ydxc20091.fluidcore.ce;

import com.ydxc20091.fluidcore.api.*;
import com.ydxc20091.fluidcore.core.FluidTank;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class TankInventoryTest {
    private final StorageContext owner = StorageContext.confinedToCurrentThread();
    private final AtomicInteger notifications = new AtomicInteger();
    private final TankInventory slots = new TankInventory(owner, notifications::incrementAndGet);
    @Test void previewAndRollbackNeverCreateItemsOrPublishNotifications() {
        try (var transaction = FluidTransaction.open()) { assertTrue(slots.insertInput(new Stack(Material.STONE, 2), transaction)); }
        assertNull(slots.input()); assertNull(slots.output()); assertEquals(0,notifications.get());
    }
    @Test void recipeCommitsItemAndFluidTogetherExactlyOnce() {
        slots.load(new Stack(Material.STONE, 2), null);
        var tank = new FluidTank(4000,owner); var water = FluidVariant.of("minecraft:water"); tank.restore(FluidStack.of(water,2000));
        try (var transaction = FluidTransaction.open()) {
            assertEquals(1000,tank.extract(water,1000,transaction));
            assertTrue(slots.completeOne(slots.input(),new Stack(Material.WATER_BUCKET,1),transaction)); transaction.commit();
        }
        assertEquals(1,slots.input().getAmount()); assertEquals(1,slots.output().getAmount()); assertEquals(1000,tank.content(0).amount()); assertEquals(1,notifications.get());
    }
    @Test void blockedOutputRestoresFluidAndRetainsInput() {
        slots.load(new Stack(Material.STONE, 2), new Stack(Material.WATER_BUCKET,1));
        var tank = new FluidTank(4000,owner); var water = FluidVariant.of("minecraft:water"); tank.restore(FluidStack.of(water,2000));
        try (var transaction = FluidTransaction.open()) {
            tank.extract(water,1000,transaction); assertFalse(slots.completeOne(slots.input(),new Stack(Material.WATER_BUCKET,1),transaction));
        }
        assertEquals(2,slots.input().getAmount()); assertEquals(1,slots.output().getAmount()); assertEquals(2000,tank.content(0).amount());
    }
    @Test void originalStackIsComparedAndCompensationCanRestoreTheOriginalOutputSlot() {
        slots.load(new Stack(Material.STONE,2),new Stack(Material.WATER_BUCKET,1));
        try (var transaction = FluidTransaction.open()) { assertFalse(slots.completeOne(new Stack(Material.STONE,1),new Stack(Material.STONE,1),transaction)); }
        try (var transaction = FluidTransaction.open()) { assertNotNull(slots.extractOutput(1,transaction)); transaction.commit(); }
        try (var transaction = FluidTransaction.open()) { assertTrue(slots.insertOutput(new Stack(Material.WATER_BUCKET,1),transaction)); transaction.commit(); }
        assertEquals(2,slots.input().getAmount()); assertEquals(Material.WATER_BUCKET,slots.output().getType());
    }
    @Test void inputSwapRequiresTheFullObservedStackAndRollsBackBothSlots() {
        slots.load(new Stack(Material.STONE, 5), new Stack(Material.WATER_BUCKET, 1));
        try (var transaction = FluidTransaction.open()) {
            assertFalse(slots.replaceInput(new Stack(Material.STONE, 4), new Stack(Material.WATER_BUCKET, 1), transaction));
            assertTrue(slots.replaceInput(slots.input(), new Stack(Material.WATER_BUCKET, 1), transaction));
            assertTrue(slots.replaceOutput(slots.output(), null, transaction));
        }
        assertEquals(new Stack(Material.STONE, 5), slots.input());
        assertEquals(new Stack(Material.WATER_BUCKET, 1), slots.output());
        assertEquals(0, notifications.get());
    }
    @Test void oversizedSwapIsRejectedWithoutReplacingOrPublishing() {
        slots.load(new Stack(Material.STONE, 5), null);
        try (var transaction = FluidTransaction.open()) {
            assertFalse(slots.replaceInput(slots.input(), new Stack(Material.WATER_BUCKET, 2), transaction));
            transaction.commit();
        }
        assertEquals(new Stack(Material.STONE, 5), slots.input()); assertEquals(0, notifications.get());
    }
    private static final class Stack extends ItemStack {
        private Material material; private int amount;
        Stack(Material material,int amount) { super(); this.material=material; this.amount=amount; }
        @Override public Material getType() { return material; }
        @Override public int getAmount() { return amount; }
        @Override public void setAmount(int value) { amount=value; }
        @Override public int getMaxStackSize() { return material==Material.STONE?64:1; }
        @Override public Stack clone() { return new Stack(material,amount); }
        @Override public boolean isSimilar(ItemStack item) { return item instanceof Stack other && material==other.material; }
        @Override public boolean equals(Object value) { return value instanceof Stack other && material==other.material&&amount==other.amount; }
        @Override public int hashCode() { return material.hashCode()*31+amount; }
    }
}
