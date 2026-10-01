/* Copyright 2026 ydxc20091. SPDX-License-Identifier: GPL-3.0-only */
package com.ydxc20091.fluidcore.ui;

import com.ydxc20091.fluidcore.FluidCorePlugin;
import com.ydxc20091.fluidcore.api.FluidStack;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.momirealms.sparrow.ui.SparrowUI;
import net.momirealms.sparrow.ui.item.StaticItem;
import net.momirealms.sparrow.ui.pane.NormalPane;
import net.momirealms.sparrow.ui.pane.PaneSize;
import net.momirealms.sparrow.ui.window.NormalWindow;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import java.util.ArrayList;
import java.util.List;

/** One-shot immutable diagnostic snapshot; no polling or remote-region storage reads. */
public final class DiagnosticsUi {
    private final FluidCorePlugin plugin;
    private boolean initialized;

    public DiagnosticsUi(FluidCorePlugin plugin) { this.plugin = plugin; }

    public boolean supported() {
        String version = Bukkit.getMinecraftVersion();
        if (!version.startsWith("1.21")) return true;
        String[] parts = version.split("\\.");
        return parts.length > 2 && Integer.parseInt(parts[2]) >= 4;
    }

    public void open(Player player) {
        if (!plugin.settings().diagnosticUi() || !supported()) {
            player.sendMessage(Component.text("此版本或配置未启用诊断菜单，请使用 /fluidcore inspect。", NamedTextColor.YELLOW));
            return;
        }
        if (!initialized) {
            synchronized (this) {
                if (!initialized) { SparrowUI.getInstance().setUp(plugin); initialized = true; }
            }
        }
        var snapshot = plugin.registry().snapshot();
        var pane = NormalPane.empty(PaneSize.of(9, 3));
        pane.setItem(10, display(Material.WATER_BUCKET, "流体注册表", List.of(
                "已注册：" + snapshot.fluids().size(), "注册代次：" + snapshot.generation(), "作者：ydxc20091")));
        pane.setItem(12, display(Material.CLOCK, "后台任务", List.of(
                "执行中：" + plugin.workQueue().active(), "等待：" + plugin.workQueue().waiting(),
                "队列拒绝：" + plugin.workQueue().rejected())));
        var target = player.getTargetBlockExact(5);
        List<String> contents = new ArrayList<>();
        if (target != null) plugin.bridge().resolver().controller(target.getLocation()).ifPresent(controller -> {
            if (controller.hasProtectedData()) contents.add("数据受保护：检查流体注册与配置，原始数据已保留");
        });
        if (target != null) plugin.storageAt(target.getLocation()).ifPresent(storage -> {
            for (int index = 0; index < storage.tanks(); index++) {
                FluidStack stack = storage.content(index);
                contents.add("#" + index + " " + (stack.isEmpty() ? "空" : stack.variant().fluid()));
                contents.add(stack.amount() + " / " + storage.capacity(index) + " mB");
                if (!stack.isEmpty()) snapshot.find(stack.variant().fluid()).ifPresent(definition -> {
                    contents.add(definition.displayName() + " · " + definition.rarity());
                    contents.add("温度 " + definition.temperature() + " K · 密度 " + definition.density() + " kg/m³");
                    contents.add("黏度 " + definition.viscosity() + " mPa·s · 亮度 " + definition.lightLevel());
                });
            }
        });
        if (contents.isEmpty()) contents.add("对准五格内的流体储罐后重新打开");
        pane.setItem(14, display(Material.GLASS_BOTTLE, "目标储罐", contents));
        pane.setItem(16, display(Material.BOOK, "事务范围", List.of(
                "同一所属执行上下文", "跨 Folia 区域转移拒绝", "界面为打开时的数据快照")));
        NormalWindow.builder().setViewer(player).setTitle("FluidCore 检查")
                .setUpperPane(pane).build().open().exceptionally(failure -> {
                    plugin.getLogger().warning("Cannot open FluidCore diagnostic window: " + failure.getMessage());
                    return null;
                });
    }

    private StaticItem display(Material material, String title, List<String> lines) {
        ItemStack item = new ItemStack(material);
        item.editMeta(meta -> {
            meta.displayName(Component.text(title, NamedTextColor.AQUA));
            meta.lore(lines.stream().map(line -> Component.text(line, NamedTextColor.GRAY)).toList());
        });
        return new StaticItem(item);
    }
}
