package dev.p2wn.startupguardian;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

public record Incident(
        String incidentId,
        Instant firstDetection,
        Instant lastDetection,
        List<Failure> failures,
        boolean previousWhitelistEnabled,
        boolean guardianEnabledWhitelist,
        int automaticRestartAttempts,
        boolean restartLoopStopped) {

    public Incident {
        incidentId = requireText(incidentId, "incidentId");
        Objects.requireNonNull(firstDetection, "firstDetection");
        Objects.requireNonNull(lastDetection, "lastDetection");
        failures = List.copyOf(Objects.requireNonNull(failures, "failures"));
        if (failures.isEmpty()) {
            throw new IllegalArgumentException("failures must not be empty");
        }
        if (automaticRestartAttempts < 0) {
            throw new IllegalArgumentException(
                    "automaticRestartAttempts must not be negative");
        }
    }

    public record Failure(
            String configuredName,
            String detectedName,
            String status) {

        public Failure {
            configuredName = requireText(configuredName, "configuredName");
            if (detectedName != null) {
                detectedName = requireText(detectedName, "detectedName");
            }
            status = requireText(status, "status");
        }
    }

    public static Incident create(
            List<PluginHealth> health,
            boolean previousWhitelist,
            boolean guardianEnabledWhitelist) {

        Instant now = Instant.now();
        return new Incident(
                UUID.randomUUID().toString(),
                now,
                now,
                failures(health),
                previousWhitelist,
                guardianEnabledWhitelist,
                0,
                false);
    }

    public Incident observed(List<PluginHealth> health) {
        return new Incident(
                incidentId,
                firstDetection,
                Instant.now(),
                failures(health),
                previousWhitelistEnabled,
                guardianEnabledWhitelist,
                automaticRestartAttempts,
                restartLoopStopped);
    }

    public Incident withRestartScheduled() {
        return new Incident(
                incidentId,
                firstDetection,
                lastDetection,
                failures,
                previousWhitelistEnabled,
                guardianEnabledWhitelist,
                automaticRestartAttempts + 1,
                false);
    }

    public Incident stopLoop() {
        return new Incident(
                incidentId,
                firstDetection,
                lastDetection,
                failures,
                previousWhitelistEnabled,
                guardianEnabledWhitelist,
                automaticRestartAttempts,
                true);
    }

    public boolean automaticRestartArmed() {
        return !restartLoopStopped;
    }

    private static List<Failure> failures(List<PluginHealth> health) {
        return health.stream()
                .filter(pluginHealth -> !pluginHealth.healthy())
                .map(pluginHealth -> new Failure(
                        pluginHealth.configuredName(),
                        pluginHealth.detectedName(),
                        pluginHealth.state().name().toLowerCase(Locale.ROOT)))
                .toList();
    }

    private static String requireText(String value, String name) {
        String checked = Objects.requireNonNull(value, name).trim();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return checked;
    }
}
