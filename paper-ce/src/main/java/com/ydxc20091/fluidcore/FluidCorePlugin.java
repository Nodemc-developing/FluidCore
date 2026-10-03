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
        saveResourceIfMissing("config.yml");
        saveResourceIfMissing("lang.yml");
        try {
            settings = FluidCoreSettings.load(getDataFolder().toPath().resolve("config.yml"));
        }
        catch (Exception failure) { throw new IllegalStateException("Invalid FluidCore configuration; file preserved", failure); }
        bridge = new CraftEngineBridge(this, registry); bridge.register();
        try { bridge.messages(com.ydxc20091.fluidcore.config.FluidMessages.load(getDataFolder().toPath().resolve("lang.yml"))); }
        catch (Exception failure) { throw new IllegalStateException("Invalid FluidCore language configuration; file preserved", failure); }
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS,
                event -> event.registrar().register("fluidcore", "FluidCore diagnostics", List.of("fc"), new AdminCommand()));
    }

    @Override public void onEnable() {
        queue = new SnapshotWorkQueue(settings.workers(), settings.queueCapacity());
        ui = new DiagnosticsUi(this);
        bridge.start();
        bridge.handoffs().inspectRecoveries(4096).thenAccept(entries -> {
            if (!entries.isEmpty()) getLogger().warning("Preserved " + entries.size() + " handoff journal records; use /fluidcore recovery to inspect uncertain transfers before any manual recovery.");
        }).exceptionally(failure -> { getLogger().log(Level.WARNING, "Cannot inspect preserved handoff journals", failure); return null; });
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
                    case "recovery" -> bridge.handoffs().inspectRecoveries(256).whenComplete((entries, failure) -> {
                        Runnable notify = () -> {
                            if (failure != null) { reply(sender, "无法检查交接记录：" + failure.getMessage()); return; }
                            reply(sender, "保留的交接记录：" + entries.size() + "（仅检查，未重新发放物品）");
                            for (var entry : entries) reply(sender, entry.id() + " | " + entry.state() + " | " + entry.source() + " -> " + entry.destination() + " | " + entry.itemBytes() + " bytes" + (entry.error().isEmpty() ? "" : " | " + entry.error()));
                        };
                        if (sender instanceof Player player) player.getScheduler().run(FluidCorePlugin.this, task -> notify.run(), () -> {});
                        else getServer().getGlobalRegionScheduler().execute(FluidCorePlugin.this, notify);
                    });
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
                    case "verify" -> verifyCore(sender);
                    case "verify-ce", "verify-persisted" -> com.ydxc20091.fluidcore.verification.CeVerification
                            .run(FluidCorePlugin.this, sub.equals("verify-persisted")).whenComplete((message, failure) -> {
                                if (failure != null) getLogger().log(Level.SEVERE, "FLUIDCORE_CE_VERIFY FAIL", failure);
                                else getLogger().info(message);
                            });
                    default -> reply(sender, "/fluidcore status|inspect|fluid|ui|recovery|reload|verify");
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
            return List.of("status", "inspect", "fluid", "ui", "recovery", "reload", "verify").stream().filter(value -> value.startsWith(prefix)).toList();
        }
    }

    private void reloadSettings(CommandSender sender) {
        var prior = queue;
        record Loaded(FluidCoreSettings settings, com.ydxc20091.fluidcore.config.FluidMessages messages) {}
        prior.submit(() -> new Loaded(FluidCoreSettings.load(getDataFolder().toPath().resolve("config.yml")),
                com.ydxc20091.fluidcore.config.FluidMessages.load(getDataFolder().toPath().resolve("lang.yml"))))
                .whenComplete((loaded, failure) -> {
                    Runnable publish = () -> {
                        if (!isEnabled()) return;
                        if (failure != null) { reply(sender, "配置重载失败，保留旧配置：" + failure.getMessage()); return; }
                        if (queue != prior) { reply(sender, "已有较新的配置重载，跳过本次结果。"); return; }
                        var replacement = new SnapshotWorkQueue(loaded.settings().workers(), loaded.settings().queueCapacity());
                        settings = loaded.settings(); bridge.messages(loaded.messages());
                        if (settings.handoffCapacity() != bridge.handoffs().capacity()) reply(sender, "handoff.capacity 将在下次插件启动时生效。");
                        queue = replacement;
                        prior.close();
                        reply(sender, "FluidCore 配置已重载；流体包请使用 CE 的重载命令。");
                    };
                    if (sender instanceof Player player) player.getScheduler().run(FluidCorePlugin.this, ignored -> publish.run(), () -> {});
                    else getServer().getGlobalRegionScheduler().execute(FluidCorePlugin.this, publish);
                });
    }

    private void verifyCore(CommandSender sender) {
        var context = StorageContext.confinedToCurrentThread();
        var source = new FluidTank(4000, context);
        var destination = new FluidTank(1500, context);
        var water = FluidVariant.of("minecraft:water");
        source.fill(FluidStack.of(water, 3000), FluidAction.EXECUTE);
        FluidTransfers.move(source, destination, water, 2000, FluidAction.SIMULATE);
        if (source.content(0).amount() != 3000 || !destination.content(0).isEmpty()) throw new IllegalStateException("simulation changed state");
        FluidTransfers.move(source, destination, water, 2000, FluidAction.EXECUTE);
        if (source.content(0).amount() != 1500 || destination.content(0).amount() != 1500) throw new IllegalStateException("transfer violated conservation");
        try (var transaction = FluidTransaction.open()) { source.extract(water, 1000, transaction); }
        if (source.content(0).amount() != 1500) throw new IllegalStateException("rollback failed");
        var codec = new FluidStackCodec(registry);
        var decoded = codec.decode(codec.encode(source.content(0)));
        if (!decoded.stack().equals(source.content(0))) throw new IllegalStateException("codec round trip failed");
        reply(sender, "FLUIDCORE_VERIFY PASS: simulation, conservation, rollback, codec, CE service registration");
    }
}
