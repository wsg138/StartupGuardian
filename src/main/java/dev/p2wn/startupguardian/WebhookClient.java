package dev.p2wn.startupguardian;

import com.google.gson.*;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Logger;

public final class WebhookClient implements AutoCloseable {
    private final Logger log; private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "StartupGuardian-Webhook"); t.setDaemon(true); return t; });
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).executor(executor).build();
    public WebhookClient(Logger log) { this.log = log; }
    public void incident(Settings settings, Incident incident, boolean restartScheduled, boolean whitelisted) { String detail = "**" + settings.messages().incidentTitle() + "**\nIncident: `" + incident.incidentId() + "`\nFailures: " + failures(incident) + "\nRestart attempts: " + incident.automaticRestartAttempts() + "\nWhitelist enabled: " + whitelisted + "\nRestart scheduled: " + restartScheduled + "\nDetected: " + incident.lastDetection(); sendIncidentAlerts(settings, detail, "⚠️ **Critical startup failure requires staff attention.** Incident: `" + incident.incidentId() + "`"); }
    public void recovery(Settings settings, Incident incident) { send(settings, "**" + settings.messages().recoveryTitle() + "**\nIncident: `" + incident.incidentId() + "`\nPrevious failures: " + failures(incident) + "\nIncident duration: " + Duration.between(incident.firstDetection(), java.time.Instant.now()).toSeconds() + " seconds"); }
    public void test(Settings settings) { send(settings, "StartupGuardian webhook test. No server protection action was taken."); }
    private String failures(Incident i) { return String.join(", ", i.failures().stream().map(f -> "`" + f.configuredName() + "` (" + f.status() + ")").toList()); }
    private void sendIncidentAlerts(Settings s, String detail, String reminder) { send(s, detail); for (int i = 1; i < s.discord().repeats(); i++) { final int delayMultiplier = i; executor.submit(() -> { try { Thread.sleep((long) s.discord().delayMillis() * delayMultiplier); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; } send(s, reminder); }); } }
    private void send(Settings s, String message) {
        if (!s.discord().configured()) return;
        try {
            JsonObject payload = new JsonObject(); payload.addProperty("content", mentions(s) + message); payload.addProperty("username", s.discord().username()); if (!s.discord().avatarUrl().isBlank()) payload.addProperty("avatar_url", s.discord().avatarUrl());
            JsonObject allowed = new JsonObject(); JsonArray roles = new JsonArray(); s.discord().roleIds().forEach(roles::add); JsonArray users = new JsonArray(); s.discord().userIds().forEach(users::add); allowed.add("roles", roles); allowed.add("users", users); allowed.add("parse", new JsonArray()); payload.add("allowed_mentions", allowed);
            HttpRequest request = HttpRequest.newBuilder(URI.create(s.discord().webhookUrl())).timeout(Duration.ofSeconds(12)).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(payload.toString())).build();
            client.sendAsync(request, HttpResponse.BodyHandlers.discarding()).thenAccept(r -> { if (r.statusCode() < 200 || r.statusCode() >= 300) log.warning("[StartupGuardian] Discord webhook returned HTTP " + r.statusCode()); }).exceptionally(ex -> { log.warning("[StartupGuardian] Discord webhook request failed: " + ex.getClass().getSimpleName()); return null; });
        } catch (Exception ex) { log.warning("[StartupGuardian] Discord webhook configuration/request failed: " + ex.getClass().getSimpleName()); }
    }
    private String mentions(Settings s) { StringBuilder b = new StringBuilder(); s.discord().roleIds().forEach(id -> b.append("<@&").append(id).append("> ")); s.discord().userIds().forEach(id -> b.append("<@").append(id).append("> ")); return b.toString(); }
    @Override public void close() { executor.shutdownNow(); }
}
