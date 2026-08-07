package dev.p2wn.startupguardian;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;

public record PluginHealth(
        String configuredName,
        String detectedName,
        State state) {

    public PluginHealth {
        configuredName = requireName(configuredName, "configuredName");
        if (detectedName != null) {
            detectedName = requireName(detectedName, "detectedName");
        }
        Objects.requireNonNull(state, "state");
    }

    public enum State {
        ENABLED,
        MISSING,
        DISABLED
    }

    public boolean healthy() {
        return state == State.ENABLED;
    }

    public static List<PluginHealth> inspect(
            PluginManager manager,
            List<String> configuredNames) {

        Objects.requireNonNull(manager, "manager");
        Objects.requireNonNull(configuredNames, "configuredNames");

        Map<String, Plugin> pluginsByName = new LinkedHashMap<>();
        Arrays.stream(manager.getPlugins()).forEach(plugin ->
                pluginsByName.putIfAbsent(
                        plugin.getName().toLowerCase(Locale.ROOT),
                        plugin));

        return configuredNames.stream()
                .map(name -> inspectOne(pluginsByName, name))
                .toList();
    }

    private static PluginHealth inspectOne(
            Map<String, Plugin> pluginsByName,
            String configuredName) {

        Plugin plugin = pluginsByName.get(configuredName.toLowerCase(Locale.ROOT));
        if (plugin == null) {
            return new PluginHealth(configuredName, null, State.MISSING);
        }

        State state = plugin.isEnabled() ? State.ENABLED : State.DISABLED;
        return new PluginHealth(configuredName, plugin.getName(), state);
    }

    private static String requireName(String value, String name) {
        String checked = Objects.requireNonNull(value, name).trim();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return checked;
    }
}
