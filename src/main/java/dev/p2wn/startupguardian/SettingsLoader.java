package dev.p2wn.startupguardian;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.bukkit.configuration.file.FileConfiguration;

public final class SettingsLoader {

    private static final int DEFAULT_GRACE_TICKS = 20;
    private static final int DEFAULT_RESTART_DELAY_SECONDS = 8;
    private static final int DEFAULT_RESTARTS = 1;
    private static final int DEFAULT_ALERT_REPEATS = 3;
    private static final int DEFAULT_ALERT_DELAY_MILLIS = 1_500;

    private SettingsLoader() {
    }

    public static Settings load(FileConfiguration config) {
        Objects.requireNonNull(config, "config");

        List<String> plugins = requiredPlugins(config.getStringList("required-plugins"));
        int graceTicks = bounded(
                config.getInt("startup-check.grace-period-ticks", DEFAULT_GRACE_TICKS),
                0,
                20 * 60,
                DEFAULT_GRACE_TICKS);

        Settings.Protection protection = new Settings.Protection(
                config.getBoolean("protection.enable-whitelist", true),
                config.getBoolean("protection.kick-online-players", true),
                config.getBoolean("protection.emergency-kick-ops", false),
                text(config, "protection.kick-message", "The server entered emergency maintenance."),
                bounded(
                        config.getInt(
                                "protection.restart-delay-seconds",
                                DEFAULT_RESTART_DELAY_SECONDS),
                        0,
                        300,
                        DEFAULT_RESTART_DELAY_SECONDS),
                command(config, "protection.restart-command", "restart"),
                command(config, "protection.fallback-command", "stop"),
                config.getBoolean(
                        "protection.restore-previous-whitelist-state-after-recovery",
                        true));

        Settings.Loop loop = new Settings.Loop(
                config.getBoolean("restart-loop-protection.enabled", true),
                bounded(
                        config.getInt(
                                "restart-loop-protection.maximum-automatic-restarts",
                                DEFAULT_RESTARTS),
                        0,
                        10,
                        DEFAULT_RESTARTS));

        Settings.Discord discord = new Settings.Discord(
                config.getBoolean("discord.enabled", true),
                text(config, "discord.webhook-url", ""),
                cleanIds(config.getStringList("discord.staff-mentions.role-ids")),
                cleanIds(config.getStringList("discord.staff-mentions.user-ids")),
                bounded(
                        config.getInt("discord.repeated-alerts", DEFAULT_ALERT_REPEATS),
                        1,
                        10,
                        DEFAULT_ALERT_REPEATS),
                bounded(
                        config.getInt(
                                "discord.delay-between-alerts-milliseconds",
                                DEFAULT_ALERT_DELAY_MILLIS),
                        0,
                        60_000,
                        DEFAULT_ALERT_DELAY_MILLIS),
                text(config, "discord.username", "Startup Guardian"),
                text(config, "discord.avatar-url", ""));

        Settings.Bypass bypass = new Settings.Bypass(
                cleanUuids(config.getStringList("critical-mode-bypass.player-uuids")),
                text(config, "critical-mode-bypass.permission", "startupguardian.bypass"),
                config.getBoolean("critical-mode-bypass.allow-ops", false));

        Settings.Messages messages = new Settings.Messages(
                text(config, "messages.incident-title", "CRITICAL SERVER STARTUP FAILURE"),
                text(config, "messages.recovery-title", "Server startup recovered"));

        return new Settings(
                plugins,
                graceTicks,
                protection,
                loop,
                discord,
                bypass,
                messages);
    }

    private static List<String> requiredPlugins(List<String> configuredNames) {
        Map<String, String> uniqueNames = new LinkedHashMap<>();
        for (String configuredName : configuredNames) {
            String name = configuredName.trim();
            if (!name.isEmpty()) {
                uniqueNames.putIfAbsent(name.toLowerCase(Locale.ROOT), name);
            }
        }

        if (uniqueNames.isEmpty()) {
            throw new IllegalArgumentException(
                    "required-plugins must contain at least one plugin name");
        }
        return List.copyOf(uniqueNames.values());
    }

    private static int bounded(int value, int minimum, int maximum, int fallback) {
        if (value < minimum || value > maximum) {
            return fallback;
        }
        return value;
    }

    private static String text(
            FileConfiguration config,
            String path,
            String fallback) {

        String value = config.getString(path, fallback);
        return value == null ? fallback : value.trim();
    }

    private static String command(
            FileConfiguration config,
            String path,
            String fallback) {

        String value = text(config, path, fallback).replaceFirst("^/", "");
        return value.isBlank() ? fallback : value;
    }

    private static List<String> cleanIds(List<String> ids) {
        return ids.stream()
                .map(String::trim)
                .filter(value -> value.matches("[0-9]{5,30}"))
                .distinct()
                .toList();
    }

    private static List<UUID> cleanUuids(List<String> values) {
        List<UUID> uuids = new ArrayList<>();
        for (String value : values) {
            try {
                UUID uuid = UUID.fromString(value.trim());
                if (!uuids.contains(uuid)) {
                    uuids.add(uuid);
                }
            } catch (IllegalArgumentException ignored) {
                // Invalid configured UUIDs are discarded.
            }
        }
        return List.copyOf(uuids);
    }
}
