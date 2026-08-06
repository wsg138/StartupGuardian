package dev.p2wn.startupguardian;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record Settings(
        List<String> requiredPlugins,
        int graceTicks,
        Protection protection,
        Loop loop,
        Discord discord,
        Bypass bypass,
        Messages messages) {

    public Settings {
        requiredPlugins = List.copyOf(Objects.requireNonNull(requiredPlugins, "requiredPlugins"));
        if (requiredPlugins.isEmpty()) {
            throw new IllegalArgumentException("requiredPlugins must not be empty");
        }
        if (graceTicks < 0) {
            throw new IllegalArgumentException("graceTicks must not be negative");
        }
        Objects.requireNonNull(protection, "protection");
        Objects.requireNonNull(loop, "loop");
        Objects.requireNonNull(discord, "discord");
        Objects.requireNonNull(bypass, "bypass");
        Objects.requireNonNull(messages, "messages");
    }

    public record Protection(
            boolean whitelist,
            boolean kickPlayers,
            boolean kickOps,
            String kickMessage,
            int restartDelaySeconds,
            String restartCommand,
            String fallbackCommand,
            boolean restoreWhitelist) {

        public Protection {
            kickMessage = requireText(kickMessage, "kickMessage");
            restartCommand = requireText(restartCommand, "restartCommand");
            fallbackCommand = requireText(fallbackCommand, "fallbackCommand");
            if (restartDelaySeconds < 0) {
                throw new IllegalArgumentException("restartDelaySeconds must not be negative");
            }
        }
    }

    public record Loop(boolean enabled, int maximumRestarts) {

        public Loop {
            if (maximumRestarts < 0) {
                throw new IllegalArgumentException("maximumRestarts must not be negative");
            }
        }
    }

    public record Discord(
            boolean enabled,
            String webhookUrl,
            List<String> roleIds,
            List<String> userIds,
            int repeats,
            int delayMillis,
            String username,
            String avatarUrl) {

        public Discord {
            webhookUrl = Objects.requireNonNull(webhookUrl, "webhookUrl");
            roleIds = List.copyOf(Objects.requireNonNull(roleIds, "roleIds"));
            userIds = List.copyOf(Objects.requireNonNull(userIds, "userIds"));
            username = requireText(username, "username");
            avatarUrl = Objects.requireNonNull(avatarUrl, "avatarUrl");
            if (repeats < 1) {
                throw new IllegalArgumentException("repeats must be at least one");
            }
            if (delayMillis < 0) {
                throw new IllegalArgumentException("delayMillis must not be negative");
            }
        }

        public boolean configured() {
            return enabled && !webhookUrl.isBlank();
        }
    }

    public record Bypass(List<UUID> playerUuids, String permission, boolean allowOps) {

        public Bypass {
            playerUuids = List.copyOf(Objects.requireNonNull(playerUuids, "playerUuids"));
            permission = Objects.requireNonNull(permission, "permission");
        }
    }

    public record Messages(String incidentTitle, String recoveryTitle) {

        public Messages {
            incidentTitle = requireText(incidentTitle, "incidentTitle");
            recoveryTitle = requireText(recoveryTitle, "recoveryTitle");
        }
    }

    private static String requireText(String value, String name) {
        String checked = Objects.requireNonNull(value, name).trim();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return checked;
    }
}
