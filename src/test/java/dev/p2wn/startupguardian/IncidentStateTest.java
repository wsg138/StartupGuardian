package dev.p2wn.startupguardian;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Logger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class IncidentStateTest {

    private static final Logger LOGGER = Logger.getAnonymousLogger();

    private final List<PluginHealth> failed = List.of(
            new PluginHealth(
                    "WorldGuard",
                    "WorldGuard",
                    PluginHealth.State.DISABLED));

    @Test
    void restartCountPersistsAcrossObservations() {
        Incident incident = Incident.create(failed, true, false)
                .withRestartScheduled()
                .observed(failed);

        assertEquals(1, incident.automaticRestartAttempts());
        assertTrue(incident.previousWhitelistEnabled());
        assertFalse(incident.guardianEnabledWhitelist());
        assertTrue(incident.automaticRestartArmed());
    }

    @Test
    void markerRoundTripsAndMalformedFileIsQuarantined() throws IOException {
        Path directory = Files.createTempDirectory("guardian-test");
        IncidentStore store = new IncidentStore(directory);
        Incident original = Incident.create(failed, false, true)
                .withRestartScheduled();

        store.save(original);
        assertEquals(
                original.incidentId(),
                store.load(LOGGER).orElseThrow().incidentId());

        IncidentStore malformedStore = new IncidentStore(directory);
        Files.writeString(malformedStore.path(), "not json");
        assertTrue(malformedStore.load(LOGGER).isEmpty());
        assertTrue(malformedStore.corrupted());

        try (Stream<Path> paths = Files.list(directory)) {
            assertTrue(paths.anyMatch(path ->
                    path.getFileName().toString().contains("corrupt")));
        }
    }

    @Test
    void structurallyIncompleteJsonIsRejected() throws IOException {
        Path directory = Files.createTempDirectory("guardian-incomplete-test");
        IncidentStore store = new IncidentStore(directory);
        Files.writeString(store.path(), "{}");

        assertTrue(store.load(LOGGER).isEmpty());
        assertTrue(store.corrupted());
    }

    @Test
    void cachedStateTracksSaveAndClearWithoutRepeatedDiskReads()
            throws IOException {

        Path directory = Files.createTempDirectory("guardian-cache-test");
        IncidentStore store = new IncidentStore(directory);

        assertFalse(store.hasActiveIncident(LOGGER));
        Incident incident = Incident.create(failed, false, true);
        store.save(incident);
        assertTrue(store.hasActiveIncident(LOGGER));

        store.clear();
        assertFalse(store.hasActiveIncident(LOGGER));
    }
}
