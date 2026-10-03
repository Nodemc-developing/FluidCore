package com.ydxc20091.fluidcore.config;

import com.ydxc20091.fluidcore.api.FluidKey;
import com.ydxc20091.fluidcore.core.FluidDefinition;
import com.ydxc20091.fluidcore.core.FluidRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class FluidMessagesTest {
    @TempDir Path directory;
    @Test void configuredTextUsesOwnerLocaleAndHasNativeHoneyNameWithoutOverwritingCustomNames() throws Exception {
        Path file = directory.resolve("lang.yml");
        Files.writeString(file, "default-language: en\nzh:\n  contains: '盛着{fluid}'\n  honey: 原蜜\nen:\n  units: four bottles\n");
        var messages = FluidMessages.load(file);
        assertEquals("盛着牛奶", messages.text("zh_cn", "contains", "fluid", "牛奶"));
        assertEquals("four bottles", messages.text(null, "units"));
        var registry = new FluidRegistry();
        assertEquals("原蜜", messages.fluidName(registry, FluidKey.of("minecraft:honey"), "zh_tw"));
        registry.register("test", FluidDefinition.builder("test:milk").displayName("山羊奶").build());
        assertEquals("山羊奶", messages.fluidName(registry, FluidKey.of("test:milk"), "en_us"));
    }
    @Test void malformedLanguageAndDuplicateKeysAreRejectedWithoutChangingTheFile() throws Exception {
        Path file = directory.resolve("lang.yml");
        for (String text : new String[]{"default-language: bad\n", "zh:\n  empty: a\n  empty: b\n"}) {
            Files.writeString(file, text); assertThrows(Exception.class, () -> FluidMessages.load(file)); assertEquals(text, Files.readString(file));
        }
    }
}
