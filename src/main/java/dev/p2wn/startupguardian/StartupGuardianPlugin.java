package dev.p2wn.startupguardian;

import org.bukkit.event.*;
import org.bukkit.event.server.ServerLoadEvent;
import java.util.concurrent.atomic.AtomicBoolean;

public final class StartupGuardianPlugin extends org.bukkit.plugin.java.JavaPlugin implements Listener {
    private GuardianService guardian; private WebhookClient webhook; private final AtomicBoolean startupEventHandled = new AtomicBoolean();
    @Override public void onEnable() { saveDefaultConfig(); Settings settings = SettingsLoader.load(getConfig()); IncidentStore store = new IncidentStore(getDataFolder().toPath()); webhook = new WebhookClient(getLogger()); guardian = new GuardianService(this, settings, store, webhook); GuardianCommand command = new GuardianCommand(this); getCommand("startupguardian").setExecutor(command); getCommand("startupguardian").setTabCompleter(command); getServer().getPluginManager().registerEvents(this, this); }
    @EventHandler public void onServerLoad(ServerLoadEvent event) { if (event.getType() != ServerLoadEvent.LoadType.STARTUP || !startupEventHandled.compareAndSet(false, true)) return; getServer().getScheduler().runTaskLater(this, guardian::startupCheck, guardian.settings().graceTicks()); }
    @Override public void onDisable() { if (webhook != null) webhook.close(); }
    GuardianService guardian() { return guardian; }
}
