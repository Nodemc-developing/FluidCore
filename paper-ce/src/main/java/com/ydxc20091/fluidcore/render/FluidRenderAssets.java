/* Copyright 2026 ydxc20091. SPDX-License-Identifier: GPL-3.0-only */
package com.ydxc20091.fluidcore.render;

import com.ydxc20091.fluidcore.api.FluidKey;
import com.ydxc20091.fluidcore.core.FluidDefinition;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Pure resource compiler. Only its own fluidcore namespace is written, with atomic file replacement. */
final class FluidRenderAssets {
    private static final int MAX_FILES = 32768;
    private static final int MAX_FLUIDS = 128;
    private FluidRenderAssets() {}
    static String prefix(FluidKey style) { return "render/styles/" + digest(style.toString()); }
    static String fluidPath(FluidKey fluid) { return digest(fluid.toString()); }
    static String model(FluidKey style) { return "fluidcore:" + prefix(style) + "/gui"; }
    static String world(FluidKey style, FluidKey fluid, int level) {
        return "fluidcore:" + prefix(style) + "/world/" + (fluid == null ? "fallback" : fluidPath(fluid)) + "/" + level;
    }
    private static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    /** Returns the total compiled file count. Empty styles deliberately do not create a pack directory. */
    static int compile(Path root, Collection<FluidRenderStyle> styles, Collection<FluidDefinition> definitions) throws IOException {
        if (styles.isEmpty()) return 0;
        if (definitions.size() > MAX_FLUIDS) throw new IllegalArgumentException("Rendering supports at most " + MAX_FLUIDS + " registered fluids");
        List<FluidDefinition> fluids = definitions.stream().sorted(Comparator.comparing(FluidDefinition::key)).toList();
        Map<String, byte[]> files = new LinkedHashMap<>();
        BufferedImage solid = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) solid.setRGB(x, y, 0xffffffff);
        ByteArrayOutputStream png = new ByteArrayOutputStream(); ImageIO.write(solid, "PNG", png);
        files.put("assets/fluidcore/textures/render/solid.png", png.toByteArray());
        for (FluidRenderStyle style : styles) {
            String prefix = prefix(style.id());
            List<Object> pieces = new ArrayList<>();
            for (int band = 0; band < FluidRenderLayout.PIXELS_PER_CELL; band++) {
                String base = prefix + "/bands/" + band;
                addJson(files, "models/" + base + "/fallback.json", bandModel(band, "fluidcore:render/solid"));
                List<Object> options = new ArrayList<>();
                for (FluidDefinition fluid : fluids) {
                    String child = base + "/" + fluidPath(fluid.key());
                    addJson(files, "models/" + child + ".json", bandModel(band, style.texture(fluid)));
                    options.add(Map.of("when", fluid.key().toString(), "model", itemModel(child, band)));
                }
                // Blank bands are genuinely empty; unknown identities use a colored fallback without altering their data.
                options.add(Map.of("when", "", "model", Map.of("type", "minecraft:empty")));
                pieces.add(Map.of("type", "minecraft:select", "property", "minecraft:custom_model_data", "index", band,
                        "cases", options, "fallback", itemModel(base + "/fallback", band)));
            }
            if (style.overlay() != null) {
                String overlay = prefix + "/overlay";
                addJson(files, "models/" + overlay + ".json", Map.of("parent", "minecraft:item/generated",
                        "textures", Map.of("layer0", style.overlay())));
                pieces.add(Map.of("type", "minecraft:model", "model", "fluidcore:" + overlay));
            }
            addJson(files, "items/" + prefix + "/gui.json", Map.of("model", Map.of("type", "minecraft:composite", "models", pieces)));
            if (style.worldModels()) {
                worldModels(files, prefix, null, "fluidcore:render/solid");
                for (FluidDefinition fluid : fluids) worldModels(files, prefix, fluid.key(), style.texture(fluid));
            }
        }
        if (files.size() > MAX_FILES) throw new IllegalArgumentException("Generated render pack exceeds " + MAX_FILES + " files");
        Path absoluteRoot = root.toAbsolutePath().normalize();
        for (Map.Entry<String, byte[]> entry : files.entrySet()) {
            Path target = absoluteRoot.resolve(entry.getKey()).normalize();
            if (!target.startsWith(absoluteRoot)) throw new IOException("Generated render path escapes its pack directory");
            rejectLinks(absoluteRoot, target);
            if (Files.isRegularFile(target) && Arrays.equals(Files.readAllBytes(target), entry.getValue())) continue;
            Files.createDirectories(target.getParent());
            Path temporary = Files.createTempFile(target.getParent(), ".fluidcore-render-", ".tmp");
            try {
                Files.write(temporary, entry.getValue());
                try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
                catch (AtomicMoveNotSupportedException unsupported) { Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING); }
            } finally { Files.deleteIfExists(temporary); }
        }
        // Deliberately never delete files: another cached pack or an older registered style may still refer to them.
        return files.size();
    }
    private static void rejectLinks(Path root, Path target) throws IOException {
        for (Path current = target; current != null && current.startsWith(root); current = current.getParent())
            if (Files.isSymbolicLink(current)) throw new IOException("Generated render assets cannot traverse a symbolic link: " + current);
    }
    private static Map<String, Object> bandModel(int band, String texture) {
        return Map.of("textures", Map.of("fluid", texture, "particle", texture), "gui_light", "front",
                "elements", List.of(Map.of("from", List.of(0, band, 8), "to", List.of(16, band + 1, 8),
                        "shade", false, "faces", Map.of("south", Map.of("uv", List.of(0, 15 - band, 16, 16 - band),
                                "texture", "#fluid", "tintindex", 0)))),
                "display", Map.of("gui", Map.of("rotation", List.of(0, 0, 0), "translation", List.of(0, 0, 0), "scale", List.of(1, 1, 1))));
    }
    private static Map<String, Object> itemModel(String path, int colorIndex) {
        return Map.of("type", "minecraft:model", "model", "fluidcore:" + path,
                "tints", List.of(Map.of("type", "minecraft:custom_model_data", "index", colorIndex, "default", 0xffffff)));
    }
    private static void worldModels(Map<String, byte[]> files, String prefix, FluidKey fluid, String texture) {
        String path = prefix + "/world/" + (fluid == null ? "fallback" : fluidPath(fluid));
        addJson(files, "items/" + path + "/0.json", Map.of("model", Map.of("type", "minecraft:empty")));
        for (int level = 1; level <= 16; level++) {
            double height = 1 + 14 * level / 16.0;
            Map<String, Object> faces = new LinkedHashMap<>();
            for (String direction : List.of("up", "down", "north", "east", "south", "west"))
                faces.put(direction, Map.of("uv", List.of(0, 0, 16, 16), "texture", "#fluid", "tintindex", 0));
            addJson(files, "models/" + path + "/" + level + ".json", Map.of("textures", Map.of("fluid", texture, "particle", texture),
                    "elements", List.of(Map.of("from", List.of(1, 1, 1), "to", List.of(15, height, 15), "faces", faces))));
            addJson(files, "items/" + path + "/" + level + ".json", Map.of("model", itemModel(path + "/" + level, 0)));
        }
    }
    private static void addJson(Map<String, byte[]> files, String path, Object value) {
        if (files.size() >= MAX_FILES) throw new IllegalArgumentException("Generated render pack exceeds " + MAX_FILES + " files");
        files.put("assets/fluidcore/" + path, json(value).getBytes(StandardCharsets.UTF_8));
    }
    static String json(Object value) {
        if (value instanceof Map<?, ?> map) {
            List<String> entries = new ArrayList<>();
            map.entrySet().stream().sorted(Comparator.comparing(entry -> String.valueOf(entry.getKey())))
                    .forEach(entry -> entries.add(quote(String.valueOf(entry.getKey())) + ":" + json(entry.getValue())));
            return "{" + String.join(",", entries) + "}";
        }
        if (value instanceof Collection<?> list) return "[" + String.join(",", list.stream().map(FluidRenderAssets::json).toList()) + "]";
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        if (value == null) return "null";
        return quote(String.valueOf(value));
    }
    private static String quote(String value) {
        StringBuilder text = new StringBuilder("\"");
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '"' || character == '\\') text.append('\\').append(character);
            else if (character < 32) text.append(String.format(Locale.ROOT, "\\u%04x", (int) character));
            else text.append(character);
        }
        return text.append('"').toString();
    }
}
