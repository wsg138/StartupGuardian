package dev.p2wn.startupguardian;

import org.bukkit.configuration.file.FileConfiguration;
import java.util.*;

public final class SettingsLoader {
    private SettingsLoader() {}
    public static Settings load(FileConfiguration c) {
        List<String> plugins = c.getStringList("required-plugins").stream().map(String::trim).filter(s -> !s.isEmpty()).distinct().toList();
        int grace = bounded(c.getInt("startup-check.grace-period-ticks", 20), 0, 20 * 60, 20);
        Settings.Protection p = new Settings.Protection(c.getBoolean("protection.enable-whitelist", true), c.getBoolean("protection.kick-online-players", true), c.getBoolean("protection.emergency-kick-ops", false), text(c, "protection.kick-message", "The server entered emergency maintenance."), bounded(c.getInt("protection.restart-delay-seconds", 8), 0, 300, 8), command(c, "protection.restart-command", "restart"), command(c, "protection.fallback-command", "stop"), c.getBoolean("protection.restore-previous-whitelist-state-after-recovery", true));
        Settings.Loop loop = new Settings.Loop(c.getBoolean("restart-loop-protection.enabled", true), bounded(c.getInt("restart-loop-protection.maximum-automatic-restarts", 1), 0, 10, 1), bounded(c.getInt("restart-loop-protection.attempt-window-minutes", 15), 1, 1440, 15));
        List<String> roles = cleanIds(c.getStringList("discord.staff-mentions.role-ids"));
        List<String> users = cleanIds(c.getStringList("discord.staff-mentions.user-ids"));
        Settings.Discord d = new Settings.Discord(c.getBoolean("discord.enabled", true), text(c, "discord.webhook-url", ""), roles, users, bounded(c.getInt("discord.repeated-alerts", 3), 1, 10, 3), bounded(c.getInt("discord.delay-between-alerts-milliseconds", 1500), 0, 60000, 1500), text(c, "discord.username", "Startup Guardian"), text(c, "discord.avatar-url", ""));
        Settings.Bypass bypass = new Settings.Bypass(cleanUuids(c.getStringList("critical-mode-bypass.player-uuids")), text(c, "critical-mode-bypass.permission", "startupguardian.bypass"), c.getBoolean("critical-mode-bypass.allow-ops", false));
        return new Settings(plugins, grace, p, loop, d, bypass, new Settings.Messages(text(c, "messages.incident-title", "CRITICAL SERVER STARTUP FAILURE"), text(c, "messages.recovery-title", "Server startup recovered")));
    }
    private static int bounded(int value, int min, int max, int fallback) { return value < min || value > max ? fallback : value; }
    private static String text(FileConfiguration c, String path, String fallback) { String s = c.getString(path, fallback); return s == null ? fallback : s.trim(); }
    private static String command(FileConfiguration c, String path, String fallback) { String s = text(c, path, fallback).replaceFirst("^/", ""); return s.isBlank() ? fallback : s; }
    private static List<String> cleanIds(List<String> ids) { return ids.stream().map(String::trim).filter(s -> s.matches("[0-9]{5,30}")).distinct().toList(); }
    private static List<java.util.UUID> cleanUuids(List<String> values) { return values.stream().map(String::trim).map(value -> { try { return java.util.UUID.fromString(value); } catch (IllegalArgumentException ignored) { return null; } }).filter(java.util.Objects::nonNull).distinct().toList(); }
}
