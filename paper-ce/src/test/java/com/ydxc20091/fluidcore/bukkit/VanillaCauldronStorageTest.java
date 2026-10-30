package com.ydxc20091.fluidcore.bukkit;

import com.ydxc20091.fluidcore.api.*;
import com.ydxc20091.fluidcore.core.FluidTank;
import com.ydxc20091.fluidcore.core.FluidTransfers;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Levelled;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class VanillaCauldronStorageTest {
    private static final FluidVariant WATER = FluidVariant.of("minecraft:water");
    private static final FluidVariant LAVA = FluidVariant.of("minecraft:lava");

    @Test void transfersWholeBucketAtomicallyAndOnlyNotifiesPhysicsAfterRootCommit() {
        var fixture = new Fixture(); var source = new FluidTank(2000, fixture.owner);
        source.fill(FluidStack.of(WATER, 1000), FluidAction.EXECUTE);
        assertEquals(1000, FluidTransfers.move(source, fixture.storage, 1000, FluidAction.SIMULATE).amount());
        assertEquals(1000, source.amount()); assertEquals(Material.CAULDRON, fixture.state.getMaterial());
        assertEquals(0, fixture.physics);
        assertEquals(1000, FluidTransfers.move(source, fixture.storage, 1000, FluidAction.EXECUTE).amount());
        assertEquals(0, source.amount()); assertEquals(1000, fixture.storage.content(0).amount());
        assertEquals(3, ((Levelled) fixture.state).getLevel()); assertEquals(1, fixture.physics);
        assertTrue(fixture.writes.stream().noneMatch(Boolean::booleanValue));
        assertEquals(1000, fixture.storage.drain(WATER, 1000, FluidAction.EXECUTE).amount());
        assertEquals(Material.CAULDRON, fixture.state.getMaterial()); assertEquals(2, fixture.physics);
    }

    @Test void partialRequestsNeverRoundABottleOrInventACauldronLayer() {
        var fixture = new Fixture();
        assertEquals(0, fixture.storage.fill(FluidStack.of(WATER, 250), FluidAction.EXECUTE));
        assertEquals(0, fixture.storage.fill(FluidStack.of(WATER, 999), FluidAction.EXECUTE));
        assertEquals(0, fixture.physics); assertEquals(0, fixture.writes.size());
        fixture.state = data(Material.WATER_CAULDRON, 1);
        assertTrue(VanillaCauldronStorage.resolve(fixture.block, fixture.owner).isEmpty());
        assertThrows(StorageAccessException.class, () -> fixture.storage.content(0));
        fixture.state = data(Material.WATER_CAULDRON, 2);
        assertTrue(VanillaCauldronStorage.resolve(fixture.block, fixture.owner).isEmpty());
        assertEquals(0, fixture.physics);
    }

    @Test void nestedRollbackPreservesParentAndOuterRollbackDoesNotRunPhysics() {
        var fixture = new Fixture();
        try (var outer = FluidTransaction.open()) {
            assertEquals(1000, fixture.storage.insert(WATER, 1000, outer));
            try (var nested = outer.openNested()) { assertEquals(1000, fixture.storage.extract(WATER, 1000, nested)); }
            assertEquals(1000, fixture.storage.content(0).amount()); assertEquals(0, fixture.physics);
            outer.commit();
        }
        assertEquals(1, fixture.physics);
        try (var outer = FluidTransaction.open()) {
            assertEquals(1000, fixture.storage.extract(WATER, 1000, outer));
            try (var nested = outer.openNested()) { assertEquals(1000, fixture.storage.insert(LAVA, 1000, nested)); nested.commit(); }
        }
        assertEquals(FluidStack.of(WATER, 1000), fixture.storage.content(0)); assertEquals(1, fixture.physics);
    }

    @Test void worldConflictsAndTickChangesFailWithoutOverwritingForeignChanges() {
        var fixture = new Fixture();
        try (var transaction = FluidTransaction.open()) {
            fixture.storage.insert(WATER, 1000, transaction);
            fixture.state = data(Material.LAVA_CAULDRON, 0);
            assertThrows(StorageAccessException.class, transaction::commit);
        }
        assertEquals(Material.LAVA_CAULDRON, fixture.state.getMaterial()); assertEquals(0, fixture.physics);
        fixture.state = data(Material.CAULDRON, 0);
        try (var transaction = FluidTransaction.open()) {
            fixture.storage.insert(WATER, 1000, transaction); fixture.owner.tick++;
            assertThrows(StorageAccessException.class, transaction::commit);
        }
        assertEquals(Material.CAULDRON, fixture.state.getMaterial()); assertEquals(0, fixture.physics);
    }

    @Test void incompatibleFluidsAndComponentsCannotBeConvertedToVanillaWater() {
        var fixture = new Fixture();
        assertEquals(0, fixture.storage.fill(FluidStack.of(FluidVariant.of("minecraft:milk"), 1000), FluidAction.EXECUTE));
        var colored = WATER.withComponent(FluidKey.of("test:color"), ComponentValue.of(new byte[]{1}));
        assertEquals(0, fixture.storage.fill(FluidStack.of(colored, 1000), FluidAction.EXECUTE));
        assertEquals(1000, fixture.storage.fill(FluidStack.of(LAVA, 1000), FluidAction.EXECUTE));
        assertTrue(fixture.storage.drain(WATER, 1000, FluidAction.EXECUTE).isEmpty());
        assertEquals(0, fixture.storage.drain(LAVA, 250, FluidAction.EXECUTE).amount());
        assertEquals(FluidStack.of(LAVA, 1000), fixture.storage.content(0));
    }

    static final class Fixture {
        final ParticipantTestSupport.Owner owner = new ParticipantTestSupport.Owner();
        BlockData state = data(Material.CAULDRON, 0);
        final List<Boolean> writes = new ArrayList<>(); int physics;
        final Block block = (Block) Proxy.newProxyInstance(Block.class.getClassLoader(), new Class<?>[]{Block.class}, (proxy, method, args) -> switch (method.getName()) {
            case "getBlockData" -> state.clone();
            case "getType" -> state.getMaterial();
            case "setBlockData" -> { state = ((BlockData) args[0]).clone(); writes.add((boolean) args[1]); yield null; }
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            case "toString" -> "cauldron-fixture";
            default -> throw new UnsupportedOperationException(method.getName());
        });
        final VanillaCauldronStorage storage = new VanillaCauldronStorage(block, owner, material -> data(material, 0), () -> physics++);
    }

    static BlockData data(Material material, int initialLevel) {
        int[] level = {initialLevel};
        Class<?> type = material == Material.WATER_CAULDRON ? Levelled.class : BlockData.class;
        return (BlockData) Proxy.newProxyInstance(BlockData.class.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> switch (method.getName()) {
            case "getMaterial" -> material;
            case "getLevel" -> level[0];
            case "getMaximumLevel" -> 3;
            case "setLevel" -> { level[0] = (int) args[0]; yield null; }
            case "getAsString", "toString" -> material.getKey().asString() + (material == Material.WATER_CAULDRON ? "[level=" + level[0] + "]" : "");
            case "clone" -> data(material, level[0]);
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> throw new UnsupportedOperationException(method.getName());
        });
    }
}
