package dev.p2wn.startupguardian;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import org.bukkit.command.PluginCommand;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.event.server.ServerLoadEvent;
import org.bukkit.plugin.java.JavaPlugin;

public final class StartupGuardianPlugin extends JavaPlugin implements Listener {

    private final AtomicBoolean startupEventHandled = new AtomicBoolean();

    private GuardianService guardianService;
    private WebhookClient webhook;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        Settings settings;
        try {
            settings = SettingsLoader.load(getConfig());
        } catch (IllegalArgumentException exception) {
            getLogger().log(
                    Level.SEVERE,
                    "[StartupGuardian] Invalid configuration; plugin will be disabled: {0}",
                    exception.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        IncidentStore store = new IncidentStore(getDataFolder().toPath(), getLogger());
        webhook = new WebhookClient(getLogger());
        guardianService = new GuardianService(
                getLogger(),
                settings,
                store,
                webhook,
                new BukkitGuardianEnvironment(this));

        GuardianCommand commandHandler = new GuardianCommand(this);
        PluginCommand command = Objects.requireNonNull(
                getCommand("startupguardian"),
                "startupguardian command is missing from plugin.yml");
        command.setExecutor(commandHandler);
        command.setTabCompleter(commandHandler);

        getServer().getPluginManager().registerEvents(this, this);
    }

    @EventHandler
    public void onServerLoad(ServerLoadEvent event) {
        if (event.getType() != ServerLoadEvent.LoadType.STARTUP
                || !startupEventHandled.compareAndSet(false, true)
                || guardianService == null) {
            return;
        }

        getServer().getScheduler().runTaskLater(
                this,
                guardianService::startupCheck,
                guardianService.settings().graceTicks());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerLogin(PlayerLoginEvent event) {
        if (guardianService == null
                || event.getResult() != PlayerLoginEvent.Result.KICK_WHITELIST
                || !guardianService.allowsCriticalBypass(event.getPlayer())) {
            return;
        }

        event.allow();
        getLogger().log(
                Level.WARNING,
                "[StartupGuardian] Allowed critical-mode bypass for {0}",
                event.getPlayer().getName());
    }

    @Override
    public void onDisable() {
        if (guardianService != null) {
            guardianService.close();
        }
        if (webhook != null) {
            webhook.close();
        }
    }

    GuardianService guardian() {
        return Objects.requireNonNull(
                guardianService,
                "StartupGuardian service is not initialized");
    }
}
