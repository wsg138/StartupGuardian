package dev.p2wn.startupguardian;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.*;
import java.util.*;

public final class GuardianCommand implements CommandExecutor, TabCompleter {
    private final StartupGuardianPlugin plugin;
    public GuardianCommand(StartupGuardianPlugin plugin) { this.plugin = plugin; }
    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("startupguardian.admin") && !(sender instanceof ConsoleCommandSender)) { sender.sendMessage(ChatColor.RED + "StartupGuardian " + ChatColor.DARK_GRAY + "» " + ChatColor.GRAY + "You do not have permission."); return true; }
        String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        GuardianService guardian = plugin.guardian();
        switch (sub) {
            case "status" -> status(sender, guardian);
            case "check" -> guardian.manualCheck(args.length > 1 && args[1].equalsIgnoreCase("--enforce"), sender);
            case "reload" -> { plugin.reloadConfig(); Settings s = SettingsLoader.load(plugin.getConfig()); guardian.settings(s); sender.sendMessage(prefix() + ChatColor.GREEN + "Configuration reloaded" + ChatColor.GRAY + " • Discord webhook: " + state(s.discord().configured())); }
            case "reset" -> { if (args.length < 2 || !args[1].equalsIgnoreCase("confirm")) sender.sendMessage(prefix() + ChatColor.YELLOW + "Confirmation required" + ChatColor.GRAY + ": /startupguardian reset confirm"); else sender.sendMessage(guardian.reset() ? prefix() + ChatColor.GREEN + "Incident cleared" + ChatColor.GRAY + " • Automatic restart re-armed • Whitelist unchanged" : prefix() + ChatColor.RED + "Could not clear the active incident."); }
            case "testdiscord" -> { guardian.webhookTest(); sender.sendMessage(prefix() + ChatColor.AQUA + "Discord test queued" + ChatColor.GRAY + " • No protection action was taken."); }
            default -> help(sender);
        }
        return true;
    }
    private void status(CommandSender sender, GuardianService g) { List<PluginHealth> health = g.health(); long healthy = health.stream().filter(PluginHealth::healthy).count(); sender.sendMessage(ChatColor.DARK_GRAY + "━━━━━━━━━━ " + ChatColor.GOLD + "StartupGuardian Status " + ChatColor.DARK_GRAY + "━━━━━━━━━━"); sender.sendMessage(ChatColor.GRAY + "Required plugins: " + ChatColor.AQUA + healthy + ChatColor.GRAY + "/" + ChatColor.AQUA + health.size() + ChatColor.GRAY + " healthy"); for (PluginHealth h : health) { ChatColor color = h.healthy() ? ChatColor.GREEN : ChatColor.RED; sender.sendMessage(color + "  " + (h.healthy() ? "✔ " : "✖ ") + ChatColor.WHITE + h.configuredName() + ChatColor.DARK_GRAY + " — " + color + h.state().name().toLowerCase(Locale.ROOT) + (h.detectedName() == null ? "" : ChatColor.GRAY + " (" + h.detectedName() + ")")); } Optional<Incident> i = g.incident(); sender.sendMessage(ChatColor.GRAY + "Incident: " + (i.isPresent() ? ChatColor.RED + i.get().incidentId() : ChatColor.GREEN + "none")); sender.sendMessage(ChatColor.GRAY + "Restart attempts: " + ChatColor.YELLOW + i.map(Incident::automaticRestartAttempts).orElse(0) + ChatColor.DARK_GRAY + " | " + ChatColor.GRAY + "Restart armed: " + state(i.map(Incident::automaticRestartArmed).orElse(true))); sender.sendMessage(ChatColor.GRAY + "Whitelist: " + state(Bukkit.hasWhitelist()) + ChatColor.DARK_GRAY + " | " + ChatColor.GRAY + "Discord: " + state(g.settings().discord().configured())); sender.sendMessage(ChatColor.DARK_GRAY + "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"); }
    private void help(CommandSender s) { s.sendMessage(ChatColor.DARK_GRAY + "━━━━━━━━━━ " + ChatColor.GOLD + "StartupGuardian Commands " + ChatColor.DARK_GRAY + "━━━━━━━━━━"); s.sendMessage(ChatColor.YELLOW + "/startupguardian status" + ChatColor.GRAY + " — current protection and plugin health"); s.sendMessage(ChatColor.YELLOW + "/startupguardian check [--enforce]" + ChatColor.GRAY + " — inspect or enforce failures"); s.sendMessage(ChatColor.YELLOW + "/startupguardian reload" + ChatColor.GRAY + " — reload configuration"); s.sendMessage(ChatColor.YELLOW + "/startupguardian reset confirm" + ChatColor.GRAY + " — clear the active incident"); s.sendMessage(ChatColor.YELLOW + "/startupguardian testdiscord" + ChatColor.GRAY + " — send a safe webhook test"); }
    private static String prefix() { return ChatColor.GOLD + "StartupGuardian " + ChatColor.DARK_GRAY + "» " + ChatColor.RESET; }
    private static String state(boolean value) { return value ? ChatColor.GREEN + "enabled" : ChatColor.RED + "disabled"; }
    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) { if (args.length == 1) return prefix(args[0], List.of("status", "check", "reload", "reset", "testdiscord", "help")); if (args.length == 2 && args[0].equalsIgnoreCase("check")) return prefix(args[1], List.of("--enforce")); if (args.length == 2 && args[0].equalsIgnoreCase("reset")) return prefix(args[1], List.of("confirm")); return List.of(); }
    private List<String> prefix(String s, List<String> candidates) { return candidates.stream().filter(x -> x.startsWith(s.toLowerCase(Locale.ROOT))).toList(); }
}
