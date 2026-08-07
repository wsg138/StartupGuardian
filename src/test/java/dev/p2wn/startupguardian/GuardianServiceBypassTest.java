package dev.p2wn.startupguardian;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GuardianServiceBypassTest {

    private static final UUID PLAYER =
            UUID.fromString("00000000-0000-0000-0000-000000000001");

    @TempDir
    Path directory;

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

    @Test
    void quarantinedCorruptionKeepsBypassActiveAcrossRestartUntilReset()
            throws IOException {

        IncidentStore firstStore = new IncidentStore(
                directory,
                GuardianServiceTestSupport.LOGGER);
        Files.writeString(firstStore.path(), "{not-json");
        assertTrue(firstStore.load().isEmpty());

        IncidentStore restartedStore = new IncidentStore(
                directory,
                GuardianServiceTestSupport.LOGGER);
        List<String> sequence = new ArrayList<>();
        GuardianServiceTestSupport.FakeEnvironment environment =
                new GuardianServiceTestSupport.FakeEnvironment(
                        sequence,
                        GuardianServiceTestSupport.HEALTHY);
        environment.whitelist = true;
        Settings.Bypass bypass = new Settings.Bypass(
                List.of(PLAYER),
                "",
                false);
        GuardianService service = GuardianServiceTestSupport.service(
                restartedStore,
                new GuardianServiceTestSupport.FakeNotifier(sequence),
                environment,
                GuardianServiceTestSupport.settings(false, false, bypass));

        assertTrue(restartedStore.corrupted());
        assertTrue(restartedStore.hasActiveIncident());
        assertTrue(service.incident().isPresent());
        assertTrue(service.allowsCriticalBypass(
                GuardianServiceTestSupport.player(PLAYER, false, false)));

        service.startupCheck();

        assertTrue(restartedStore.corrupted());
        assertTrue(Files.exists(directory.resolve(
                IncidentStore.CORRUPTION_SENTINEL_FILE)));
        assertTrue(service.allowsCriticalBypass(
                GuardianServiceTestSupport.player(PLAYER, false, false)));

        assertTrue(service.reset());

        IncidentStore resetStore = new IncidentStore(
                directory,
                GuardianServiceTestSupport.LOGGER);
        assertFalse(resetStore.hasActiveIncident());
        assertFalse(resetStore.corrupted());
        assertFalse(service.allowsCriticalBypass(
                GuardianServiceTestSupport.player(PLAYER, false, false)));
    }
}
