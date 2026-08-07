package dev.p2wn.startupguardian;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.logging.Logger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IncidentStateTest {

    private static final Logger LOGGER = Logger.getAnonymousLogger();
    private static final String INCIDENT_ID = "00000000-0000-0000-0000-000000000001";
    private static final String FIRST_DETECTION = "2026-08-06T12:00:00Z";
    private static final String LAST_DETECTION = "2026-08-06T12:01:00Z";

    private final List<PluginHealth> failed = List.of(
            new PluginHealth(
                    "WorldGuard",
                    "WorldGuard",
                    PluginHealth.State.DISABLED));

    @TempDir
    Path directory;

    @Test
    void currentSchemaRoundTrips() throws IOException {
        IncidentStore store = new IncidentStore(directory, LOGGER);
        Incident original = Incident.create(failed, false, true)
                .withRestartScheduled();

        store.save(original);
        String json = Files.readString(store.path());
        Optional<Incident> loaded = new IncidentStore(directory, LOGGER).load();

        assertTrue(json.contains("\"schemaVersion\": 1"));
        assertEquals(original, loaded.orElseThrow());
    }

    @Test
    void missingPluginRoundTripsWithoutFalseCorruption() throws IOException {
        List<PluginHealth> missing = List.of(
                new PluginHealth(
                        "PolarLoader",
                        null,
                        PluginHealth.State.MISSING));
        IncidentStore firstStore = new IncidentStore(directory, LOGGER);
        Incident original = Incident.create(missing, false, true)
                .withRestartScheduled();

        firstStore.save(original);
        String json = Files.readString(firstStore.path());
        IncidentStore restartedStore = new IncidentStore(directory, LOGGER);
        Optional<Incident> loaded = restartedStore.load();

        assertTrue(json.contains("\"detectedName\": null"));
        assertEquals(original, loaded.orElseThrow());
        assertFalse(restartedStore.corrupted());
    }

    @Test
    void currentSchemaAcceptsOmittedNullableDetectedName() throws IOException {
        IncidentStore store = new IncidentStore(directory, LOGGER);
        Files.writeString(store.path(), currentMissingPluginMarker());

        Incident incident = store.load().orElseThrow();

        assertEquals("Polar", incident.failures().getFirst().configuredName());
        assertEquals(null, incident.failures().getFirst().detectedName());
        assertEquals("missing", incident.failures().getFirst().status());
        assertFalse(store.corrupted());
    }

    @Test
    void currentSchemaRejectsInvalidDetectedName() throws IOException {
        JsonObject marker = parsedCurrentMarker();
        marker.getAsJsonArray("failures")
                .get(0)
                .getAsJsonObject()
                .addProperty("detectedName", 1);

        assertRejected(marker.toString());
    }

    @Test
    void validLegacyMarkerRemainsReadable() throws IOException {
        IncidentStore store = new IncidentStore(directory, LOGGER);
        Files.writeString(store.path(), legacyMarker());

        Incident incident = store.load().orElseThrow();
        store.save(incident);
        JsonObject migrated = JsonParser.parseString(
                Files.readString(store.path())).getAsJsonObject();

        assertEquals(INCIDENT_ID, incident.incidentId());
        assertEquals(1, incident.automaticRestartAttempts());
        assertFalse(incident.restartLoopStopped());
        assertEquals(IncidentStore.CURRENT_SCHEMA_VERSION,
                migrated.get("schemaVersion").getAsInt());
    }

    @Test
    void missingRestartCountIsRejected() throws IOException {
        JsonObject marker = parsedCurrentMarker();
        marker.remove("automaticRestartAttempts");

        assertRejected(marker.toString());
    }

    @Test
    void missingWhitelistFieldsAreRejected() throws IOException {
        JsonObject marker = parsedCurrentMarker();
        marker.remove("previousWhitelistEnabled");
        marker.remove("guardianEnabledWhitelist");

        assertRejected(marker.toString());
    }

    @Test
    void missingFailuresAreRejected() throws IOException {
        JsonObject marker = parsedCurrentMarker();
        marker.remove("failures");

        assertRejected(marker.toString());
    }

    @Test
    void wrongJsonTypesAreRejected() throws IOException {
        JsonObject marker = parsedCurrentMarker();
        marker.addProperty("automaticRestartAttempts", "1");
        marker.addProperty("guardianEnabledWhitelist", 1);

        assertRejected(marker.toString());
    }

    @Test
    void unsupportedFutureSchemaIsRejected() throws IOException {
        JsonObject marker = parsedCurrentMarker();
        marker.addProperty("schemaVersion", 99);

        assertRejected(marker.toString());
    }

    @Test
    void malformedJsonIsRejected() throws IOException {
        assertRejected("{not-json");
    }

    @Test
    void successfulQuarantineMovesTheInvalidMarker() throws IOException {
        IncidentStore store = new IncidentStore(directory, LOGGER);
        Files.writeString(store.path(), "{}");

        assertTrue(store.load().isEmpty());
        assertTrue(store.corrupted());
        assertFalse(Files.exists(store.path()));
        assertTrue(Files.exists(directory.resolve(
                IncidentStore.CORRUPTION_SENTINEL_FILE)));
        try (Stream<Path> paths = Files.list(directory)) {
            assertTrue(paths.anyMatch(path -> path.getFileName().toString().contains("corrupt")));
        }
    }

    @Test
    void quarantineFailureLeavesSafetyLatchSet() throws IOException {
        IncidentStore.FileMover failingMover = (
                Path source,
                Path target,
                CopyOption... options) -> {
            throw new IOException("forced move failure");
        };
        IncidentStore store = new IncidentStore(directory, LOGGER, failingMover);
        Files.writeString(store.path(), "{}");

        assertTrue(store.load().isEmpty());
        assertTrue(store.corrupted());
        assertTrue(store.quarantineFailed());
        assertTrue(Files.exists(store.path()));
        assertTrue(Files.exists(directory.resolve(
                IncidentStore.CORRUPTION_SENTINEL_FILE)));
    }

    @Test
    void ordinarySaveCannotResolvePersistentCorruption() throws IOException {
        IncidentStore firstStore = new IncidentStore(directory, LOGGER);
        Files.writeString(firstStore.path(), "{}");
        assertTrue(firstStore.load().isEmpty());

        Path sentinel = directory.resolve(IncidentStore.CORRUPTION_SENTINEL_FILE);
        Incident trustedIncident = Incident.create(failed, false, true);
        IncidentStore restartedStore = new IncidentStore(directory, LOGGER);

        assertThrows(IOException.class, () -> restartedStore.save(trustedIncident));
        assertTrue(restartedStore.corrupted());
        assertTrue(Files.exists(sentinel));
        assertFalse(Files.exists(restartedStore.path()));
    }

    @Test
    void validMarkerBesideSentinelKeepsCorruptionDominant() throws IOException {
        Incident trustedIncident = Incident.create(failed, false, true);
        IncidentStore firstStore = new IncidentStore(directory, LOGGER);
        firstStore.save(trustedIncident);
        Files.writeString(
                directory.resolve(IncidentStore.CORRUPTION_SENTINEL_FILE),
                "unresolved\n");

        IncidentStore restartedStore = new IncidentStore(directory, LOGGER);

        assertEquals(trustedIncident, restartedStore.load().orElseThrow());
        assertTrue(restartedStore.corrupted());
        assertThrows(
                IOException.class,
                () -> restartedStore.save(trustedIncident.observed(failed)));
        assertTrue(Files.exists(directory.resolve(
                IncidentStore.CORRUPTION_SENTINEL_FILE)));
    }

    @Test
    void failedSentinelClearLeavesPersistentEmergencyState() throws IOException {
        Incident trustedIncident = Incident.create(failed, false, true);
        IncidentStore firstStore = new IncidentStore(directory, LOGGER);
        firstStore.save(trustedIncident);
        Path sentinel = directory.resolve(IncidentStore.CORRUPTION_SENTINEL_FILE);
        Files.writeString(sentinel, "unresolved\n");

        IncidentStore.FileDeleter failingSentinelDeleter = path -> {
            if (path.equals(sentinel)) {
                throw new IOException("forced sentinel deletion failure");
            }
            return Files.deleteIfExists(path);
        };
        IncidentStore store = new IncidentStore(
                directory,
                LOGGER,
                Files::move,
                failingSentinelDeleter);

        assertThrows(IOException.class, store::clear);

        assertFalse(Files.exists(store.path()));
        assertTrue(Files.exists(sentinel));
        assertTrue(store.corrupted());
        assertTrue(store.hasActiveIncident());
    }

    @Test
    void restartCountPersistsAcrossStoreInstances() throws IOException {
        IncidentStore firstStore = new IncidentStore(directory, LOGGER);
        Incident incident = Incident.create(failed, true, false)
                .withRestartScheduled()
                .observed(failed);
        firstStore.save(incident);

        Incident reloaded = new IncidentStore(directory, LOGGER).load().orElseThrow();

        assertEquals(1, reloaded.automaticRestartAttempts());
        assertTrue(reloaded.previousWhitelistEnabled());
        assertFalse(reloaded.guardianEnabledWhitelist());
    }

    @Test
    void cachedStateTracksSaveAndClear() throws IOException {
        IncidentStore store = new IncidentStore(directory, LOGGER);

        assertFalse(store.hasActiveIncident());
        store.save(Incident.create(failed, false, true));
        assertTrue(store.hasActiveIncident());
        store.clear();
        assertFalse(store.hasActiveIncident());
    }

    private void assertRejected(String json) throws IOException {
        IncidentStore store = new IncidentStore(directory, LOGGER);
        Files.writeString(store.path(), json);

        assertTrue(store.load().isEmpty());
        assertTrue(store.corrupted());
    }

    private static String currentMarker() {
        return """
                {
                  "incidentId": "%s",
                  "firstDetection": "%s",
                  "lastDetection": "%s",
                  "failures": [
                    {"configuredName": "WorldGuard", "detectedName": "WorldGuard", "status": "disabled"}
                  ],
                  "previousWhitelistEnabled": false,
                  "guardianEnabledWhitelist": true,
                  "automaticRestartAttempts": 1,
                  "restartLoopStopped": false,
                  "schemaVersion": 1
                }
                """.formatted(INCIDENT_ID, FIRST_DETECTION, LAST_DETECTION);
    }

    private static String currentMissingPluginMarker() {
        return """
                {
                  "incidentId": "10bd2e1d-c1f3-4e19-8260-94722fc31d56",
                  "firstDetection": "2026-08-07T04:01:25.309830352Z",
                  "lastDetection": "2026-08-07T04:01:25.309830352Z",
                  "failures": [
                    {"configuredName": "Polar", "status": "missing"}
                  ],
                  "previousWhitelistEnabled": false,
                  "guardianEnabledWhitelist": true,
                  "automaticRestartAttempts": 1,
                  "restartLoopStopped": false,
                  "schemaVersion": 1
                }
                """;
    }

    private static JsonObject parsedCurrentMarker() {
        return JsonParser.parseString(currentMarker()).getAsJsonObject();
    }

    private static String legacyMarker() {
        return """
                {
                  "incidentId": "%s",
                  "firstDetection": "%s",
                  "lastDetection": "%s",
                  "failures": [
                    {"configuredName": "WorldGuard", "detectedName": "WorldGuard", "status": "disabled"}
                  ],
                  "previousWhitelistEnabled": false,
                  "guardianEnabledWhitelist": true,
                  "automaticRestartAttempts": 1,
                  "restartLoopStopped": false,
                  "automaticRestartArmed": true
                }
                """.formatted(INCIDENT_ID, FIRST_DETECTION, LAST_DETECTION);
    }
}
