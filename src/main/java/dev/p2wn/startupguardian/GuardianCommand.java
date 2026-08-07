package dev.p2wn.startupguardian;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.TabCompleter;

public final class GuardianCommand implements CommandExecutor, TabCompleter {

    private static final String ADMIN_PERMISSION = "startupguardian.admin";

    private static final List<String> SUBCOMMANDS = List.of(
            "status",
            "check",
            "reload",
            "reset",
            "testdiscord",
            "help");

    private final Supplier<GuardianService> guardianSupplier;

    private final SettingsReloader settingsReloader;

    public GuardianCommand(StartupGuardianPlugin plugin) {
        Objects.requireNonNull(plugin, "plugin");
        guardianSupplier = plugin::guardian;
        settingsReloader = () -> {
            plugin.reloadConfig();
            return SettingsLoader.load(plugin.getConfig());
        };
    }

    @Override
    public boolean onCommand(
            CommandSender sender,
            Command command,
            String label,
            String[] arguments) {

        if (!isAuthorized(sender)) {
            sender.sendMessage(
                    ChatColor.RED
                            + "StartupGuardian "
                            + ChatColor.DARK_GRAY
                            + "» "
                            + ChatColor.GRAY
                            + "You do not have permission.");
            return true;
        }

        String subcommand = arguments.length == 0
                ? "help"
                : arguments[0].toLowerCase(Locale.ROOT);
        executeSubcommand(sender, guardianSupplier.get(), subcommand, arguments);
        return true;
    }

    private void executeSubcommand(
            CommandSender sender,
            GuardianService guardian,
            String subcommand,
            String[] arguments) {

        switch (subcommand) {
            case "status" -> sendStatus(sender, guardian);
            case "check" -> guardian.manualCheck(
                    arguments.length > 1
                            && "--enforce".equalsIgnoreCase(arguments[1]),
                    sender);
            case "reload" -> reload(sender, guardian);
            case "reset" -> reset(sender, guardian, arguments);
            case "testdiscord" -> testDiscord(sender, guardian);
            case "help" -> sendHelp(sender);
            default -> {
                sender.sendMessage(
                        prefix()
                                + ChatColor.RED
                                + "Unknown subcommand: "
                                + ChatColor.WHITE
                                + subcommand);
                sendHelp(sender);
            }
        }
    }

    private boolean isAuthorized(CommandSender sender) {
        return sender instanceof ConsoleCommandSender
                || sender.hasPermission(ADMIN_PERMISSION);
    }

    private void reload(CommandSender sender, GuardianService guardian) {
        try {
            Settings updatedSettings = settingsReloader.reload();
            guardian.updateSettings(updatedSettings);
            sender.sendMessage(
                    prefix()
                            + ChatColor.GREEN
                            + "Configuration reloaded"
                            + ChatColor.GRAY
                            + " • Discord webhook: "
                            + state(updatedSettings.discord().configured()));
        } catch (IllegalArgumentException exception) {
            sender.sendMessage(
                    prefix()
                            + ChatColor.RED
                            + "Configuration rejected: "
                            + ChatColor.WHITE
                            + exception.getMessage()
                            + ChatColor.GRAY
                            + " • Previous settings remain active.");
        }
    }

    private void reset(
            CommandSender sender,
            GuardianService guardian,
            String[] arguments) {

        if (arguments.length < 2
                || !"confirm".equalsIgnoreCase(arguments[1])) {
            sender.sendMessage(
                    prefix()
                            + ChatColor.YELLOW
                            + "Confirmation required"
                            + ChatColor.GRAY
                            + ": /startupguardian reset confirm");
            return;
        }

        if (guardian.reset()) {
            sender.sendMessage(
                    prefix()
                            + ChatColor.GREEN
                            + "Incident cleared"
                            + ChatColor.GRAY
                            + " • Pending restart cancelled"
                            + " • Whitelist unchanged");
        } else {
            sender.sendMessage(
                    prefix()
                            + ChatColor.RED
                            + "Could not clear the active incident"
                            + ChatColor.GRAY
                            + " • Pending restart unchanged.");
        }
    }

    private void testDiscord(
            CommandSender sender,
            GuardianService guardian) {

        WebhookTestResult result = guardian.webhookTest();
        switch (result) {
            case QUEUED -> sender.sendMessage(
                    prefix()
                            + ChatColor.AQUA
                            + "Discord test queued"
                            + ChatColor.GRAY
                            + " • No protection action was taken.");
            case NOT_CONFIGURED -> sender.sendMessage(
                    prefix()
                            + ChatColor.YELLOW
                            + "Discord webhook is not configured"
                            + ChatColor.GRAY
                            + " • Enable Discord and set a webhook URL.");
            case CLOSED -> sender.sendMessage(
                    prefix()
                            + ChatColor.RED
                            + "Discord webhook client is closed"
                            + ChatColor.GRAY
                            + " • The test was not queued.");
            default -> throw new IllegalStateException(
                    "Unexpected webhook test result: " + result);
        }
    }

    private void sendStatus(
            CommandSender sender,
            GuardianService guardian) {

        List<PluginHealth> health = guardian.health();
        long healthyCount = health.stream().filter(
                PluginHealth::healthy).count();

        sender.sendMessage(
                ChatColor.DARK_GRAY
                        + "━━━━━━━━━━ "
                        + ChatColor.GOLD
                        + "StartupGuardian Status "
                        + ChatColor.DARK_GRAY
                        + "━━━━━━━━━━");
        sender.sendMessage(
                ChatColor.GRAY
                        + "Required plugins: "
                        + ChatColor.AQUA
                        + healthyCount
                        + ChatColor.GRAY
                        + "/"
                        + ChatColor.AQUA
                        + health.size()
                        + ChatColor.GRAY
                        + " healthy");

        sendPluginHealth(sender, health);
        sendIncidentStatus(sender, guardian.incident());
        sender.sendMessage(
                ChatColor.GRAY
                        + "Whitelist: "
                        + state(Bukkit.hasWhitelist())
                        + ChatColor.DARK_GRAY
                        + " | "
                        + ChatColor.GRAY
                        + "Discord: "
                        + state(guardian.settings().discord().configured()));
        sender.sendMessage(
                ChatColor.DARK_GRAY
                        + "━━━━━━━━━━━━━━━━━━━━━━"
                        + "━━━━━━━━━━━━━━━━━━━━━━");
    }

    private void sendPluginHealth(
            CommandSender sender,
            List<PluginHealth> health) {

        for (PluginHealth pluginHealth : health) {
            ChatColor color = pluginHealth.healthy()
                    ? ChatColor.GREEN
                    : ChatColor.RED;
            String detectedName = pluginHealth.detectedName() == null
                    ? ""
                    : ChatColor.GRAY
                            + " ("
                            + pluginHealth.detectedName()
                            + ")";

            sender.sendMessage(
                    color
                            + "  "
                            + (pluginHealth.healthy() ? "✔ " : "✖ ")
                            + ChatColor.WHITE
                            + pluginHealth.configuredName()
                            + ChatColor.DARK_GRAY
                            + " — "
                            + color
                            + pluginHealth.state().name().toLowerCase(Locale.ROOT)
                            + detectedName);
        }
    }

    private void sendIncidentStatus(
            CommandSender sender,
            Optional<Incident> incident) {

        String incidentId = incident.map(
                value -> ChatColor.RED + value.incidentId()).orElse(
                        ChatColor.GREEN + "none");
        int restartAttempts = incident.map(
                Incident::automaticRestartAttempts).orElse(0);
        boolean restartArmed = incident.map(
                Incident::automaticRestartArmed).orElse(true);

        sender.sendMessage(
                ChatColor.GRAY
                        + "Incident: "
                        + incidentId);
        sender.sendMessage(
                ChatColor.GRAY
                        + "Restart attempts: "
                        + ChatColor.YELLOW
                        + restartAttempts
                        + ChatColor.DARK_GRAY
                        + " | "
                        + ChatColor.GRAY
                        + "Restart armed: "
                        + state(restartArmed));
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(
                ChatColor.DARK_GRAY
                        + "━━━━━━━━━━ "
                        + ChatColor.GOLD
                        + "StartupGuardian Commands "
                        + ChatColor.DARK_GRAY
                        + "━━━━━━━━━━");
        sender.sendMessage(
                ChatColor.YELLOW
                        + "/startupguardian status"
                        + ChatColor.GRAY
                        + " — current protection and plugin health");
        sender.sendMessage(
                ChatColor.YELLOW
                        + "/startupguardian check [--enforce]"
                        + ChatColor.GRAY
                        + " — inspect or enforce failures");
        sender.sendMessage(
                ChatColor.YELLOW
                        + "/startupguardian reload"
                        + ChatColor.GRAY
                        + " — validate and reload configuration");
        sender.sendMessage(
                ChatColor.YELLOW
                        + "/startupguardian reset confirm"
                        + ChatColor.GRAY
                        + " — clear the incident and cancel pending restart");
        sender.sendMessage(
                ChatColor.YELLOW
                        + "/startupguardian testdiscord"
                        + ChatColor.GRAY
                        + " — test webhook configuration and queue state");
    }

    private static String prefix() {
        return ChatColor.GOLD
                + "StartupGuardian "
                + ChatColor.DARK_GRAY
                + "» "
                + ChatColor.RESET;
    }

    private static String state(boolean value) {
        return value
                ? ChatColor.GREEN + "enabled"
                : ChatColor.RED + "disabled";
    }

    @Override
    public List<String> onTabComplete(
            CommandSender sender,
            Command command,
            String alias,
            String[] arguments) {

        if (!isAuthorized(sender)) {
            return List.of();
        }
        if (arguments.length == 1) {
            return matchesPrefix(arguments[0], SUBCOMMANDS);
        }
        if (arguments.length == 2
                && "check".equalsIgnoreCase(arguments[0])) {
            return matchesPrefix(arguments[1], List.of("--enforce"));
        }
        if (arguments.length == 2
                && "reset".equalsIgnoreCase(arguments[0])) {
            return matchesPrefix(arguments[1], List.of("confirm"));
        }
        return List.of();
    }

    private List<String> matchesPrefix(
            String input,
            List<String> candidates) {

        String normalizedInput = input.toLowerCase(Locale.ROOT);
        return candidates.stream().filter(
                candidate -> candidate.startsWith(normalizedInput)).toList();
    }

    @FunctionalInterface
    private interface SettingsReloader {

        Settings reload();
    }
}
