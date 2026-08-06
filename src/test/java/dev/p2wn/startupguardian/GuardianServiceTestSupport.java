package dev.p2wn.startupguardian;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

class GuardianServiceTestSupport {

    static final Logger LOGGER = Logger.getAnonymousLogger();
    static final List<PluginHealth> FAILED = List.of(
            new PluginHealth("WorldGuard", "WorldGuard", PluginHealth.State.DISABLED));
    static final List<PluginHealth> HEALTHY = List.of(
            new PluginHealth("WorldGuard", "WorldGuard", PluginHealth.State.ENABLED));

    static CommandSender commandSender() {
        return (CommandSender) Proxy.newProxyInstance(
                CommandSender.class.getClassLoader(),
                new Class<?>[] {CommandSender.class},
                (proxy, method, arguments) -> defaultValue(method.getReturnType()));
    }

    static Player player(UUID uuid, boolean operator, boolean permission) {
        return (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class<?>[] {Player.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getUniqueId" -> uuid;
                    case "isOp" -> operator;
                    case "hasPermission" -> permission;
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static Object defaultValue(Class<?> returnType) {
        if (returnType == boolean.class) {
            return false;
        }
        if (returnType == int.class) {
            return 0;
        }
        if (returnType == long.class) {
            return 0L;
        }
        return null;
    }

    static GuardianService service(
            IncidentRepository repository,
            FakeNotifier notifier,
            FakeEnvironment environment,
            Settings settings) {

        return new GuardianService(LOGGER, settings, repository, notifier, environment);
    }

    static Settings settings(boolean kickOperators) {
        return settings(true, kickOperators);
    }

    static Settings settings(boolean kickPlayers, boolean kickOperators) {
        return settings(
                kickPlayers,
                kickOperators,
                new Settings.Bypass(
                        List.<UUID>of(),
                        "startupguardian.bypass",
                        false));
    }

    static Settings settings(
            boolean kickPlayers,
            boolean kickOperators,
            Settings.Bypass bypass) {

        return new Settings(
                List.of("WorldGuard"),
                20,
                new Settings.Protection(
                        true,
                        kickPlayers,
                        kickOperators,
                        "Maintenance",
                        8,
                        "restart",
                        "stop",
                        true),
                new Settings.Loop(true, 1),
                new Settings.Discord(
                        true,
                        "https://example.invalid/webhook",
                        List.of(),
                        List.of(),
                        1,
                        0,
                        "Startup Guardian",
                        ""),
                bypass,
                new Settings.Messages("Incident", "Recovery"));
    }

    static final class FakeRepository implements IncidentRepository {

        final List<String> sequence;
        Optional<Incident> incident = Optional.empty();
        boolean saveFails;
        boolean clearFails;
        boolean corrupted;

        FakeRepository(List<String> sequence) {
            this.sequence = sequence;
        }

        @Override
        public Optional<Incident> load() {
            return incident;
        }

        @Override
        public void save(Incident value) throws IOException {
            sequence.add("save");
            if (saveFails) {
                throw new IOException("forced save failure");
            }
            incident = Optional.of(value);
            corrupted = false;
        }

        @Override
        public void clear() throws IOException {
            sequence.add("clear");
            if (clearFails) {
                throw new IOException("forced clear failure");
            }
            incident = Optional.empty();
            corrupted = false;
        }

        @Override
        public boolean corrupted() {
            return corrupted;
        }

        @Override
        public Path path() {
            return Path.of("active-incident.json");
        }
    }

    static final class FakeNotifier implements GuardianNotifier {

        final List<String> sequence;
        int incidents;
        int persistenceFailures;
        int recoveries;
        boolean lastRestartScheduled;

        FakeNotifier(List<String> sequence) {
            this.sequence = sequence;
        }

        @Override
        public void incident(
                Settings settings,
                Incident incident,
                boolean restartScheduled,
                boolean whitelisted) {

            incidents++;
            lastRestartScheduled = restartScheduled;
            sequence.add("alert");
        }

        @Override
        public void persistenceFailure(Settings settings, Incident incident) {
            persistenceFailures++;
            sequence.add("persistence-failure");
        }

        @Override
        public void recovery(Settings settings, Incident incident) {
            recoveries++;
        }

        @Override
        public WebhookTestResult test(Settings settings) {
            return WebhookTestResult.QUEUED;
        }
    }

    static final class FakeEnvironment implements GuardianEnvironment {

        final List<String> sequence;
        final FakePlayer nonOperator;
        final FakePlayer operator;
        final List<String> commands = new ArrayList<>();
        List<PluginHealth> health;
        boolean whitelist;
        boolean primaryDispatchResult = true;
        boolean fallbackDispatchResult = true;
        boolean protectionFails;
        boolean scheduleFails;
        int whitelistChanges;
        int scheduleCalls;
        FakeRestartTask lastTask;

        FakeEnvironment(List<String> sequence, List<PluginHealth> health) {
            this.sequence = sequence;
            this.health = health;
            nonOperator = new FakePlayer(false, sequence);
            operator = new FakePlayer(true, sequence);
        }

        @Override
        public List<PluginHealth> inspectPlugins(List<String> configuredNames) {
            return health;
        }

        @Override
        public boolean whitelistEnabled() {
            return whitelist;
        }

        @Override
        public void setWhitelist(boolean enabled) {
            sequence.add("whitelist");
            if (protectionFails) {
                throw new IllegalStateException("forced protection failure");
            }
            whitelist = enabled;
            whitelistChanges++;
        }

        @Override
        public List<OnlinePlayer> onlinePlayers() {
            return List.of(nonOperator, operator);
        }

        @Override
        public RestartTask scheduleRestart(long delayTicks, Runnable action) {
            sequence.add("schedule");
            scheduleCalls++;
            if (scheduleFails) {
                throw new IllegalStateException("forced schedule failure");
            }
            lastTask = new FakeRestartTask(action, sequence);
            return lastTask;
        }

        @Override
        public boolean dispatchCommand(String command) {
            commands.add(command);
            if ("restart".equals(command)) {
                return primaryDispatchResult;
            }
            if ("stop".equals(command)) {
                return fallbackDispatchResult;
            }
            return true;
        }
    }

    static final class FakePlayer implements GuardianEnvironment.OnlinePlayer {

        final boolean operator;
        final List<String> sequence;
        int kicks;

        FakePlayer(boolean operator, List<String> sequence) {
            this.operator = operator;
            this.sequence = sequence;
        }

        @Override
        public boolean operator() {
            return operator;
        }

        @Override
        public void kick(String message) {
            kicks++;
            sequence.add("kick");
        }
    }

    static final class FakeRestartTask implements GuardianEnvironment.RestartTask {

        final Runnable action;
        final List<String> sequence;
        boolean cancelled;
        boolean cancelFails;

        FakeRestartTask(Runnable action, List<String> sequence) {
            this.action = action;
            this.sequence = sequence;
        }

        @Override
        public boolean cancelled() {
            return cancelled;
        }

        @Override
        public void cancel() {
            sequence.add("cancel");
            if (cancelFails) {
                throw new IllegalStateException("forced cancellation failure");
            }
            cancelled = true;
        }

        void run() {
            action.run();
        }
    }
}
