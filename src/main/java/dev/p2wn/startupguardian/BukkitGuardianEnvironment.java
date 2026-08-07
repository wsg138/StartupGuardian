package dev.p2wn.startupguardian;

import java.util.List;
import java.util.Objects;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

final class BukkitGuardianEnvironment implements GuardianEnvironment {

    private final Plugin plugin;

    BukkitGuardianEnvironment(Plugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    @Override
    public List<PluginHealth> inspectPlugins(List<String> configuredNames) {
        return PluginHealth.inspect(Bukkit.getPluginManager(), configuredNames);
    }

    @Override
    public boolean whitelistEnabled() {
        return Bukkit.hasWhitelist();
    }

    @Override
    public void setWhitelist(boolean enabled) {
        Bukkit.setWhitelist(enabled);
    }

    @Override
    public List<OnlinePlayer> onlinePlayers() {
        return Bukkit.getOnlinePlayers().stream()
                .<OnlinePlayer>map(BukkitOnlinePlayer::new)
                .toList();
    }

    @Override
    public RestartTask scheduleRestart(long delayTicks, Runnable action) {
        BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, action, delayTicks);
        return new BukkitRestartTask(task);
    }

    @Override
    public boolean dispatchCommand(String command) {
        return Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
    }

    private record BukkitOnlinePlayer(Player player) implements OnlinePlayer {

        private BukkitOnlinePlayer {
            Objects.requireNonNull(player, "player");
        }

        @Override
        public boolean operator() {
            return player.isOp();
        }

        @Override
        public void kick(String message) {
            player.kick(MiniMessage.miniMessage().deserialize(message));
        }
    }

    private record BukkitRestartTask(BukkitTask task) implements RestartTask {

        private BukkitRestartTask {
            Objects.requireNonNull(task, "task");
        }

        @Override
        public boolean cancelled() {
            return task.isCancelled();
        }

        @Override
        public void cancel() {
            task.cancel();
        }
    }
}
