/* Copyright 2026 ydxc20091. SPDX-License-Identifier: GPL-3.0-only */
package com.ydxc20091.fluidcore.ce;

import com.ydxc20091.fluidcore.api.FluidStack;
import com.ydxc20091.fluidcore.api.FluidVariant;
import com.ydxc20091.fluidcore.api.StorageContext;
import com.ydxc20091.fluidcore.bukkit.ItemContainerTransfers;
import com.ydxc20091.fluidcore.core.FluidDefinition;
import com.ydxc20091.fluidcore.core.FluidRegistry;
import com.ydxc20091.fluidcore.core.FluidTank;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FluidInteractionMessagesTest {
    @Test void emptyAndPartiallyFilledTanksReportActualAmountAndCapacityInThePlayersLanguage() {
        var registry = new FluidRegistry();
        var tank = new FluidTank(16000, StorageContext.confinedToCurrentThread());
        assertEquals("流体储罐: 空 0 / 16000 mB", FluidInteractionMessages.storage(tank, registry, "zh_cn"));
        tank.restore(FluidStack.of(FluidVariant.of("minecraft:water"), 375));
        assertEquals("流体储罐: 水 375 / 16000 mB", FluidInteractionMessages.storage(tank, registry, "ZH_TW"));
        assertEquals("Fluid tank: Water 375 / 16000 mB", FluidInteractionMessages.storage(tank, registry, "en_us"));
    }

    @Test void configuredDisplayNamesAndLongAmountsArePreserved() {
        var registry = new FluidRegistry();
        registry.register("test", FluidDefinition.builder("example:syrup").displayName("树莓糖浆").build());
        var tank = new FluidTank(Long.MAX_VALUE, StorageContext.confinedToCurrentThread());
        tank.restore(FluidStack.of(FluidVariant.of("example:syrup"), Long.MAX_VALUE));
        assertTrue(FluidInteractionMessages.storage(tank, registry, "zh_hk")
                .contains("树莓糖浆 9223372036854775807 / 9223372036854775807 mB"));
    }

    @Test void everyRejectedNativeTransferHasFeedbackAndASuccessIsNotReportedAsFailure() {
        for (var result : ItemContainerTransfers.Result.values()) {
            if (result == ItemContainerTransfers.Result.SUCCESS || result == ItemContainerTransfers.Result.NOT_A_CONTAINER) continue;
            assertNotNull(FluidInteractionMessages.failure(result, "zh_cn"), result.name());
            assertNotNull(FluidInteractionMessages.failure(result, "en_us"), result.name());
        }
        assertNull(FluidInteractionMessages.failure(ItemContainerTransfers.Result.SUCCESS, "zh_cn"));
        assertTrue(FluidInteractionMessages.failure(ItemContainerTransfers.Result.NO_INVENTORY_SPACE, "zh_cn").contains("均已保留"));
    }
}
