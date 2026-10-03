package com.ydxc20091.fluidcore.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class FluidCoreSettingsTest {
    @TempDir Path directory;

    @Test void readsVersionedConfigWithExplicitQueueBounds() throws Exception {
        Path file = directory.resolve("config.yml");
        Files.writeString(file, "config-version: 1\nasync:\n  workers: 2\n  queue-capacity: 1024\ndiagnostics:\n  ui: false\n");
        assertEquals(new FluidCoreSettings(2, 1024, false), FluidCoreSettings.load(file));
    }

    @Test void malformedDuplicateOrFutureConfigIsPreserved() throws Exception {
        Path file = directory.resolve("config.yml");
        for (String text : new String[]{"config-version: 2\n", "async:\n  workers: 0\n", "config-version: 1\nconfig-version: 2\n"}) {
            Files.writeString(file, text);
            assertThrows(Exception.class, () -> FluidCoreSettings.load(file));
            assertEquals(text, Files.readString(file));
        }
    }
}
