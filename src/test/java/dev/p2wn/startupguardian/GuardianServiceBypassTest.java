package dev.p2wn.startupguardian;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class GuardianServiceBypassTest {

    private static final UUID PLAYER =
            UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test
    void explicitPermissionWorksForOperatorWhenAutomaticOpBypassIsDisabled() {
        Settings.Bypass bypass = new Settings.Bypass(
                List.of(),
                "startupguardian.bypass",
                false);

        assertTrue(GuardianService.hasCriticalBypass(
                PLAYER,
                true,
                true,
                bypass));
    }

    @Test
    void operatorDoesNotBypassWithoutPermissionWhenDisabled() {
        Settings.Bypass bypass = new Settings.Bypass(
                List.of(),
                "startupguardian.bypass",
                false);

        assertFalse(GuardianService.hasCriticalBypass(
                PLAYER,
                true,
                false,
                bypass));
    }

    @Test
    void configuredUuidAlwaysBypasses() {
        Settings.Bypass bypass = new Settings.Bypass(
                List.of(PLAYER),
                "",
                false);

        assertTrue(GuardianService.hasCriticalBypass(
                PLAYER,
                false,
                false,
                bypass));
    }
}
