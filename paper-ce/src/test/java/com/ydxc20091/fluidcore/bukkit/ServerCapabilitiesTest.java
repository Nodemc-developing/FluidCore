package com.ydxc20091.fluidcore.bukkit;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ServerCapabilitiesTest {
    @Test void olderClientsKeepIntegerModelData() {
        for (String version : new String[]{"1.21", "1.21.1", "1.21.2", "1.21.3"}) assertFalse(ServerCapabilities.structuredModels(version), version);
    }
    @Test void newerClientsUseStructuredModelData() {
        for (String version : new String[]{"1.21.4", "1.21.11", "26.1", "26.1.2", "26.2", "26.3"}) assertTrue(ServerCapabilities.structuredModels(version), version);
    }
}
