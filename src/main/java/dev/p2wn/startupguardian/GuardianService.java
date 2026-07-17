package dev.p2wn.startupguardian;

import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.*;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

public final class GuardianService {
    private final Plugin plugin; private final Logger log; private final IncidentStore store; private final WebhookClient webhook; private Settings settings; private final AtomicBoolean handling = new AtomicBoolean(); private final AtomicBoolean failureProcessed = new AtomicBoolean();
    public GuardianService(Plugin plugin, Settings settings, IncidentStore store, WebhookClient webhook) { this.plugin = plugin; this.log = plugin.getLogger(); this.settings = settings; this.store = store; this.webhook = webhook; }
    public void settings(Settings settings) { this.settings = settings; }
    public Settings settings() { return settings; }
    public List<PluginHealth> health() { return PluginHealth.inspect(Bukkit.getPluginManager(), settings.requiredPlugins()); }
    public Optional<Incident> incident() { return store.load(log); }
    public boolean allowsCriticalBypass(Player player) {
        if (!Bukkit.hasWhitelist() || store.load(log).isEmpty()) return false;
        Settings.Bypass bypass = settings.bypass();
        if (bypass.playerUuids().contains(player.getUniqueId())) return true;
        if (player.isOp() && !bypass.allowOps()) return false;
        return bypass.allowOps() && player.isOp() || (!bypass.permission().isBlank() && player.hasPermission(bypass.permission()));
    }
    public void startupCheck() { check(true, null); }
    public void manualCheck(boolean enforce, CommandSender sender) { check(enforce, sender); }
    private void check(boolean enforce, CommandSender sender) {
        List<PluginHealth> health = health(); List<PluginHealth> failed = health.stream().filter(h -> !h.healthy()).toList();
        if (failed.isEmpty()) { recoverIfNeeded(); if (sender != null) sender.sendMessage(ChatColor.GOLD + "StartupGuardian " + ChatColor.DARK_GRAY + "» " + ChatColor.GREEN + "All " + health.size() + " required plugins are enabled."); else log.info("[StartupGuardian] All " + health.size() + " required plugins are enabled."); return; }
        if (sender != null && !enforce) { sender.sendMessage(ChatColor.GOLD + "StartupGuardian " + ChatColor.DARK_GRAY + "» " + ChatColor.RED + "Failed plugins: " + ChatColor.WHITE + describe(failed) + ChatColor.GRAY + " • Use " + ChatColor.YELLOW + "--enforce" + ChatColor.GRAY + " to enter protection mode."); return; }
        handleFailure(health);
    }
    private void handleFailure(List<PluginHealth> health) {
        if (!failureProcessed.compareAndSet(false, true) || !handling.compareAndSet(false, true)) return;
        try {
            Optional<Incident> existing = store.load(log); boolean previous = Bukkit.hasWhitelist(); boolean enabledByUs = settings.protection().whitelist() && !previous;
            Incident incident = existing.map(i -> i.observed(health)).orElseGet(() -> Incident.create(health, previous, enabledByUs));
            if (settings.protection().whitelist()) { Bukkit.setWhitelist(true); if (settings.protection().kickPlayers()) kickPlayers(); }
            boolean restart = incident.automaticRestartArmed() && (!settings.loop().enabled() || incident.automaticRestartAttempts() < settings.loop().maximumRestarts());
            if (restart) incident = incident.withRestartScheduled(); else if (settings.loop().enabled()) incident = incident.stopLoop();
            if (store.corrupted()) { incident = incident.stopLoop(); restart = false; }
            if (!save(incident)) { incident = incident.stopLoop(); restart = false; }
            logFailure(incident, restart, Bukkit.hasWhitelist()); webhook.incident(settings, incident, restart, Bukkit.hasWhitelist());
            if (restart) { Incident finalIncident = incident; Bukkit.getScheduler().runTaskLater(plugin, () -> dispatchRestart(finalIncident), settings.protection().restartDelaySeconds() * 20L); }
        } finally { handling.set(false); }
    }
    private void kickPlayers() { for (Player p : Bukkit.getOnlinePlayers()) if (settings.protection().kickOps() || !p.isOp()) p.kick(MiniMessage.miniMessage().deserialize(settings.protection().kickMessage())); }
    private void dispatchRestart(Incident incident) { boolean done = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), settings.protection().restartCommand()); if (!done) { log.warning("[StartupGuardian] Restart command was not dispatched; using fallback command."); Bukkit.dispatchCommand(Bukkit.getConsoleSender(), settings.protection().fallbackCommand()); } }
    private void recoverIfNeeded() { Optional<Incident> active = store.load(log); if (active.isEmpty()) return; Incident i = active.get(); webhook.recovery(settings, i); if (settings.protection().restoreWhitelist() && i.guardianEnabledWhitelist()) Bukkit.setWhitelist(i.previousWhitelistEnabled()); try { store.clear(); } catch (IOException ex) { log.severe("[StartupGuardian] Could not clear recovered incident marker: " + ex.getMessage()); return; } log.warning("[StartupGuardian] Recovery detected for incident " + i.incidentId() + ". Incident marker cleared."); }
    public boolean reset() { try { store.clear(); failureProcessed.set(false); return true; } catch (IOException ex) { log.severe("[StartupGuardian] Could not reset incident: " + ex.getMessage()); return false; } }
    public void webhookTest() { webhook.test(settings); }
    private boolean save(Incident i) { try { store.save(i); return true; } catch (IOException ex) { log.severe("[StartupGuardian] Could not save incident marker; automatic restart suppressed for safety: " + ex.getMessage()); return false; } }
    private void logFailure(Incident i, boolean restart, boolean whitelist) { log.severe("\n========== STARTUPGUARDIAN CRITICAL FAILURE =========="); i.failures().forEach(f -> log.severe("Required plugin " + f.configuredName() + ": " + f.status() + (f.detectedName() == null ? "" : " (detected as " + f.detectedName() + ")"))); log.severe("Incident: " + i.incidentId() + " | restart attempts: " + i.automaticRestartAttempts() + " | restart scheduled: " + restart); log.severe("Whitelist enabled: " + whitelist + " | marker: " + store.path()); if (i.restartLoopStopped()) log.severe("AUTOMATIC RESTARTS STOPPED: manual intervention is required."); log.severe("======================================================="); }
    private String describe(List<PluginHealth> failed) { return String.join(", ", failed.stream().map(h -> h.configuredName() + " (" + h.state().name().toLowerCase() + ")").toList()); }
}
