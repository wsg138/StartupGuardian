package dev.p2wn.startupguardian;

import java.util.List;

interface GuardianEnvironment {

    List<PluginHealth> inspectPlugins(List<String> configuredNames);

    boolean whitelistEnabled();

    void setWhitelist(boolean enabled);

    List<OnlinePlayer> onlinePlayers();

    RestartTask scheduleRestart(long delayTicks, Runnable action);

    boolean dispatchCommand(String command);

    interface OnlinePlayer {

        boolean operator();

        void kick(String message);
    }

    interface RestartTask {

        boolean cancelled();

        void cancel();
    }
}
