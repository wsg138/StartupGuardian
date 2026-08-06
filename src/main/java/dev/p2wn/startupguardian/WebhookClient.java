package dev.p2wn.startupguardian;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class WebhookClient implements GuardianNotifier, AutoCloseable {

    static final int DISCORD_CONTENT_LIMIT = 2_000;

    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(8);
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(12);
    private static final Duration SHUTDOWN_GRACE = Duration.ofSeconds(2);
    private static final long MAX_RETRY_AFTER_MILLIS = 30_000L;
    private static final int MAX_RATE_LIMIT_RETRIES = 1;

    private final Logger logger;
    private final WebhookTasks tasks;
    private final WebhookTransport transport;

    public WebhookClient(Logger logger) {
        this(
                logger,
                new ScheduledWebhookTasks(SHUTDOWN_GRACE),
                new JdkWebhookTransport());
    }

    WebhookClient(Logger logger, WebhookTasks tasks, WebhookTransport transport) {
        this.logger = Objects.requireNonNull(logger, "logger");
        this.tasks = Objects.requireNonNull(tasks, "tasks");
        this.transport = Objects.requireNonNull(transport, "transport");
    }

    @Override
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
                + "Restart planned: " + restartScheduled + "\n"
                + "Detected: " + incident.lastDetection();

        String reminder = "⚠️ **Critical startup failure requires staff attention.** "
                + "Incident: `" + incident.incidentId() + "`";

        sendIncidentAlerts(settings, detail, reminder);
    }

    @Override
    public void persistenceFailure(Settings settings, Incident incident) {
        String message = "**StartupGuardian persistence failure**\n"
                + "Incident candidate: `" + incident.incidentId() + "`\n"
                + "The incident marker could not be saved. StartupGuardian did not change "
                + "the whitelist, kick players, or schedule a new restart.";
        queue(settings, message);
    }

    @Override
    public void recovery(Settings settings, Incident incident) {
        long durationSeconds = Duration.between(
                incident.firstDetection(),
                Instant.now()).toSeconds();

        queue(settings, "**" + settings.messages().recoveryTitle() + "**\n"
                + "Incident: `" + incident.incidentId() + "`\n"
                + "Previous failures: " + failures(incident) + "\n"
                + "Incident duration: " + durationSeconds + " seconds");
    }

    @Override
    public WebhookTestResult test(Settings settings) {
        Objects.requireNonNull(settings, "settings");
        if (tasks.closed()) {
            return WebhookTestResult.CLOSED;
        }
        if (!settings.discord().configured()) {
            return WebhookTestResult.NOT_CONFIGURED;
        }

        boolean queued = queue(
                settings,
                "StartupGuardian webhook test. No server protection action was taken.");
        return queued ? WebhookTestResult.QUEUED : WebhookTestResult.CLOSED;
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

    static String payload(Settings settings, String message) {
        String content = truncate(
                mentions(settings) + Objects.requireNonNull(message, "message"),
                DISCORD_CONTENT_LIMIT);

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
        return payload.toString();
    }

    private void sendIncidentAlerts(
            Settings settings,
            String detail,
            String reminder) {

        if (!settings.discord().configured() || tasks.closed()) {
            return;
        }

        List<Long> delays = repeatDelays(
                settings.discord().repeats(),
                settings.discord().delayMillis());

        queue(settings, detail);
        for (int index = 1; index < delays.size(); index++) {
            long delay = delays.get(index);
            String requestPayload = payload(settings, reminder);
            tasks.schedule(
                    () -> deliver(settings.discord().webhookUrl(), requestPayload, 0),
                    delay);
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

    private boolean queue(Settings settings, String message) {
        if (!settings.discord().configured() || tasks.closed()) {
            return false;
        }

        String requestPayload = payload(settings, message);
        return tasks.submit(() -> deliver(
                settings.discord().webhookUrl(),
                requestPayload,
                0));
    }

    private void deliver(String webhookUrl, String requestPayload, int retryNumber) {
        try {
            WebhookResponse response = transport.send(webhookUrl, requestPayload);
            handleResponse(webhookUrl, requestPayload, response, retryNumber);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            logger.log(
                    Level.FINE,
                    "[StartupGuardian] Discord webhook delivery was interrupted.");
        } catch (IOException | IllegalArgumentException exception) {
            logger.log(
                    Level.WARNING,
                    "[StartupGuardian] Discord webhook request failed: {0}",
                    exception.getClass().getSimpleName());
        }
    }

    private void handleResponse(
            String webhookUrl,
            String requestPayload,
            WebhookResponse response,
            int retryNumber) {

        int statusCode = response.statusCode();
        if (statusCode >= 200 && statusCode < 300) {
            return;
        }

        if (statusCode == 429 && retryNumber < MAX_RATE_LIMIT_RETRIES) {
            Optional<Long> retryDelay = parseRetryAfter(response.retryAfter());
            if (retryDelay.isPresent()) {
                boolean scheduled = tasks.schedule(
                        () -> deliver(webhookUrl, requestPayload, retryNumber + 1),
                        retryDelay.orElseThrow());
                if (scheduled) {
                    logger.log(
                            Level.WARNING,
                            "[StartupGuardian] Discord rate limited a webhook; "
                                    + "one bounded retry was scheduled.");
                    return;
                }
            }
        }

        logger.log(
                Level.WARNING,
                "[StartupGuardian] Discord webhook returned HTTP {0}",
                statusCode);
    }

    private Optional<Long> parseRetryAfter(Optional<String> header) {
        if (header.isEmpty()) {
            return Optional.empty();
        }

        try {
            BigDecimal seconds = new BigDecimal(header.orElseThrow().trim());
            if (seconds.signum() < 0) {
                return Optional.empty();
            }
            long milliseconds = seconds
                    .multiply(BigDecimal.valueOf(1_000L))
                    .setScale(0, RoundingMode.CEILING)
                    .longValueExact();
            return Optional.of(Math.min(milliseconds, MAX_RETRY_AFTER_MILLIS));
        } catch (ArithmeticException | NumberFormatException exception) {
            logger.log(
                    Level.FINE,
                    "[StartupGuardian] Discord Retry-After header was invalid.");
            return Optional.empty();
        }
    }

    private static String mentions(Settings settings) {
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
        tasks.close();
    }
}

interface WebhookTasks {

    boolean submit(Runnable task);

    boolean schedule(Runnable task, long delayMillis);

    boolean closed();

    void close();
}

final class ScheduledWebhookTasks implements WebhookTasks {

    private final ScheduledExecutorService executor;
    private final Duration shutdownGrace;
    private final Set<Future<?>> submittedTasks = ConcurrentHashMap.newKeySet();
    private final Set<ScheduledFuture<?>> delayedTasks = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();

    ScheduledWebhookTasks(Duration shutdownGrace) {
        this.shutdownGrace = Objects.requireNonNull(shutdownGrace, "shutdownGrace");
        AtomicInteger threadNumber = new AtomicInteger();
        executor = Executors.newScheduledThreadPool(2, runnable -> {
            Thread thread = new Thread(
                    runnable,
                    "StartupGuardian-Webhook-" + threadNumber.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    @Override
    public boolean submit(Runnable task) {
        Objects.requireNonNull(task, "task");
        if (closed()) {
            return false;
        }
        try {
            submittedTasks.add(executor.submit(task));
            return true;
        } catch (RejectedExecutionException exception) {
            return false;
        }
    }

    @Override
    public boolean schedule(Runnable task, long delayMillis) {
        Objects.requireNonNull(task, "task");
        if (closed()) {
            return false;
        }
        try {
            delayedTasks.add(executor.schedule(
                    () -> {
                        if (!closed()) {
                            task.run();
                        }
                    },
                    delayMillis,
                    TimeUnit.MILLISECONDS));
            return true;
        } catch (RejectedExecutionException exception) {
            return false;
        }
    }

    @Override
    public boolean closed() {
        return closed.get();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }

        delayedTasks.forEach(task -> task.cancel(false));
        submittedTasks.forEach(task -> task.cancel(false));
        executor.shutdown();
        try {
            if (!executor.awaitTermination(shutdownGrace.toMillis(), TimeUnit.MILLISECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException exception) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}

interface WebhookTransport {

    WebhookResponse send(String webhookUrl, String payload)
            throws IOException, InterruptedException;
}

record WebhookResponse(int statusCode, Optional<String> retryAfter) {

    WebhookResponse {
        Objects.requireNonNull(retryAfter, "retryAfter");
    }
}

final class JdkWebhookTransport implements WebhookTransport {

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(WebhookClient.CONNECT_TIMEOUT)
            .build();

    @Override
    public WebhookResponse send(String webhookUrl, String payload)
            throws IOException, InterruptedException {

        HttpRequest request = HttpRequest.newBuilder(URI.create(webhookUrl))
                .timeout(WebhookClient.REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();
        HttpResponse<Void> response = client.send(
                request,
                HttpResponse.BodyHandlers.discarding());
        return new WebhookResponse(
                response.statusCode(),
                response.headers().firstValue("Retry-After"));
    }
}
