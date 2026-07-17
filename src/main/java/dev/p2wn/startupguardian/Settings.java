package dev.p2wn.startupguardian;

import java.util.List;

public record Settings(List<String> requiredPlugins, int graceTicks, Protection protection, Loop loop, Discord discord, Bypass bypass, Messages messages) {
    public record Protection(boolean whitelist, boolean kickPlayers, boolean kickOps, String kickMessage, int restartDelaySeconds, String restartCommand, String fallbackCommand, boolean restoreWhitelist) {}
    public record Loop(boolean enabled, int maximumRestarts, int windowMinutes) {}
    public record Discord(boolean enabled, String webhookUrl, List<String> roleIds, List<String> userIds, int repeats, int delayMillis, String username, String avatarUrl) {
        public boolean configured() { return enabled && !webhookUrl.isBlank(); }
    }
    public record Bypass(List<java.util.UUID> playerUuids, String permission, boolean allowOps) {}
    public record Messages(String incidentTitle, String recoveryTitle) {}
}
