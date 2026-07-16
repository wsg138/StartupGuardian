package dev.p2wn.startupguardian;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.time.Instant;
import java.util.List;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;

class IncidentStateTest {
    private final List<PluginHealth> failed = List.of(new PluginHealth("WorldGuard", "WorldGuard", PluginHealth.State.DISABLED));
    @Test void restartCountPersistsAcrossObservations() {
        Incident incident = Incident.create(failed, true, false).withRestartScheduled().observed(failed);
        assertEquals(1, incident.automaticRestartAttempts()); assertTrue(incident.previousWhitelistEnabled()); assertFalse(incident.guardianEnabledWhitelist());
    }
    @Test void markerRoundTripAndMalformedBackup() throws Exception {
        var dir = Files.createTempDirectory("guardian-test"); IncidentStore store = new IncidentStore(dir); Incident original = Incident.create(failed, false, true).withRestartScheduled(); store.save(original);
        assertEquals(original.incidentId(), store.load(Logger.getAnonymousLogger()).orElseThrow().incidentId());
        Files.writeString(store.path(), "not json"); assertTrue(store.load(Logger.getAnonymousLogger()).isEmpty());
        assertTrue(Files.list(dir).anyMatch(p -> p.getFileName().toString().contains("corrupt")));
    }
    @Test void loopStopsAtMaximum() {
        Incident oneAttempt = Incident.create(failed, false, true).withRestartScheduled();
        assertEquals(1, oneAttempt.automaticRestartAttempts());
        Incident stopped = oneAttempt.stopLoop(); assertTrue(stopped.restartLoopStopped()); assertFalse(stopped.automaticRestartArmed());
    }
}
