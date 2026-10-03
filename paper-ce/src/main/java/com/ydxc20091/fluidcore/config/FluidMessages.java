/* Copyright 2026 ydxc20091. SPDX-License-Identifier: GPL-3.0-only */
package com.ydxc20091.fluidcore.config;

import com.ydxc20091.fluidcore.api.FluidKey;
import com.ydxc20091.fluidcore.core.FluidRegistry;
import net.momirealms.sparrow.yaml.SparrowYaml;
import net.momirealms.sparrow.yaml.route.Route;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Immutable configurable messages, loaded once and published together on settings reload. */
public record FluidMessages(String defaultLanguage, Map<String, String> chinese, Map<String, String> english) {
    private static final Map<String, String> ZH = Map.ofEntries(
            Map.entry("tank", "流体储罐"), Map.entry("empty", "空"), Map.entry("contains", "包含：{fluid}"),
            Map.entry("amount", "{amount} / {capacity} mB"), Map.entry("units", "1 桶 = 1000 mB = 4 瓶"),
            Map.entry("input", "输入"), Map.entry("output", "输出"), Map.entry("close", "关闭"),
            Map.entry("recipes", "流体配方"), Map.entry("recipes-hint", "查看灌装、排空与浸泡配方"),
            Map.entry("insert", "光标左键放入整叠，右键放入一个；Shift 背包快捷投料"), Map.entry("take-input", "空光标左键取整叠，右键取一半；Shift 取回背包"),
            Map.entry("take-output", "空光标点击领取整叠产物"), Map.entry("progress", "进度：{percent}%"),
            Map.entry("bucket-count", "当前储量：{count} 桶"), Map.entry("bottle-count", "当前储量：{count} 瓶"),
            Map.entry("protected", "数据受保护"), Map.entry("no-transfer", "无法转移：流体不足、容量不足或类型不匹配。"),
            Map.entry("no-space", "背包没有空间，物品和流体均已保留。"),
            Map.entry("protected-transfer", "容器数据受保护，物品和流体均已保留。"),
            Map.entry("atomic-unsupported", "此容器不支持完整转移，操作已取消。"),
            Map.entry("access-rejected", "流体数据受保护或容器暂不可用，操作已取消。"),
            Map.entry("transfer-failed", "流体转移未能完成，请查看服务器日志。"),
            Map.entry("water", "水"), Map.entry("milk", "牛奶"), Map.entry("lava", "岩浆"), Map.entry("honey", "蜂蜜"));
    private static final Map<String, String> EN = Map.ofEntries(
            Map.entry("tank", "Fluid tank"), Map.entry("empty", "Empty"), Map.entry("contains", "Contains: {fluid}"),
            Map.entry("amount", "{amount} / {capacity} mB"), Map.entry("units", "1 bucket = 1000 mB = 4 bottles"),
            Map.entry("input", "Input"), Map.entry("output", "Output"), Map.entry("close", "Close"),
            Map.entry("recipes", "Fluid recipes"), Map.entry("recipes-hint", "Browse filling, emptying and soaking recipes"),
            Map.entry("insert", "Left-click to place a stack, right-click for one; shift-click inventory to insert"), Map.entry("take-input", "With an empty cursor: left-click for a stack, right-click for half; shift-click to inventory"),
            Map.entry("take-output", "Click with an empty cursor to collect the result stack"), Map.entry("progress", "Progress: {percent}%"),
            Map.entry("bucket-count", "Stored: {count} buckets"), Map.entry("bottle-count", "Stored: {count} bottles"),
            Map.entry("protected", "Protected data"), Map.entry("no-transfer", "Cannot transfer: insufficient fluid, capacity, or incompatible type."),
            Map.entry("no-space", "No inventory space; the item and fluid were preserved."),
            Map.entry("protected-transfer", "Container data is protected; the item and fluid were preserved."),
            Map.entry("atomic-unsupported", "This container cannot transfer atomically; the operation was cancelled."),
            Map.entry("access-rejected", "Fluid data is protected or the container is unavailable; the operation was cancelled."),
            Map.entry("transfer-failed", "The fluid transfer could not complete; check the server log."),
            Map.entry("water", "Water"), Map.entry("milk", "Milk"), Map.entry("lava", "Lava"), Map.entry("honey", "Honey"));
    public FluidMessages { chinese = Map.copyOf(chinese); english = Map.copyOf(english); }
    public static FluidMessages defaults() { return new FluidMessages("zh", ZH, EN); }
    public static FluidMessages load(Path file) throws IOException {
        var yaml = SparrowYaml.builder().setAllowDuplicateKeys(false).build().load(file);
        String language = yaml.getStringOrDefault("zh", Route.from("default-language"));
        if (!language.equals("zh") && !language.equals("en")) throw new IllegalArgumentException("default-language must be zh or en");
        Map<String, String> zh = new LinkedHashMap<>(), en = new LinkedHashMap<>();
        for (String key : ZH.keySet()) {
            String cn = yaml.getStringOrDefault(ZH.get(key), Route.from("zh", key));
            String eng = yaml.getStringOrDefault(EN.get(key), Route.from("en", key));
            if (cn.length() > 4096 || eng.length() > 4096) throw new IllegalArgumentException("Message is too long: " + key);
            zh.put(key, cn); en.put(key, eng);
        }
        return new FluidMessages(language, zh, en);
    }
    public String text(String locale, String key, Object... replacements) {
        boolean zh = (locale == null ? defaultLanguage : locale).toLowerCase(Locale.ROOT).startsWith("zh");
        String text = (zh ? chinese : english).getOrDefault(key, key);
        if (replacements.length % 2 != 0) throw new IllegalArgumentException("Message placeholders require pairs");
        for (int index = 0; index < replacements.length; index += 2)
            text = text.replace("{" + replacements[index] + "}", String.valueOf(replacements[index + 1]));
        return text;
    }
    public String fluidName(FluidRegistry registry, FluidKey key, String locale) {
        return registry.find(key).map(fluid -> {
            String name = fluid.displayName();
            String shortName = switch (key.toString()) {
                case "minecraft:water" -> "water"; case "minecraft:milk" -> "milk";
                case "minecraft:lava" -> "lava"; case "minecraft:honey" -> "honey"; default -> null;
            };
            if (shortName != null && name.equalsIgnoreCase(shortName)) return text(locale, shortName);
            return name;
        }).orElse(key.toString());
    }
}
