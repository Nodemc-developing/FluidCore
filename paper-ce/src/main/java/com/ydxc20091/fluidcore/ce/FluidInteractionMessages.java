/* Copyright 2026 ydxc20091. SPDX-License-Identifier: GPL-3.0-only */
package com.ydxc20091.fluidcore.ce;

import com.ydxc20091.fluidcore.api.FluidStorage;
import com.ydxc20091.fluidcore.bukkit.ItemContainerTransfers;
import com.ydxc20091.fluidcore.core.FluidRegistry;

/** Feedback is computed only for a click, from storage owned by the current execution context. */
final class FluidInteractionMessages {
    private FluidInteractionMessages() {}

    static String failure(ItemContainerTransfers.Result result, String locale) {
        return failure(result, locale, com.ydxc20091.fluidcore.config.FluidMessages.defaults());
    }
    static String failure(ItemContainerTransfers.Result result, String locale, com.ydxc20091.fluidcore.config.FluidMessages messages) {
        if (result == null) return null;
        return switch (result) {
            case SUCCESS, NOT_A_CONTAINER -> null;
            case NO_TRANSFER -> messages.text(locale, "no-transfer");
            case NO_INVENTORY_SPACE -> messages.text(locale, "no-space");
            case PROTECTED_DATA -> messages.text(locale, "protected-transfer");
            case ATOMIC_TRANSFER_UNSUPPORTED -> messages.text(locale, "atomic-unsupported");
        };
    }

    static String accessRejected(String locale) {
        return chinese(locale) ? "流体数据受保护或容器暂不可用，操作已取消。"
                : "Fluid data is protected or the container is unavailable; the operation was cancelled.";
    }

    static String transferFailed(String locale) {
        return chinese(locale) ? "流体转移未能完成，请查看服务器日志。"
                : "The fluid transfer could not complete; check the server log.";
    }

    static String storage(FluidStorage storage, FluidRegistry registry, String locale) {
        return storage(storage, registry, locale, com.ydxc20091.fluidcore.config.FluidMessages.defaults());
    }
    static String storage(FluidStorage storage, FluidRegistry registry, String locale, com.ydxc20091.fluidcore.config.FluidMessages messages) {
        StringBuilder result = new StringBuilder(messages.text(locale, "tank") + ": ");
        int tanks = storage.tanks();
        for (int index = 0; index < tanks; index++) {
            if (index > 0) result.append(" · ");
            if (tanks > 1) result.append('#').append(index + 1).append(' ');
            var content = storage.content(index);
            String name = content.isEmpty() ? messages.text(locale, "empty") : messages.fluidName(registry, content.variant().fluid(), locale);
            result.append(name).append(' ').append(content.amount()).append(" / ")
                    .append(storage.capacity(index)).append(" mB");
        }
        return result.toString();
    }

    private static boolean chinese(String locale) {
        return locale != null && locale.toLowerCase(java.util.Locale.ROOT).startsWith("zh");
    }
}
