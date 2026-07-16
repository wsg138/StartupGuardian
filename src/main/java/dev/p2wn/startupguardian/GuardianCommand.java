package dev.p2wn.startupguardian;

import org.bukkit.Bukkit;
import org.bukkit.command.*;
import java.util.*;

public final class GuardianCommand implements CommandExecutor, TabCompleter {
    private final StartupGuardianPlugin plugin;
    public GuardianCommand(StartupGuardianPlugin plugin) { this.plugin = plugin; }
    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("startupguardian.admin") && !(sender instanceof ConsoleCommandSender)) { sender.sendMessage("You do not have permission."); return true; }
        String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        GuardianService guardian = plugin.guardian();
        switch (sub) {
            case "status" -> status(sender, guardian);
            case "check" -> guardian.manualCheck(args.length > 1 && args[1].equalsIgnoreCase("--enforce"), sender);
            case "reload" -> { plugin.reloadConfig(); Settings s = SettingsLoader.load(plugin.getConfig()); guardian.settings(s); sender.sendMessage("[StartupGuardian] Configuration reloaded and validated. Webhook configured: " + s.discord().configured()); }
            case "reset" -> { if (args.length < 2 || !args[1].equalsIgnoreCase("confirm")) sender.sendMessage("[StartupGuardian] Confirmation required: /startupguardian reset confirm"); else sender.sendMessage(guardian.reset() ? "[StartupGuardian] Active incident cleared; automatic restart is re-armed. Whitelist was not changed." : "[StartupGuardian] Could not clear the active incident."); }
            case "testdiscord" -> { guardian.webhookTest(); sender.sendMessage("[StartupGuardian] Test webhook queued if Discord is configured."); }
            default -> help(sender);
        }
        return true;
    }
    private void status(CommandSender sender, GuardianService g) { sender.sendMessage("[StartupGuardian] Required plugins: " + g.settings().requiredPlugins()); for (PluginHealth h : g.health()) sender.sendMessage(" - " + h.configuredName() + ": " + h.state() + (h.detectedName() == null ? "" : " (" + h.detectedName() + ")")); Optional<Incident> i = g.incident(); sender.sendMessage("Active incident: " + i.map(Incident::incidentId).orElse("none")); sender.sendMessage("Restart attempts: " + i.map(Incident::automaticRestartAttempts).orElse(0) + " | automatic restart armed: " + i.map(Incident::automaticRestartArmed).orElse(true)); sender.sendMessage("Whitelist: " + Bukkit.hasWhitelist() + " | Discord webhook configured: " + g.settings().discord().configured()); }
    private void help(CommandSender s) { s.sendMessage("[StartupGuardian] /startupguardian status|check [--enforce]|reload|reset confirm|testdiscord|help"); }
    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) { if (args.length == 1) return prefix(args[0], List.of("status", "check", "reload", "reset", "testdiscord", "help")); if (args.length == 2 && args[0].equalsIgnoreCase("check")) return prefix(args[1], List.of("--enforce")); if (args.length == 2 && args[0].equalsIgnoreCase("reset")) return prefix(args[1], List.of("confirm")); return List.of(); }
    private List<String> prefix(String s, List<String> candidates) { return candidates.stream().filter(x -> x.startsWith(s.toLowerCase(Locale.ROOT))).toList(); }
}
