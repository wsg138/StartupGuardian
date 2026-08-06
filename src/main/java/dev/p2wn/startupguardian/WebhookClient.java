package dev.p2wn.startupguardian;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class WebhookClient implements AutoCloseable {

    private static final int DISCORD_CONTENT_LIMIT = 2_000;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(8);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(12);

    private final Logger logger;
    private final ScheduledExecutorService executor;
    private final HttpClient client;

    public WebhookClient(Logger logger) {
        this.logger = Objects.requireNonNull(logger, "logger");

        AtomicInteger threadNumber = new AtomicInteger();
        executor = Executors.newScheduledThreadPool(2, runnable -> {
            Thread thread = new Thread(
                    runnable,
                    "StartupGuardian-Webhook-" + threadNumber.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });

        client = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .executor(executor)
                .build();
    }

    public void incident(
            Settings settings,
            Incident incident,
            boolean restartScheduled,
            boolean whitelisted) {

        String detail = "**" + settings.messages().incidentTitle() + "**\n"
                + "Incident: `" + incident.incidentId() + "`\n"
                + "Failures: " + failures(incident) + "\n"
                + "Restart attempts: " + incident.automaticRestartAttempts() + "\n"
                + "Whitelist enabled: " + whitelisted + "\n"
                + "Restart scheduled: " + restartScheduled + "\n"
                + "Detected: " + incident.lastDetection();

        String reminder = "⚠️ **Critical startup failure requires staff attention.** "
                + "Incident: `" + incident.incidentId() + "`";

        sendIncidentAlerts(settings, detail, reminder);
    }

    public void recovery(Settings settings, Incident incident) {
        long durationSeconds = Duration.between(
                incident.firstDetection(),
                Instant.now()).toSeconds();

        send(settings, "**" + settings.messages().recoveryTitle() + "**\n"
                + "Incident: `" + incident.incidentId() + "`\n"
                + "Previous failures: " + failures(incident) + "\n"
                + "Incident duration: " + durationSeconds + " seconds");
    }

    public void test(Settings settings) {
        send(settings, "StartupGuardian webhook test. "
                + "No server protection action was taken.");
    }

    static List<Long> repeatDelays(int repeats, int delayMillis) {
        if (repeats < 1) {
            throw new IllegalArgumentException("repeats must be at least one");
        }
        if (delayMillis < 0) {
            throw new IllegalArgumentException("delayMillis must not be negative");
        }

        List<Long> delays = new ArrayList<>(repeats);
        for (int index = 0; index < repeats; index++) {
            delays.add((long) delayMillis * index);
        }
        return List.copyOf(delays);
    }

    private void sendIncidentAlerts(
            Settings settings,
            String detail,
            String reminder) {

        if (!settings.discord().configured()) {
            return;
        }

        List<Long> delays = repeatDelays(
                settings.discord().repeats(),
                settings.discord().delayMillis());

        send(settings, detail);
        for (int index = 1; index < delays.size(); index++) {
            long delay = delays.get(index);
            try {
                executor.schedule(
                        () -> send(settings, reminder),
                        delay,
                        TimeUnit.MILLISECONDS);
            } catch (RejectedExecutionException exception) {
                logger.log(
                        Level.FINE,
                        "[StartupGuardian] Webhook executor was already closed.",
                        exception);
                return;
            }
        }
    }

    private String failures(Incident incident) {
        return String.join(
                ", ",
                incident.failures().stream()
                        .map(failure -> "`" + failure.configuredName()
                                + "` (" + failure.status() + ")")
                        .toList());
    }

    private void send(Settings settings, String message) {
        if (!settings.discord().configured()) {
            return;
        }

        try {
            String content = truncate(
                    mentions(settings) + message,
                    DISCORD_CONTENT_LIMIT);
            HttpRequest request = buildRequest(settings, content);

            client.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                    .thenAccept(response -> handleResponse(response.statusCode()))
                    .exceptionally(exception -> {
                        logger.log(
                                Level.WARNING,
                                "[StartupGuardian] Discord webhook request failed: {0}",
                                exception.getClass().getSimpleName());
                        return null;
                    });
        } catch (IllegalArgumentException | RejectedExecutionException exception) {
            logger.log(
                    Level.WARNING,
                    "[StartupGuardian] Discord webhook configuration/request failed: {0}",
                    exception.getClass().getSimpleName());
        }
    }

    private HttpRequest buildRequest(Settings settings, String content) {
        JsonObject payload = new JsonObject();
        payload.addProperty("content", content);
        payload.addProperty("username", settings.discord().username());

        if (!settings.discord().avatarUrl().isBlank()) {
            payload.addProperty("avatar_url", settings.discord().avatarUrl());
        }

        JsonObject allowedMentions = new JsonObject();
        JsonArray roles = new JsonArray();
        settings.discord().roleIds().forEach(roles::add);
        JsonArray users = new JsonArray();
        settings.discord().userIds().forEach(users::add);

        allowedMentions.add("roles", roles);
        allowedMentions.add("users", users);
        allowedMentions.add("parse", new JsonArray());
        payload.add("allowed_mentions", allowedMentions);

        return HttpRequest.newBuilder(URI.create(settings.discord().webhookUrl()))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                .build();
    }

    private void handleResponse(int statusCode) {
        if (statusCode < 200 || statusCode >= 300) {
            logger.log(
                    Level.WARNING,
                    "[StartupGuardian] Discord webhook returned HTTP {0}",
                    statusCode);
        }
    }

    private String mentions(Settings settings) {
        StringBuilder builder = new StringBuilder();
        settings.discord().roleIds().forEach(id ->
                builder.append("<@&").append(id).append("> "));
        settings.discord().userIds().forEach(id ->
                builder.append("<@").append(id).append("> "));
        return builder.toString();
    }

    private static String truncate(String value, int maximumLength) {
        if (value.length() <= maximumLength) {
            return value;
        }
        return value.substring(0, maximumLength - 1) + "…";
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
