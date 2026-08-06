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
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

public final class GuardianService {

    private final Plugin plugin;
    private final Logger logger;
    private final IncidentStore store;
    private final WebhookClient webhook;
    private final AtomicBoolean handling = new AtomicBoolean();

    private volatile Settings currentSettings;
    private BukkitTask restartTask;

    public GuardianService(
            Plugin plugin,
            Settings settings,
            IncidentStore store,
            WebhookClient webhook) {

        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.logger = plugin.getLogger();
        this.currentSettings = Objects.requireNonNull(settings, "settings");
        this.store = Objects.requireNonNull(store, "store");
        this.webhook = Objects.requireNonNull(webhook, "webhook");
    }

    public void updateSettings(Settings updatedSettings) {
        currentSettings = Objects.requireNonNull(updatedSettings, "updatedSettings");
    }

    public Settings settings() {
        return currentSettings;
    }

    public List<PluginHealth> health() {
        return PluginHealth.inspect(
                Bukkit.getPluginManager(),
                currentSettings.requiredPlugins());
    }

    public Optional<Incident> incident() {
        return store.load(logger);
    }

    public boolean allowsCriticalBypass(Player player) {
        Objects.requireNonNull(player, "player");
        if (!Bukkit.hasWhitelist() || !store.hasActiveIncident(logger)) {
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
        cancelScheduledRestart();
        try {
            store.clear();
            return true;
        } catch (IOException exception) {
            logger.log(
                    Level.SEVERE,
                    "[StartupGuardian] Could not reset incident.",
                    exception);
            return false;
        }
    }

    public void webhookTest() {
        webhook.test(currentSettings);
    }

    public void close() {
        cancelScheduledRestart();
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

        // Every explicit enforcement is processed. RestartPolicy prevents duplicate
        // pending restarts and enforces the persisted automatic restart cap.
        handleFailure(pluginHealth);
    }

    private void handleFailure(List<PluginHealth> pluginHealth) {
        if (!handling.compareAndSet(false, true)) {
            return;
        }

        try {
            Optional<Incident> existingIncident = store.load(logger);
            boolean previousWhitelist = Bukkit.hasWhitelist();
            boolean enabledWhitelist = currentSettings.protection().whitelist()
                    && !previousWhitelist;

            Incident incident = existingIncident
                    .map(value -> value.observed(pluginHealth))
                    .orElseGet(() -> Incident.create(
                            pluginHealth,
                            previousWhitelist,
                            enabledWhitelist));

            applyProtection();

            RestartPolicy.Decision decision = RestartPolicy.evaluate(
                    incident,
                    currentSettings.loop(),
                    hasScheduledRestart(),
                    store.corrupted());
            incident = decision.incident();

            boolean restartScheduled = decision.scheduleRestart();
            if (!save(incident)) {
                incident = incident.stopLoop();
                restartScheduled = false;
            }

            logFailure(incident, restartScheduled, Bukkit.hasWhitelist());
            webhook.incident(
                    currentSettings,
                    incident,
                    restartScheduled,
                    Bukkit.hasWhitelist());

            if (restartScheduled) {
                scheduleRestart();
            }
        } finally {
            handling.set(false);
        }
    }

    private void applyProtection() {
        if (!currentSettings.protection().whitelist()) {
            return;
        }

        Bukkit.setWhitelist(true);
        if (currentSettings.protection().kickPlayers()) {
            kickPlayers();
        }
    }

    private void kickPlayers() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (currentSettings.protection().kickOps() || !player.isOp()) {
                player.kick(MiniMessage.miniMessage().deserialize(
                        currentSettings.protection().kickMessage()));
            }
        }
    }

    private void scheduleRestart() {
        long delayTicks = currentSettings.protection().restartDelaySeconds() * 20L;
        restartTask = Bukkit.getScheduler().runTaskLater(
                plugin,
                this::dispatchRestart,
                delayTicks);
    }

    private void dispatchRestart() {
        restartTask = null;
        boolean dispatched = Bukkit.dispatchCommand(
                Bukkit.getConsoleSender(),
                currentSettings.protection().restartCommand());

        if (!dispatched) {
            logger.warning(
                    "[StartupGuardian] Restart command was not dispatched; "
                            + "using fallback command.");
            Bukkit.dispatchCommand(
                    Bukkit.getConsoleSender(),
                    currentSettings.protection().fallbackCommand());
        }
    }

    private boolean hasScheduledRestart() {
        return restartTask != null && !restartTask.isCancelled();
    }

    private void cancelScheduledRestart() {
        if (restartTask != null) {
            restartTask.cancel();
            restartTask = null;
        }
    }

    private void recoverIfNeeded() {
        Optional<Incident> activeIncident = store.load(logger);
        if (activeIncident.isEmpty()) {
            return;
        }

        Incident incident = activeIncident.orElseThrow();
        try {
            store.clear();
        } catch (IOException exception) {
            logger.log(
                    Level.SEVERE,
                    "[StartupGuardian] Could not clear recovered incident marker.",
                    exception);
            return;
        }

        cancelScheduledRestart();
        if (currentSettings.protection().restoreWhitelist()
                && incident.guardianEnabledWhitelist()) {
            Bukkit.setWhitelist(incident.previousWhitelistEnabled());
        }

        webhook.recovery(currentSettings, incident);
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
                    "[StartupGuardian] Could not save incident marker; "
                            + "automatic restart suppressed for safety.",
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
