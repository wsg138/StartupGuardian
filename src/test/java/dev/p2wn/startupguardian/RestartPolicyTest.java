package dev.p2wn.startupguardian;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class RestartPolicyTest {

    private final List<PluginHealth> failed = List.of(
            new PluginHealth(
                    "WorldGuard",
                    "WorldGuard",
                    PluginHealth.State.DISABLED));

    @Test
    void schedulesUntilConfiguredMaximum() {
        Incident incident = Incident.create(failed, false, true);
        Settings.Loop loop = new Settings.Loop(true, 2);

        RestartPolicy.Decision first = RestartPolicy.evaluate(
                incident,
                loop,
                false,
                false);
        RestartPolicy.Decision second = RestartPolicy.evaluate(
                first.incident(),
                loop,
                false,
                false);
        RestartPolicy.Decision third = RestartPolicy.evaluate(
                second.incident(),
                loop,
                false,
                false);

        assertTrue(first.scheduleRestart());
        assertTrue(second.scheduleRestart());
        assertFalse(third.scheduleRestart());
        assertEquals(2, third.incident().automaticRestartAttempts());
        assertTrue(third.incident().restartLoopStopped());
    }

    @Test
    void manualReenforcementDoesNotDuplicatePendingRestart() {
        Incident incident = Incident.create(failed, false, true)
                .withRestartScheduled();

        RestartPolicy.Decision decision = RestartPolicy.evaluate(
                incident,
                new Settings.Loop(true, 3),
                true,
                false);

        assertFalse(decision.scheduleRestart());
        assertTrue(decision.incident().automaticRestartArmed());
        assertEquals(1, decision.incident().automaticRestartAttempts());
    }

    @Test
    void corruptedPersistenceAlwaysSuppressesRestart() {
        Incident incident = Incident.create(failed, false, true);

        RestartPolicy.Decision decision = RestartPolicy.evaluate(
                incident,
                new Settings.Loop(false, 0),
                false,
                true);

        assertFalse(decision.scheduleRestart());
        assertTrue(decision.incident().restartLoopStopped());
    }
}
