package dev.p2wn.startupguardian;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.UUID;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class SettingsLoaderTest {

    @Test
    void invalidValuesUseSafeDefaults() {
        YamlConfiguration config = baseConfiguration();
        config.set("startup-check.grace-period-ticks", -1);
        config.set("protection.restart-delay-seconds", 9_999);
        config.set("restart-loop-protection.maximum-automatic-restarts", -4);
        config.set("discord.repeated-alerts", 0);
        config.set(
                "discord.staff-mentions.role-ids",
                List.of("bad", "123456"));
        config.set(
                "critical-mode-bypass.player-uuids",
                List.of(
                        "invalid",
                        "00000000-0000-0000-0000-000000000001"));

        Settings settings = SettingsLoader.load(config);

        assertEquals(20, settings.graceTicks());
        assertEquals(8, settings.protection().restartDelaySeconds());
        assertEquals(1, settings.loop().maximumRestarts());
        assertEquals(3, settings.discord().repeats());
        assertEquals(List.of("123456"), settings.discord().roleIds());
        assertEquals(
                List.of(UUID.fromString(
                        "00000000-0000-0000-0000-000000000001")),
                settings.bypass().playerUuids());
    }

    @Test
    void requiredPluginsAreDeduplicatedCaseInsensitively() {
        YamlConfiguration config = baseConfiguration();
        config.set(
                "required-plugins",
                List.of("WorldGuard", " worldguard ", "LuckPerms"));

        Settings settings = SettingsLoader.load(config);

        assertEquals(
                List.of("WorldGuard", "LuckPerms"),
                settings.requiredPlugins());
    }

    @Test
    void emptyRequiredPluginListIsRejected() {
        YamlConfiguration config = new YamlConfiguration();

        assertThrows(
                IllegalArgumentException.class,
                () -> SettingsLoader.load(config));
    }

    private static YamlConfiguration baseConfiguration() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("required-plugins", List.of("WorldGuard"));
        return config;
    }
}
