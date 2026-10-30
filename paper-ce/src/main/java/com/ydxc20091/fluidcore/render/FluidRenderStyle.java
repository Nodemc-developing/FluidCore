/* Copyright 2026 ydxc20091. SPDX-License-Identifier: GPL-3.0-only */
package com.ydxc20091.fluidcore.render;

import com.ydxc20091.fluidcore.api.FluidKey;
import com.ydxc20091.fluidcore.api.FluidStack;
import com.ydxc20091.fluidcore.core.FluidDefinition;
import java.util.Objects;

/** Immutable presentation policy. Resolvers must be thread-safe pure data functions. */
public final class FluidRenderStyle {
    @FunctionalInterface public interface TextureResolver { String texture(FluidDefinition fluid); }
    @FunctionalInterface public interface ColorResolver { int color(FluidStack fluid, FluidDefinition definition); }
    private final FluidKey id;
    private final TextureResolver textures;
    private final ColorResolver colors;
    private final String overlay;
    private final boolean worldModels;

    private FluidRenderStyle(Builder builder) {
        id = builder.id; textures = builder.textures; colors = builder.colors;
        overlay = builder.overlay; worldModels = builder.worldModels;
    }
    public static Builder builder(FluidKey id) { return new Builder(id); }
    public static Builder builder(String id) { return builder(FluidKey.of(id)); }
    public FluidKey id() { return id; }
    public boolean worldModels() { return worldModels; }
    public String overlay() { return overlay; }
    public String texture(FluidDefinition fluid) { return resource(textures.texture(fluid)); }
    public int color(FluidStack fluid, FluidDefinition definition) { return colors.color(fluid, definition); }

    static String resource(String value) {
        FluidKey key = FluidKey.of(Objects.requireNonNull(value, "resource"));
        for (String part : key.value().split("/", -1)) {
            if (part.isEmpty() || part.equals(".") || part.equals(".."))
                throw new IllegalArgumentException("Invalid texture path: " + value);
        }
        return key.toString();
    }
    public static final class Builder {
        private final FluidKey id;
        private TextureResolver textures = fluid -> fluid.texture().orElse("fluidcore:render/solid");
        private ColorResolver colors = (stack, fluid) -> fluid.color().orElse(0xffb0b0b0);
        private String overlay;
        private boolean worldModels;
        private Builder(FluidKey id) { this.id = Objects.requireNonNull(id, "id"); }
        public Builder textures(TextureResolver value) { textures = Objects.requireNonNull(value); return this; }
        public Builder colors(ColorResolver value) { colors = Objects.requireNonNull(value); return this; }
        /** Existing pack texture reference; FluidCore never copies or extracts that pack. */
        public Builder overlay(String texture) { overlay = resource(texture); return this; }
        public Builder worldModels(boolean value) { worldModels = value; return this; }
        public FluidRenderStyle build() { return new FluidRenderStyle(this); }
    }
}
