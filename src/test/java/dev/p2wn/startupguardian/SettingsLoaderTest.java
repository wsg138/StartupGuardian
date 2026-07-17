package dev.p2wn.startupguardian;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SettingsLoaderTest {
    @Test void invalidValuesUseSafeDefaults() {
        YamlConfiguration c = new YamlConfiguration(); c.set("startup-check.grace-period-ticks", -1); c.set("protection.restart-delay-seconds", 9999); c.set("restart-loop-protection.maximum-automatic-restarts", -4); c.set("discord.repeated-alerts", 0); c.set("discord.staff-mentions.role-ids", java.util.List.of("bad", "123456")); c.set("critical-mode-bypass.player-uuids", java.util.List.of("invalid", "00000000-0000-0000-0000-000000000001"));
        Settings s = SettingsLoader.load(c);
        assertEquals(20, s.graceTicks()); assertEquals(8, s.protection().restartDelaySeconds()); assertEquals(1, s.loop().maximumRestarts()); assertEquals(3, s.discord().repeats()); assertEquals(java.util.List.of("123456"), s.discord().roleIds()); assertEquals(java.util.List.of(java.util.UUID.fromString("00000000-0000-0000-0000-000000000001")), s.bypass().playerUuids());
    }
}
