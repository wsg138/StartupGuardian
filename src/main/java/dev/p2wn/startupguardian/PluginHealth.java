package dev.p2wn.startupguardian;

import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import java.util.*;

public record PluginHealth(String configuredName, String detectedName, State state) {
    public enum State { ENABLED, MISSING, DISABLED }
    public boolean healthy() { return state == State.ENABLED; }
    public static List<PluginHealth> inspect(PluginManager manager, List<String> names) {
        return names.stream().map(name -> inspectOne(manager, name)).toList();
    }
    private static PluginHealth inspectOne(PluginManager manager, String name) {
        Plugin found = Arrays.stream(manager.getPlugins()).filter(p -> p.getName().equalsIgnoreCase(name)).findFirst().orElse(null);
        if (found == null) return new PluginHealth(name, null, State.MISSING);
        return new PluginHealth(name, found.getName(), found.isEnabled() ? State.ENABLED : State.DISABLED);
    }
}
