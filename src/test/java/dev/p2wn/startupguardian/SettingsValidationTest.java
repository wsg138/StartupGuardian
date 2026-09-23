package dev.p2wn.startupguardian;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SettingsValidationTest {

    @Test
    void rootSettingsDefensivelyCopyRequiredPlugins() {
        List<String> plugins = new ArrayList<>(List.of("Vault", "Plan"));
        Settings settings = settings(plugins, 20);

        plugins.add("CoreProtect");

        assertEquals(List.of("Vault", "Plan"), settings.requiredPlugins());
        assertThrows(UnsupportedOperationException.class,
                () -> settings.requiredPlugins().add("CoreProtect"));
    }

    @Test
    void rootSettingsRejectEmptyPluginsAndNegativeGrace() {
        assertThrows(IllegalArgumentException.class,
                () -> settings(List.of(), 20));
        assertThrows(IllegalArgumentException.class,
                () -> settings(List.of("Vault"), -1));
    }

    @Test
    void protectionTrimsCommandsAndRejectsInvalidValues() {
        Settings.Protection protection = new Settings.Protection(
                true, true, false, "  restarting  ", 5,
                "  restart  ", "  stop  ", true);

        assertEquals("restarting", protection.kickMessage());
        assertEquals("restart", protection.restartCommand());
        assertEquals("stop", protection.fallbackCommand());
        assertThrows(IllegalArgumentException.class,
                () -> new Settings.Protection(true, true, false, "msg", -1,
                        "restart", "stop", true));
        assertThrows(IllegalArgumentException.class,
                () -> new Settings.Protection(true, true, false, " ", 0,
                        "restart", "stop", true));
    }

    @Test
    void loopAndDiscordRejectUnsafeNumericConfiguration() {
        assertThrows(IllegalArgumentException.class,
                () -> new Settings.Loop(true, -1));
        assertThrows(IllegalArgumentException.class,
                () -> discord(true, "https://example.invalid", 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> discord(true, "https://example.invalid", 1, -1));
    }

    @Test
    void discordConfiguredRequiresEnabledAndNonBlankWebhook() {
        assertTrue(discord(true, "https://example.invalid", 1, 0).configured());
        assertFalse(discord(false, "https://example.invalid", 1, 0).configured());
        assertFalse(discord(true, "   ", 1, 0).configured());
    }

    @Test
    void discordAndBypassDefensivelyCopyLists() {
        List<String> roles = new ArrayList<>(List.of("role-1"));
        List<String> users = new ArrayList<>(List.of("user-1"));
        Settings.Discord discord = new Settings.Discord(
                true, "https://example.invalid", roles, users, 1, 0,
                "Guardian", "");
        roles.add("role-2");
        users.add("user-2");

        assertEquals(List.of("role-1"), discord.roleIds());
        assertEquals(List.of("user-1"), discord.userIds());

        List<UUID> uuids = new ArrayList<>(List.of(UUID.randomUUID()));
        Settings.Bypass bypass = new Settings.Bypass(uuids, "guardian.bypass", false);
        int originalSize = bypass.playerUuids().size();
        uuids.add(UUID.randomUUID());

        assertEquals(originalSize, bypass.playerUuids().size());
        assertThrows(UnsupportedOperationException.class,
                () -> bypass.playerUuids().add(UUID.randomUUID()));
    }

    @Test
    void messagesTrimRequiredTitles() {
        Settings.Messages messages = new Settings.Messages("  Incident  ", "  Recovered  ");

        assertEquals("Incident", messages.incidentTitle());
        assertEquals("Recovered", messages.recoveryTitle());
        assertThrows(IllegalArgumentException.class,
                () -> new Settings.Messages("", "Recovered"));
    }

    private static Settings settings(List<String> plugins, int graceTicks) {
        return new Settings(
                plugins,
                graceTicks,
                new Settings.Protection(true, true, false, "restart", 5,
                        "restart", "stop", true),
                new Settings.Loop(true, 3),
                discord(false, "", 1, 0),
                new Settings.Bypass(List.of(), "guardian.bypass", true),
                new Settings.Messages("Incident", "Recovered"));
    }

    private static Settings.Discord discord(
            boolean enabled,
            String url,
            int repeats,
            int delayMillis) {
        return new Settings.Discord(
                enabled, url, List.of(), List.of(), repeats, delayMillis,
                "Guardian", "");
    }
}
