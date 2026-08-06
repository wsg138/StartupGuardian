package dev.p2wn.startupguardian;

import java.io.IOException;
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
        return store.load();
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
        List<PluginHealth> failedPlugins = pluginHealth.stream()
                .filter(health -> !health.healthy())
                .toList();

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
            boolean enabledWhitelist = currentSettings.protection().whitelist()
                    && !previousWhitelist;

            Incident incident = existingIncident
                    .map(value -> value.observed(pluginHealth))
                    .orElseGet(() -> Incident.create(
                            pluginHealth,
                            previousWhitelist,
                            enabledWhitelist));

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

            applyProtectionSafely();
            boolean whitelistEnabled = environment.whitelistEnabled();
            boolean restartScheduled = decision.scheduleRestart();

            logFailure(persistedIncident, restartScheduled, whitelistEnabled);
            notifier.incident(
                    currentSettings,
                    persistedIncident,
                    restartScheduled,
                    whitelistEnabled);

            if (restartScheduled) {
                scheduleRestart();
            }
        } finally {
            handling.set(false);
        }
    }

    private void applyProtectionSafely() {
        try {
            applyProtection();
        } catch (RuntimeException exception) {
            logger.log(
                    Level.SEVERE,
                    "[StartupGuardian] Incident was persisted, but emergency protection "
                            + "could not be fully applied. The marker was retained.",
                    exception);
        }
    }

    private void applyProtection() {
        if (!currentSettings.protection().whitelist()) {
            return;
        }

        environment.setWhitelist(true);
        if (!currentSettings.protection().kickPlayers()) {
            return;
        }

        for (GuardianEnvironment.OnlinePlayer player : environment.onlinePlayers()) {
            if (currentSettings.protection().kickOps() || !player.operator()) {
                player.kick(currentSettings.protection().kickMessage());
            }
        }
    }

    private void scheduleRestart() {
        long delayTicks = currentSettings.protection().restartDelaySeconds() * 20L;
        try {
            GuardianEnvironment.RestartTask task = environment.scheduleRestart(
                    delayTicks,
                    this::dispatchRestart);
            restartTask = Optional.of(task);
        } catch (RuntimeException exception) {
            logger.log(
                    Level.SEVERE,
                    "[StartupGuardian] Incident was persisted, but the restart task "
                            + "could not be scheduled.",
                    exception);
        }
    }

    private void dispatchRestart() {
        restartTask = Optional.empty();
        boolean dispatched = environment.dispatchCommand(
                currentSettings.protection().restartCommand());

        if (!dispatched) {
            logger.warning(
                    "[StartupGuardian] Restart command was not dispatched; "
                            + "using fallback command.");
            environment.dispatchCommand(currentSettings.protection().fallbackCommand());
        }
    }

    private boolean hasScheduledRestart() {
        return restartTask
                .filter(task -> !task.cancelled())
                .isPresent();
    }

    private void cancelScheduledRestart() {
        restartTask.ifPresent(GuardianEnvironment.RestartTask::cancel);
        restartTask = Optional.empty();
    }

    private void recoverIfNeeded() {
        Optional<Incident> activeIncident = store.load();
        if (activeIncident.isEmpty()) {
            return;
        }

        Incident incident = activeIncident.orElseThrow();
        try {
            store.clear();
        } catch (IOException exception) {
            logger.log(
                    Level.SEVERE,
                    "[StartupGuardian] Could not clear recovered incident marker. "
                            + "Recovery actions were not applied.",
                    exception);
            return;
        }

        cancelScheduledRestart();
        if (currentSettings.protection().restoreWhitelist()
                && incident.guardianEnabledWhitelist()) {
            environment.setWhitelist(incident.previousWhitelistEnabled());
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
                            + "Whitelist changes, player kicks, and new restart scheduling "
                            + "were skipped for safety.",
                    exception);
            return false;
        }
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
                        + " | restart planned: "
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
                failedPlugins.stream()
                        .map(health -> health.configuredName()
                                + " ("
                                + health.state().name().toLowerCase(Locale.ROOT)
                                + ")")
                        .toList());
    }

    private static String prefix() {
        return ChatColor.GOLD
                + "StartupGuardian "
                + ChatColor.DARK_GRAY
                + "» "
                + ChatColor.RESET;
    }
}
