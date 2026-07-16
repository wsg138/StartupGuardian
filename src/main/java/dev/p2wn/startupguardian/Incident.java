package dev.p2wn.startupguardian;

import java.time.Instant;
import java.util.List;

public record Incident(String incidentId, Instant firstDetection, Instant lastDetection, List<Failure> failures, boolean previousWhitelistEnabled, boolean guardianEnabledWhitelist, int automaticRestartAttempts, boolean restartLoopStopped, boolean automaticRestartArmed) {
    public record Failure(String configuredName, String detectedName, String status) {}
    public static Incident create(List<PluginHealth> health, boolean previousWhitelist, boolean guardianEnabled) {
        Instant now = Instant.now(); return new Incident(java.util.UUID.randomUUID().toString(), now, now, failures(health), previousWhitelist, guardianEnabled, 0, false, true);
    }
    public Incident observed(List<PluginHealth> health) { return new Incident(incidentId, firstDetection, Instant.now(), failures(health), previousWhitelistEnabled, guardianEnabledWhitelist, automaticRestartAttempts, restartLoopStopped, automaticRestartArmed); }
    public Incident withRestartScheduled() { return new Incident(incidentId, firstDetection, lastDetection, failures, previousWhitelistEnabled, guardianEnabledWhitelist, automaticRestartAttempts + 1, false, automaticRestartArmed); }
    public Incident stopLoop() { return new Incident(incidentId, firstDetection, lastDetection, failures, previousWhitelistEnabled, guardianEnabledWhitelist, automaticRestartAttempts, true, false); }
    private static List<Failure> failures(List<PluginHealth> health) { return health.stream().filter(h -> !h.healthy()).map(h -> new Failure(h.configuredName(), h.detectedName(), h.state().name().toLowerCase())).toList(); }
}
