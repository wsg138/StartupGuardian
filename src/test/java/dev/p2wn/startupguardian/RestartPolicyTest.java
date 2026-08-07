package dev.p2wn.startupguardian;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
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
    void schedulingDecisionDoesNotConsumeAttemptBeforeSchedulerSucceeds() {
        Incident incident = Incident.create(failed, false, false);
        Settings.Loop loop = new Settings.Loop(true, 2);

        RestartPolicy.Decision decision = RestartPolicy.evaluate(
                incident,
                loop,
                false,
                false);

        assertTrue(decision.scheduleRestart());
        assertSame(incident, decision.incident());
        assertEquals(0, decision.incident().automaticRestartAttempts());
    }

    @Test
    void stopsAfterPersistedAttemptsReachConfiguredMaximum() {
        Incident incident = Incident.create(failed, false, true)
                .withRestartScheduled()
                .withRestartScheduled();

        RestartPolicy.Decision decision = RestartPolicy.evaluate(
                incident,
                new Settings.Loop(true, 2),
                false,
                false);

        assertFalse(decision.scheduleRestart());
        assertEquals(2, decision.incident().automaticRestartAttempts());
        assertTrue(decision.incident().restartLoopStopped());
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
        Incident incident = Incident.create(failed, false, false);

        RestartPolicy.Decision decision = RestartPolicy.evaluate(
                incident,
                new Settings.Loop(false, 0),
                false,
                true);

        assertFalse(decision.scheduleRestart());
        assertTrue(decision.incident().restartLoopStopped());
    }
}
