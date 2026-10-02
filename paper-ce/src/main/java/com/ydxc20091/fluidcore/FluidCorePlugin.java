/* Copyright 2026 ydxc20091. SPDX-License-Identifier: GPL-3.0-only */
package com.ydxc20091.fluidcore;

import com.ydxc20091.fluidcore.api.*;
import com.ydxc20091.fluidcore.async.SnapshotWorkQueue;
import com.ydxc20091.fluidcore.ce.CraftEngineBridge;
import com.ydxc20091.fluidcore.config.FluidCoreSettings;
import com.ydxc20091.fluidcore.core.*;
import com.ydxc20091.fluidcore.ui.DiagnosticsUi;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.kyori.adventure.text.Component;
import org.bstats.bukkit.Metrics;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import java.util.*;
import java.util.logging.Level;

public final class FluidCorePlugin extends JavaPlugin implements BukkitFluidCoreService {
    private FluidRegistry registry;
    private CraftEngineBridge bridge;
    private volatile FluidCoreSettings settings;
    private volatile SnapshotWorkQueue queue;
    private DiagnosticsUi ui;
    private Metrics metrics;

    @Override public void onLoad() {
        registry = new FluidRegistry();
        bridge = new CraftEngineBridge(this, registry);
        bridge.register();
        saveResourceIfMissing("config.yml");
        try { settings = FluidCoreSettings.load(getDataFolder().toPath().resolve("config.yml")); }
        catch (Exception failure) { throw new IllegalStateException("Invalid FluidCore configuration; file preserved", failure); }
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS,
                event -> event.registrar().register("fluidcore", "FluidCore diagnostics", List.of("fc"), new AdminCommand()));
    }

    @Override public void onEnable() {
        queue = new SnapshotWorkQueue(settings.workers(), settings.queueCapacity());
        ui = new DiagnosticsUi(this);
        bridge.start();
        var services = getServer().getServicesManager();
        services.register(BukkitFluidCoreService.class, this, this, ServicePriority.Normal);
        services.register(CraftEngineBridge.class, bridge, this, ServicePriority.Normal);
        services.register(FluidRegistry.class, registry, this, ServicePriority.Normal);
        metrics = new Metrics(this, 34449);
        getLogger().info("FluidCore " + getPluginMeta().getVersion() + " by ydxc20091 enabled; CE 26.10 bridge registered.");
    }

    @Override public void onDisable() {
        if (metrics != null) {
            metrics.shutdown();
            metrics = null;
        }
        getServer().getServicesManager().unregisterAll(this);
        if (bridge != null) bridge.close();
        if (queue != null) queue.close();
    }

    private void saveResourceIfMissing(String resource) {
        if (!getDataFolder().toPath().resolve(resource).toFile().exists()) saveResource(resource, false);
    }

    @Override public FluidRegistry registry() { return registry; }
    @Override public CraftEngineBridge bridge() { return bridge; }
    @Override public SnapshotWorkQueue workQueue() { return queue; }
    @Override public Optional<FluidStorage> storageAt(Location location) { return bridge.resolver().resolve(location); }
    public FluidCoreSettings settings() { return settings; }

    private void reply(CommandSender sender, String text) { sender.sendMessage(Component.text(text)); }

    private final class AdminCommand implements BasicCommand {
        @Override public String permission() { return "fluidcore.admin"; }
        @Override public void execute(CommandSourceStack source, String[] args) {
            CommandSender sender = source.getSender();
            String sub = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
            try {
                switch (sub) {
                    case "status" -> reply(sender, "FluidCore " + getPluginMeta().getVersion() + " | ydxc20091 | fluids="
                            + registry.snapshot().fluids().size() + " | queued=" + queue.waiting() + " | rejected=" + queue.rejected());
                    case "inspect" -> {
                        if (!(sender instanceof Player player)) { reply(sender, "此命令需要玩家对准储罐。"); return; }
                        var target = player.getTargetBlockExact(5);
                        var storage = target == null ? Optional.<FluidStorage>empty() : storageAt(target.getLocation());
                        if (storage.isEmpty()) { reply(sender, "目标没有注册流体存储。"); return; }
                        bridge.resolver().controller(target.getLocation()).ifPresent(controller -> {
                            if (controller.hasProtectedData()) reply(sender, "数据受保护：检查流体注册与配置，原始数据已保留。");
                        });
                        for (int index = 0; index < storage.get().tanks(); index++) {
                            var content = storage.get().content(index);
                            reply(sender, "#" + index + " " + (content.isEmpty() ? "empty" : content.variant().fluid())
                                    + " " + content.amount() + "/" + storage.get().capacity(index) + " mB");
                        }
                    }
                    case "ui" -> {
                        if (sender instanceof Player player) ui.open(player); else reply(sender, "菜单需要玩家使用。");
                    }
                    case "fluid" -> {
                        if (args.length != 2) { reply(sender, "/fluidcore fluid namespace:id"); return; }
                        var definition = registry.find(FluidKey.of(args[1]));
                        if (definition.isEmpty()) { reply(sender, "未注册该流体。"); return; }
                        var fluid = definition.get();
                        reply(sender, fluid.key() + " | " + fluid.displayName() + " | " + fluid.rarity());
                        reply(sender, "温度 " + fluid.temperature() + " K | 密度 " + fluid.density()
                                + " kg/m³ | 黏度 " + fluid.viscosity() + " mPa·s | 亮度 " + fluid.lightLevel());
                        reply(sender, "颜色 " + (fluid.color().isPresent() ? String.format("#%08X", fluid.color().getAsInt()) : "未设置")
                                + " | 纹理 " + fluid.texture().orElse("未设置") + " | 声音 " + fluid.properties().sounds());
                    }
                    case "reload" -> reloadSettings(sender);
                    default -> reply(sender, "/fluidcore status|inspect|fluid|ui|reload");
                }
            } catch (RuntimeException failure) {
                getLogger().log(Level.WARNING, "FluidCore command failed", failure);
                reply(sender, "操作失败：" + failure.getMessage());
            }
        }
        @Override public Collection<String> suggest(CommandSourceStack source, String[] args) {
            String prefix = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            if (args.length == 2 && args[0].equalsIgnoreCase("fluid"))
                return registry.snapshot().fluids().keySet().stream().map(FluidKey::toString)
                        .filter(key -> key.startsWith(args[1])).sorted().toList();
            return List.of("status", "inspect", "fluid", "ui", "reload").stream().filter(value -> value.startsWith(prefix)).toList();
        }
    }

    private void reloadSettings(CommandSender sender) {
        var prior = queue;
        prior.submit(() -> FluidCoreSettings.load(getDataFolder().toPath().resolve("config.yml")))
                .whenComplete((loaded, failure) -> {
                    Runnable publish = () -> {
                        if (!isEnabled()) return;
                        if (failure != null) { reply(sender, "配置重载失败，保留旧配置：" + failure.getMessage()); return; }
                        if (queue != prior) { reply(sender, "已有较新的配置重载，跳过本次结果。"); return; }
                        var replacement = new SnapshotWorkQueue(loaded.workers(), loaded.queueCapacity());
                        settings = loaded;
                        queue = replacement;
                        prior.close();
                        reply(sender, "FluidCore 配置已重载；流体包请使用 CE 的重载命令。");
                    };
                    if (sender instanceof Player player) player.getScheduler().run(FluidCorePlugin.this, ignored -> publish.run(), () -> {});
                    else getServer().getGlobalRegionScheduler().execute(FluidCorePlugin.this, publish);
                });
    }

}
