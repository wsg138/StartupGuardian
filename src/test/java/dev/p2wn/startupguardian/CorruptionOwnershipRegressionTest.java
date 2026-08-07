package dev.p2wn.startupguardian;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

class CorruptionOwnershipRegressionTest extends GuardianServiceTestSupport {

    private static final UUID TRUSTED_PLAYER =
            UUID.fromString("00000000-0000-0000-0000-000000000001");

    @TempDir
    Path directory;

    @Test
    void corruptOwnedWhitelistRemainsUnknownUntilExplicitReset() throws IOException {
        IncidentStore originalStore = new IncidentStore(directory, LOGGER);
        Incident originalIncident = Incident.create(FAILED, false, true);
        originalStore.save(originalIncident);

        Files.writeString(originalStore.path(), "{not-json");
        IncidentStore quarantineStore = new IncidentStore(directory, LOGGER);
        assertTrue(quarantineStore.load().isEmpty());
        assertTrue(quarantineStore.corrupted());

        Path sentinel = directory.resolve(IncidentStore.CORRUPTION_SENTINEL_FILE);
        assertTrue(Files.exists(sentinel));
        assertFalse(Files.exists(quarantineStore.path()));

        IncidentStore restartedStore = new IncidentStore(directory, LOGGER);
        List<String> sequence = new ArrayList<>();
        FakeEnvironment environment = new FakeEnvironment(sequence, FAILED);
        environment.whitelist = true;
        FakeNotifier notifier = new FakeNotifier(sequence);
        Settings.Bypass bypass = new Settings.Bypass(
                List.of(TRUSTED_PLAYER),
                "",
                false);
        GuardianService service = service(
                restartedStore,
                notifier,
                environment,
                settings(false, false, bypass));

        service.startupCheck();

        assertTrue(restartedStore.corrupted());
        assertTrue(Files.exists(sentinel));
        assertTrue(restartedStore.load().isEmpty());
        assertFalse(Files.exists(restartedStore.path()));
        assertTrue(environment.whitelist);
        assertEquals(0, environment.whitelistChanges);
        assertEquals(0, environment.scheduleCalls);
        assertEquals(0, environment.nonOperator.kicks);
        assertEquals(0, environment.operator.kicks);
        assertEquals(1, notifier.incidents);
        assertEquals(0, notifier.persistenceFailures);
        assertEquals(0, notifier.recoveries);
        assertEquals("CORRUPTED-MARKER", service.incident().orElseThrow().incidentId());
        assertTrue(service.allowsCriticalBypass(player(TRUSTED_PLAYER, false, false)));

        environment.health = HEALTHY;
        service.startupCheck();

        assertTrue(restartedStore.corrupted());
        assertTrue(Files.exists(sentinel));
        assertTrue(environment.whitelist);
        assertEquals(0, environment.whitelistChanges);
        assertEquals(0, environment.scheduleCalls);
        assertEquals(0, notifier.recoveries);
        assertTrue(service.allowsCriticalBypass(player(TRUSTED_PLAYER, false, false)));

        assertTrue(service.reset());

        assertFalse(restartedStore.corrupted());
        assertFalse(Files.exists(sentinel));
        assertFalse(Files.exists(restartedStore.path()));
        assertFalse(restartedStore.hasActiveIncident());
        assertTrue(environment.whitelist);
        assertFalse(service.allowsCriticalBypass(player(TRUSTED_PLAYER, false, false)));
    }
}
