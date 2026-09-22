package dev.p2wn.startupguardian;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PluginHealthTest {

    @Test
    void constructorTrimsNamesAndEnabledIsHealthy() {
        PluginHealth health = new PluginHealth(
                "  Vault  ",
                "  Vault  ",
                PluginHealth.State.ENABLED);

        assertEquals("Vault", health.configuredName());
        assertEquals("Vault", health.detectedName());
        assertTrue(health.healthy());
    }

    @Test
    void missingPluginAllowsNullDetectedNameAndIsUnhealthy() {
        PluginHealth health = new PluginHealth(
                "Plan",
                null,
                PluginHealth.State.MISSING);

        assertNull(health.detectedName());
        assertFalse(health.healthy());
    }

    @Test
    void disabledPluginIsUnhealthy() {
        PluginHealth health = new PluginHealth(
                "CoreProtect",
                "CoreProtect",
                PluginHealth.State.DISABLED);

        assertFalse(health.healthy());
    }

    @Test
    void constructorRejectsMissingBlankNamesAndState() {
        assertThrows(NullPointerException.class,
                () -> new PluginHealth(null, null, PluginHealth.State.MISSING));
        assertThrows(IllegalArgumentException.class,
                () -> new PluginHealth("   ", null, PluginHealth.State.MISSING));
        assertThrows(IllegalArgumentException.class,
                () -> new PluginHealth("Plan", "   ", PluginHealth.State.DISABLED));
        assertThrows(NullPointerException.class,
                () -> new PluginHealth("Plan", "Plan", null));
    }
}
