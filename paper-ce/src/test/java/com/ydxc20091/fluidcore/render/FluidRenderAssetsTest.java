package com.ydxc20091.fluidcore.render;

import com.ydxc20091.fluidcore.api.FluidKey;
import com.ydxc20091.fluidcore.core.FluidRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.imageio.ImageIO;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class FluidRenderAssetsTest {
    @TempDir Path directory;

    @Test void noStyleMeansNoGeneratedPackOrExtractedContent() throws Exception {
        Path absent = directory.resolve("absent");
        assertEquals(0, FluidRenderAssets.compile(absent, List.of(), new FluidRegistry().all()));
        assertFalse(Files.exists(absent));
    }
    @Test void generatedResourcesAreNamespacedAndPreserveExistingFiles() throws Exception {
        var style = FluidRenderStyle.builder("addon:meter").worldModels(true).overlay("addon:gui/frame").build();
        Path unrelated = directory.resolve("assets/otherpack/keep.txt");
        Files.createDirectories(unrelated.getParent()); Files.writeString(unrelated, "original");
        assertTrue(FluidRenderAssets.compile(directory, List.of(style), new FluidRegistry().all()) > 0);
        String prefix = FluidRenderAssets.prefix(style.id());
        Path gauge = directory.resolve("assets/fluidcore/items/" + prefix + "/gui.json");
        String model = Files.readString(gauge);
        assertTrue(model.contains("minecraft:composite"));
        assertTrue(model.contains("minecraft:water"));
        assertTrue(model.contains("minecraft:custom_model_data"));
        assertTrue(model.contains("fluidcore:" + prefix + "/overlay"));
        String overlay = Files.readString(directory.resolve("assets/fluidcore/models/" + prefix + "/overlay.json"));
        assertTrue(overlay.contains("addon:gui/frame"));
        assertEquals("original", Files.readString(unrelated));
        assertTrue(Files.exists(directory.resolve("assets/fluidcore/items/" + prefix + "/world/fallback/0.json")));
        String top = Files.readString(directory.resolve("assets/fluidcore/models/" + prefix + "/world/fallback/16.json"));
        assertTrue(top.contains("\"to\":[15,15.0,15]"));
        assertEquals(16, ImageIO.read(directory.resolve("assets/fluidcore/textures/render/solid.png").toFile()).getWidth());
        byte[] before = Files.readAllBytes(gauge);
        FluidRenderAssets.compile(directory, List.of(style), new FluidRegistry().all());
        assertArrayEquals(before, Files.readAllBytes(gauge));
        try (var paths = Files.walk(directory)) { assertFalse(paths.anyMatch(path -> path.getFileName().toString().endsWith(".tmp"))); }
    }
    @Test void optInWorldModelsAndSafeHashedStylePathsDoNotTouchForeignNamespaces() throws Exception {
        var style = FluidRenderStyle.builder(FluidKey.of("addon:../../outside")).build();
        FluidRenderAssets.compile(directory, List.of(style), new FluidRegistry().all());
        assertTrue(FluidRenderAssets.prefix(style.id()).matches("render/styles/[a-f0-9]{64}"));
        assertFalse(Files.exists(directory.resolve("assets/fluidcore/items/" + FluidRenderAssets.prefix(style.id()) + "/world")));
        assertFalse(Files.exists(directory.resolve("assets/addon")));
        assertThrows(IllegalArgumentException.class, () -> FluidRenderStyle.builder("addon:bad").overlay("addon:../outside").build());
    }
    @Test void badResolverDoesNotWriteAnyFilesAndJsonEscapesControlCharacters() {
        var style = FluidRenderStyle.builder("addon:bad").textures(fluid -> "addon:../../outside").build();
        assertThrows(IllegalArgumentException.class, () -> FluidRenderAssets.compile(directory, List.of(style), new FluidRegistry().all()));
        assertFalse(Files.exists(directory.resolve("assets")));
        assertEquals("\"a\\n\\\"b\\\\c\"".replace("\\n", "\\u000a"), FluidRenderAssets.json("a\n\"b\\c"));
    }
}
