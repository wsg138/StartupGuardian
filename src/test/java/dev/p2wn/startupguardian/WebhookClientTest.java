package dev.p2wn.startupguardian;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.text.MessageFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

class WebhookClientTest {

    @Test
    void repeatDelaysAreAbsoluteRatherThanCumulative() {
        assertEquals(
                List.of(0L, 1_500L, 3_000L),
                WebhookClient.repeatDelays(3, 1_500));
    }

    @Test
    void repeatDelayValidationRejectsInvalidValues() {
        assertThrows(
                IllegalArgumentException.class,
                () -> WebhookClient.repeatDelays(0, 1_500));
        assertThrows(
                IllegalArgumentException.class,
                () -> WebhookClient.repeatDelays(3, -1));
    }

    @Test
    void webhookNotConfiguredReturnsActualResult() {
        FakeTasks tasks = new FakeTasks();
        try (WebhookClient client = new WebhookClient(
                logger(),
                tasks,
                new FakeTransport())) {

            assertEquals(WebhookTestResult.NOT_CONFIGURED, client.test(settings(false, 1)));
            assertTrue(tasks.immediate.isEmpty());
        }
    }

    @Test
    void closedWebhookClientReturnsActualResult() {
        FakeTasks tasks = new FakeTasks();
        try (WebhookClient client = new WebhookClient(
                logger(),
                tasks,
                new FakeTransport())) {

            client.close();
            assertEquals(WebhookTestResult.CLOSED, client.test(settings(true, 1)));
        }
    }

    @Test
    void configuredWebhookQueuesTestOffThread() {
        FakeTasks tasks = new FakeTasks();
        try (WebhookClient client = new WebhookClient(
                logger(),
                tasks,
                new FakeTransport())) {

            assertEquals(WebhookTestResult.QUEUED, client.test(settings(true, 1)));
            assertEquals(1, tasks.immediate.size());
        }
    }

    @Test
    void httpSuccessCompletesWithoutWarning() {
        CapturingHandler handler = new CapturingHandler();
        Logger logger = logger(handler);
        FakeTasks tasks = new FakeTasks();
        FakeTransport transport = new FakeTransport(new WebhookResponse(204, Optional.empty()));
        try (WebhookClient client = new WebhookClient(logger, tasks, transport)) {
            client.test(settings(true, 1));
            tasks.runImmediate();

            assertEquals(1, transport.calls);
            assertFalse(handler.hasWarning());
        }
    }

    @Test
    void nonSuccessResponseIsLogged() {
        CapturingHandler handler = new CapturingHandler();
        Logger logger = logger(handler);
        FakeTasks tasks = new FakeTasks();
        FakeTransport transport = new FakeTransport(new WebhookResponse(500, Optional.empty()));
        try (WebhookClient client = new WebhookClient(logger, tasks, transport)) {
            client.test(settings(true, 1));
            tasks.runImmediate();

            assertTrue(handler.hasMessage("HTTP 500"));
        }
    }

    @Test
    void rateLimitUsesRetryAfterOnce() {
        FakeTasks tasks = new FakeTasks();
        FakeTransport transport = new FakeTransport(
                new WebhookResponse(429, Optional.of("0.001")),
                new WebhookResponse(204, Optional.empty()));
        try (WebhookClient client = new WebhookClient(logger(), tasks, transport)) {
            client.test(settings(true, 1));
            tasks.runImmediate();
            assertEquals(List.of(1L), tasks.delays());
            tasks.runDelayed();

            assertEquals(2, transport.calls);
            assertTrue(tasks.delayed.isEmpty());
        }
    }

    @Test
    void secondRateLimitIsNotRetriedAgain() {
        FakeTasks tasks = new FakeTasks();
        FakeTransport transport = new FakeTransport(
                new WebhookResponse(429, Optional.of("0")),
                new WebhookResponse(429, Optional.of("0")));
        try (WebhookClient client = new WebhookClient(logger(), tasks, transport)) {
            client.test(settings(true, 1));
            tasks.runImmediate();
            tasks.runDelayed();

            assertEquals(2, transport.calls);
            assertTrue(tasks.delayed.isEmpty());
        }
    }

    @Test
    void networkFailureIsLoggedWithoutThrowing() {
        CapturingHandler handler = new CapturingHandler();
        Logger logger = logger(handler);
        FakeTasks tasks = new FakeTasks();
        FakeTransport transport = new FakeTransport();
        transport.failure = new IOException("network down");
        try (WebhookClient client = new WebhookClient(logger, tasks, transport)) {
            client.test(settings(true, 1));
            tasks.runImmediate();

            assertTrue(handler.hasMessage("IOException"));
        }
    }

    @Test
    void contentIsTruncatedToDiscordLimit() {
        JsonObject payload = JsonParser.parseString(
                WebhookClient.payload(settings(true, 1), "x".repeat(3_000)))
                .getAsJsonObject();

        assertEquals(
                WebhookClient.DISCORD_CONTENT_LIMIT,
                payload.get("content").getAsString().length());
        assertTrue(payload.get("content").getAsString().endsWith("…"));
    }

    @Test
    void allowedMentionsRemainExplicit() {
        JsonObject payload = JsonParser.parseString(
                WebhookClient.payload(settings(true, 1), "message"))
                .getAsJsonObject();
        JsonObject allowedMentions = payload.getAsJsonObject("allowed_mentions");

        assertEquals("12345", allowedMentions.getAsJsonArray("roles").get(0).getAsString());
        assertEquals("67890", allowedMentions.getAsJsonArray("users").get(0).getAsString());
        assertTrue(allowedMentions.getAsJsonArray("parse").isEmpty());
    }

    @Test
    void shutdownCancelsFutureReminders() {
        FakeTasks tasks = new FakeTasks();
        try (WebhookClient client = new WebhookClient(
                logger(),
                tasks,
                new FakeTransport())) {

            Incident incident = Incident.create(
                    List.of(new PluginHealth(
                            "WorldGuard",
                            "WorldGuard",
                            PluginHealth.State.DISABLED)),
                    false,
                    true);

            client.incident(settings(true, 3), incident, true, true);
            assertEquals(2, tasks.delayed.size());

            client.close();

            assertTrue(tasks.closed);
            assertTrue(tasks.delayed.stream().allMatch(task -> task.cancelled));
        }
    }

    private static Settings settings(boolean configured, int repeats) {
        return new Settings(
                List.of("WorldGuard"),
                20,
                new Settings.Protection(
                        true,
                        true,
                        false,
                        "Maintenance",
                        8,
                        "restart",
                        "stop",
                        true),
                new Settings.Loop(true, 1),
                new Settings.Discord(
                        configured,
                        configured ? "https://example.invalid/webhook" : "",
                        List.of("12345"),
                        List.of("67890"),
                        repeats,
                        1_500,
                        "Startup Guardian",
                        ""),
                new Settings.Bypass(List.<UUID>of(), "startupguardian.bypass", false),
                new Settings.Messages("Incident", "Recovery"));
    }

    private static Logger logger(Handler... handlers) {
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        for (Handler handler : handlers) {
            logger.addHandler(handler);
        }
        return logger;
    }

    private static final class FakeTasks implements WebhookTasks {

        private final Deque<Runnable> immediate = new ArrayDeque<>();
        private final List<FakeScheduledTask> delayed = new ArrayList<>();
        private boolean closed;

        @Override
        public boolean submit(Runnable task) {
            if (closed) {
                return false;
            }
            immediate.add(task);
            return true;
        }

        @Override
        public boolean schedule(Runnable task, long delayMillis) {
            if (closed) {
                return false;
            }
            delayed.add(new FakeScheduledTask(task, delayMillis));
            return true;
        }

        @Override
        public boolean closed() {
            return closed;
        }

        @Override
        public void close() {
            closed = true;
            delayed.forEach(task -> task.cancelled = true);
            immediate.clear();
        }

        private void runImmediate() {
            immediate.removeFirst().run();
        }

        private void runDelayed() {
            FakeScheduledTask task = delayed.remove(0);
            if (!task.cancelled) {
                task.action.run();
            }
        }

        private List<Long> delays() {
            return delayed.stream().map(task -> task.delayMillis).toList();
        }
    }

    private static final class FakeScheduledTask {

        private final Runnable action;
        private final long delayMillis;
        private boolean cancelled;

        private FakeScheduledTask(Runnable action, long delayMillis) {
            this.action = action;
            this.delayMillis = delayMillis;
        }
    }

    private static final class FakeTransport implements WebhookTransport {

        private final Deque<WebhookResponse> responses = new ArrayDeque<>();
        private IOException failure;
        private int calls;

        private FakeTransport(WebhookResponse... responses) {
            this.responses.addAll(List.of(responses));
        }

        @Override
        public WebhookResponse send(String webhookUrl, String payload) throws IOException {
            calls++;
            if (failure != null) {
                throw failure;
            }
            return responses.isEmpty()
                    ? new WebhookResponse(204, Optional.empty())
                    : responses.removeFirst();
        }
    }

    private static final class CapturingHandler extends Handler {

        private final List<LogRecord> records = new ArrayList<>();

        @Override
        public void publish(LogRecord record) {
            records.add(record);
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }

        private boolean hasWarning() {
            return records.stream().anyMatch(record ->
                    record.getLevel().intValue() >= Level.WARNING.intValue());
        }

        private boolean hasMessage(String fragment) {
            return records.stream().anyMatch(record -> {
                Object[] parameters = record.getParameters();
                String message = parameters == null
                        ? record.getMessage()
                        : MessageFormat.format(record.getMessage(), parameters);
                return message.contains(fragment);
            });
        }
    }
}
