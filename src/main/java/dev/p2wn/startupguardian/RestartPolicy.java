package dev.p2wn.startupguardian;

import java.util.Objects;

final class RestartPolicy {

    private RestartPolicy() {
    }

    static Decision evaluate(
            Incident incident,
            Settings.Loop loop,
            boolean restartAlreadyScheduled,
            boolean persistenceCorrupted) {

        Objects.requireNonNull(incident, "incident");
        Objects.requireNonNull(loop, "loop");

        if (persistenceCorrupted) {
            return new Decision(incident.stopLoop(), false);
        }
        if (!incident.automaticRestartArmed() || restartAlreadyScheduled) {
            return new Decision(incident, false);
        }
        if (loop.enabled()
                && incident.automaticRestartAttempts() >= loop.maximumRestarts()) {
            return new Decision(incident.stopLoop(), false);
        }

        return new Decision(incident.withRestartScheduled(), true);
    }

    record Decision(Incident incident, boolean scheduleRestart) {

        Decision {
            Objects.requireNonNull(incident, "incident");
        }
    }
}
