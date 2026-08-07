package dev.p2wn.startupguardian;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class GuardianService {

    private final Logger logger;

    private final IncidentRepository store;

    private final GuardianNotifier notifier;

    private final GuardianEnvironment environment;

    private final AtomicBoolean handling = new AtomicBoolean();

    private Settings currentSettings;

    private Optional<GuardianEnvironment.RestartTask> restartTask = Optional.empty();

    public GuardianService(
            Logger logger,
            Settings settings,
            IncidentRepository store,
            GuardianNotifier notifier,
            GuardianEnvironment environment) {

        this.logger = Objects.requireNonNull(logger, "logger");
        currentSettings = Objects.requireNonNull(settings, "settings");
        this.store = Objects.requireNonNull(store, "store");
        this.notifier = Objects.requireNonNull(notifier, "notifier");
        this.environment = Objects.requireNonNull(environment, "environment");
    }

    public void updateSettings(Settings updatedSettings) {
        currentSettings = Objects.requireNonNull(updatedSettings, "updatedSettings");
    }

    public Settings settings() {
        return currentSettings;
    }

    public List<PluginHealth> health() {
        return environment.inspectPlugins(currentSettings.requiredPlugins());
    }

    public Optional<Incident> incident() {
        Optional<Incident> incident = store.load();
        if (incident.isPresent() || !store.corrupted()) {
            return incident;
        }

        Instant now = Instant.now();
        return Optional.of(new Incident(
                "CORRUPTED-MARKER",
                now,
                now,
                List.of(new Incident.Failure(
                        "active-incident.json",
                        null,
                        "corrupted")),
                false,
                false,
                0,
                true));
    }

    public boolean allowsCriticalBypass(Player player) {
        Objects.requireNonNull(player, "player");
        if (!environment.whitelistEnabled() || !store.hasActiveIncident()) {
            return false;
        }

        Settings.Bypass bypass = currentSettings.bypass();
        boolean hasPermission = !bypass.permission().isBlank()
                && player.hasPermission(bypass.permission());

        return hasCriticalBypass(
                player.getUniqueId(),
                player.isOp(),
                hasPermission,
                bypass);
    }

    static boolean hasCriticalBypass(
            UUID playerUuid,
            boolean operator,
            boolean hasPermission,
            Settings.Bypass bypass) {

        Objects.requireNonNull(playerUuid, "playerUuid");
        Objects.requireNonNull(bypass, "bypass");

        return bypass.playerUuids().contains(playerUuid)
                || hasPermission
                || (bypass.allowOps() && operator);
    }

    public void startupCheck() {
        check(true, null);
    }

    public void manualCheck(boolean enforce, CommandSender sender) {
        check(enforce, Objects.requireNonNull(sender, "sender"));
    }

    public boolean reset() {
        try {
            store.clear();
        } catch (IOException exception) {
            logger.log(
                    Level.SEVERE,
                    "[StartupGuardian] Could not reset incident. "
                            + "Any pending restart remains scheduled.",
                    exception);
            return false;
        }

        cancelScheduledRestart();
        return true;
    }

    public WebhookTestResult webhookTest() {
        return notifier.test(currentSettings);
    }

    public void close() {
        cancelScheduledRestart();
    }

    boolean hasPendingRestart() {
        return hasScheduledRestart();
    }

    private void check(boolean enforce, CommandSender sender) {
        List<PluginHealth> pluginHealth = health();
        List<PluginHealth> failedPlugins = pluginHealth.stream().filter(
                health -> !health.healthy()).toList();

        if (failedPlugins.isEmpty()) {
            recoverIfNeeded();
            reportHealthy(sender, pluginHealth.size());
            return;
        }

        if (sender != null && !enforce) {
            sender.sendMessage(
                    prefix()
                            + ChatColor.RED
                            + "Failed plugins: "
                            + ChatColor.WHITE
                            + describe(failedPlugins)
                            + ChatColor.GRAY
                            + " • Use "
                            + ChatColor.YELLOW
                            + "--enforce"
                            + ChatColor.GRAY
                            + " to enter protection mode.");
            return;
        }

        handleFailure(pluginHealth);
    }

    private void handleFailure(List<PluginHealth> pluginHealth) {
        if (!handling.compareAndSet(false, true)) {
            return;
        }

        try {
            Optional<Incident> existingIncident = store.load();
            boolean previousWhitelist = environment.whitelistEnabled();

            Incident incident = existingIncident.map(
                    value -> value.observed(pluginHealth)).orElseGet(
                            () -> Incident.create(
                                    pluginHealth,
                                    previousWhitelist,
                                    false));

            RestartPolicy.Decision decision = RestartPolicy.evaluate(
                    incident,
                    currentSettings.loop(),
                    hasScheduledRestart(),
                    store.corrupted());
            Incident persistedIncident = decision.incident();

            if (!save(persistedIncident)) {
                notifier.persistenceFailure(currentSettings, persistedIncident);
                return;
            }

            ProtectionResult protectionResult = applyWhitelistSafely(persistedIncident);
            if (!protectionResult.continueActions()) {
                if (protectionResult.reportIncident()) {
                    reportIncident(protectionResult.incident(), false);
                }
                return;
            }
            persistedIncident = protectionResult.incident();

            kickPlayersSafely();

            boolean restartScheduled = false;
            if (decision.scheduleRestart()) {
                Optional<GuardianEnvironment.RestartTask> scheduledTask = scheduleRestart();
                if (scheduledTask.isPresent()) {
                    restartTask = scheduledTask;
                    Incident restartIncident = persistedIncident.withRestartScheduled();
                    if (!save(restartIncident)) {
                        notifier.persistenceFailure(currentSettings, restartIncident);
                        cancelUnpersistedRestart();
                        return;
                    }
                    persistedIncident = restartIncident;
                    restartScheduled = true;
                }
            }

            reportIncident(persistedIncident, restartScheduled);
        } finally {
            handling.set(false);
        }
    }

    private ProtectionResult applyWhitelistSafely(Incident incident) {
        if (!currentSettings.protection().whitelist()
                || environment.whitelistEnabled()) {
            return ProtectionResult.continueWith(incident);
        }

        try {
            environment.setWhitelist(true);
            if (!environment.whitelistEnabled()) {
                throw new IllegalStateException(
                        "Whitelist remained disabled after setWhitelist(true)");
            }
        } catch (RuntimeException exception) {
            logger.log(
                    Level.SEVERE,
                    "[StartupGuardian] Incident was persisted, but emergency whitelist "
                            + "protection could not be applied. Ownership was not recorded, "
                            + "and kicks and restart scheduling were skipped.",
                    exception);
            return ProtectionResult.stopAndReport(incident);
        }

        Incident ownedIncident = incident.withGuardianEnabledWhitelist();
        if (!save(ownedIncident)) {
            notifier.persistenceFailure(currentSettings, ownedIncident);
            logger.severe(
                    "[StartupGuardian] The whitelist was enabled, but ownership could not "
                            + "be persisted. Automatic recovery will not disable it.");
            return ProtectionResult.stopSilently(incident);
        }
        return ProtectionResult.continueWith(ownedIncident);
    }

    private void kickPlayersSafely() {
        if (!currentSettings.protection().whitelist()
                || !currentSettings.protection().kickPlayers()) {
            return;
        }

        try {
            for (GuardianEnvironment.OnlinePlayer player : environment.onlinePlayers()) {
                if (currentSettings.protection().kickOps() || !player.operator()) {
                    player.kick(currentSettings.protection().kickMessage());
                }
            }
        } catch (RuntimeException exception) {
            logger.log(
                    Level.SEVERE,
                    "[StartupGuardian] Whitelist ownership was persisted, but one or more "
                            + "players could not be removed.",
                    exception);
        }
    }

    private Optional<GuardianEnvironment.RestartTask> scheduleRestart() {
        long delayTicks = currentSettings.protection().restartDelaySeconds() * 20L;
        try {
            return Optional.of(environment.scheduleRestart(
                    delayTicks,
                    this::dispatchRestart));
        } catch (RuntimeException exception) {
            logger.log(
                    Level.SEVERE,
                    "[StartupGuardian] The restart task could not be scheduled. "
                            + "No automatic attempt was consumed.",
                    exception);
            return Optional.empty();
        }
    }

    private void cancelUnpersistedRestart() {
        try {
            cancelScheduledRestart();
        } catch (RuntimeException exception) {
            logger.log(
                    Level.SEVERE,
                    "[StartupGuardian] Restart-attempt persistence failed and the newly "
                            + "scheduled task could not be cancelled. Manual intervention "
                            + "is required.",
                    exception);
        }
    }

    private void dispatchRestart() {
        restartTask = Optional.empty();
        boolean dispatched = environment.dispatchCommand(
                currentSettings.protection().restartCommand());

        if (dispatched) {
            return;
        }

        logger.warning(
                "[StartupGuardian] Restart command was not dispatched; "
                        + "using fallback command.");
        boolean fallbackDispatched = environment.dispatchCommand(
                currentSettings.protection().fallbackCommand());
        if (!fallbackDispatched) {
            logger.severe(
                    "[StartupGuardian] Neither the restart command nor the fallback command "
                            + "was dispatched. Manual restart is required.");
        }
    }

    private boolean hasScheduledRestart() {
        return restartTask.filter(
                task -> !task.cancelled()).isPresent();
    }

    private void cancelScheduledRestart() {
        restartTask.ifPresent(GuardianEnvironment.RestartTask::cancel);
        restartTask = Optional.empty();
    }

    private void recoverIfNeeded() {
        Optional<Incident> activeIncident = store.load();
        if (store.corrupted()) {
            logger.severe(
                    "[StartupGuardian] Plugin health recovered, but persistent incident "
                            + "corruption is recorded. Automatic recovery remains suppressed; "
                            + "trusted emergency bypasses remain active until reset.");
            return;
        }
        if (activeIncident.isEmpty()) {
            return;
        }

        Incident incident = activeIncident.orElseThrow();
        try {
            cancelScheduledRestart();
            if (currentSettings.protection().restoreWhitelist()
                    && incident.guardianEnabledWhitelist()) {
                environment.setWhitelist(incident.previousWhitelistEnabled());
            }
        } catch (RuntimeException exception) {
            logger.log(
                    Level.SEVERE,
                    "[StartupGuardian] Recovery actions failed. The incident marker was "
                            + "retained so recovery can be retried.",
                    exception);
            return;
        }

        try {
            store.clear();
        } catch (IOException exception) {
            logger.log(
                    Level.SEVERE,
                    "[StartupGuardian] Recovery actions completed, but the incident marker "
                            + "could not be cleared. It was retained for a retry.",
                    exception);
            return;
        }

        notifier.recovery(currentSettings, incident);
        logger.log(
                Level.WARNING,
                "[StartupGuardian] Recovery detected for incident {0}. "
                        + "Incident marker cleared.",
                incident.incidentId());
    }

    private boolean save(Incident incident) {
        try {
            store.save(incident);
            return true;
        } catch (IOException exception) {
            logger.log(
                    Level.SEVERE,
                    "[StartupGuardian] Could not save incident marker. "
                            + "Subsequent safety actions were skipped.",
                    exception);
            return false;
        }
    }

    private void reportIncident(Incident incident, boolean restartScheduled) {
        boolean whitelistEnabled = environment.whitelistEnabled();
        logFailure(incident, restartScheduled, whitelistEnabled);
        notifier.incident(
                currentSettings,
                incident,
                restartScheduled,
                whitelistEnabled);
    }

    private void reportHealthy(CommandSender sender, int requiredPluginCount) {
        String message = "[StartupGuardian] All "
                + requiredPluginCount
                + " required plugins are enabled.";

        if (sender == null) {
            logger.info(message);
            return;
        }

        sender.sendMessage(
                prefix()
                        + ChatColor.GREEN
                        + "All "
                        + requiredPluginCount
                        + " required plugins are enabled.");
    }

    private void logFailure(
            Incident incident,
            boolean restartScheduled,
            boolean whitelistEnabled) {

        logger.severe(
                "\n========== STARTUPGUARDIAN CRITICAL FAILURE ==========");
        for (Incident.Failure failure : incident.failures()) {
            String detectedName = failure.detectedName() == null
                    ? ""
                    : " (detected as " + failure.detectedName() + ")";
            logger.severe(
                    "Required plugin "
                            + failure.configuredName()
                            + ": "
                            + failure.status()
                            + detectedName);
        }

        logger.severe(
                "Incident: "
                        + incident.incidentId()
                        + " | restart attempts: "
                        + incident.automaticRestartAttempts()
                        + " | restart scheduled: "
                        + restartScheduled);
        logger.severe(
                "Whitelist enabled: "
                        + whitelistEnabled
                        + " | marker: "
                        + store.path());

        if (incident.restartLoopStopped()) {
            logger.severe(
                    "AUTOMATIC RESTARTS STOPPED: manual intervention is required.");
        }
        logger.severe(
                "=======================================================");
    }

    private String describe(List<PluginHealth> failedPlugins) {
        return String.join(
                ", ",
                failedPlugins.stream().map(
                        health -> health.configuredName()
                                + " ("
                                + health.state().name().toLowerCase(Locale.ROOT)
                                + ")").toList());
    }

    private record ProtectionResult(
            Incident incident,
            boolean continueActions,
            boolean reportIncident) {

        private static ProtectionResult continueWith(Incident incident) {
            return new ProtectionResult(incident, true, false);
        }

        private static ProtectionResult stopAndReport(Incident incident) {
            return new ProtectionResult(incident, false, true);
        }

        private static ProtectionResult stopSilently(Incident incident) {
            return new ProtectionResult(incident, false, false);
        }
    }

    private static String prefix() {
        return ChatColor.GOLD
                + "StartupGuardian "
                + ChatColor.DARK_GRAY
                + "» "
                + ChatColor.RESET;
    }
}
