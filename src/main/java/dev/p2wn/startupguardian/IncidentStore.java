package dev.p2wn.startupguardian;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;
import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class IncidentStore implements IncidentRepository {

    static final int CURRENT_SCHEMA_VERSION = 1;

    private static final String ACTIVE_INCIDENT_FILE = "active-incident.json";
    static final String CORRUPTION_SENTINEL_FILE = "incident-corruption.lock";
    private static final String SCHEMA_VERSION = "schemaVersion";
    private static final List<String> REQUIRED_INCIDENT_FIELDS = List.of(
            "incidentId",
            "firstDetection",
            "lastDetection",
            "failures",
            "previousWhitelistEnabled",
            "guardianEnabledWhitelist",
            "automaticRestartAttempts",
            "restartLoopStopped");

    private final Path file;
    private final Path corruptionSentinel;
    private final Logger logger;
    private final Gson gson;
    private final FileMover mover;

    private Optional<Incident> cachedIncident = Optional.empty();
    private boolean loaded;
    private boolean corruptionDetected;
    private boolean quarantineFailed;

    public IncidentStore(Path dataFolder, Logger logger) {
        this(dataFolder, logger, Files::move);
    }

    IncidentStore(Path dataFolder, Logger logger, FileMover mover) {
        Path folder = Objects.requireNonNull(dataFolder, "dataFolder");
        file = folder.resolve(ACTIVE_INCIDENT_FILE);
        corruptionSentinel = folder.resolve(CORRUPTION_SENTINEL_FILE);
        this.logger = Objects.requireNonNull(logger, "logger");
        this.mover = Objects.requireNonNull(mover, "mover");
        gson = createGson();
        corruptionDetected = Files.exists(corruptionSentinel);
    }

    @Override
    public Path path() {
        return file;
    }

    @Override
    public synchronized Optional<Incident> load() {
        if (loaded) {
            return cachedIncident;
        }

        loaded = true;
        if (!Files.exists(file)) {
            return cachedIncident;
        }

        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonObject marker = parseMarker(reader);
            validateMarker(marker);
            Incident incident = gson.fromJson(marker, Incident.class);
            if (incident == null) {
                throw new JsonParseException("Incident marker contained null");
            }
            cachedIncident = Optional.of(incident);
        } catch (IOException | RuntimeException exception) {
            corruptionDetected = true;
            cachedIncident = Optional.empty();
            quarantineCorruptFile(exception);
        }

        return cachedIncident;
    }

    @Override
    public synchronized void save(Incident incident) throws IOException {
        Objects.requireNonNull(incident, "incident");
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        JsonObject marker = gson.toJsonTree(incident).getAsJsonObject();
        marker.addProperty(SCHEMA_VERSION, CURRENT_SCHEMA_VERSION);

        Path temporaryFile = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.writeString(
                    temporaryFile,
                    gson.toJson(marker),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);
            replaceAtomically(temporaryFile);
            Files.deleteIfExists(corruptionSentinel);
            cachedIncident = Optional.of(incident);
            loaded = true;
            corruptionDetected = false;
            quarantineFailed = false;
        } finally {
            Files.deleteIfExists(temporaryFile);
        }
    }

    @Override
    public synchronized void clear() throws IOException {
        Files.deleteIfExists(file);
        Files.deleteIfExists(corruptionSentinel);
        cachedIncident = Optional.empty();
        loaded = true;
        corruptionDetected = false;
        quarantineFailed = false;
    }

    @Override
    public synchronized boolean corrupted() {
        if (!corruptionDetected && Files.exists(corruptionSentinel)) {
            corruptionDetected = true;
        }
        return corruptionDetected;
    }

    synchronized boolean quarantineFailed() {
        return quarantineFailed;
    }

    private JsonObject parseMarker(Reader reader) {
        JsonElement element = JsonParser.parseReader(reader);
        if (!element.isJsonObject()) {
            throw new JsonParseException("Incident marker root must be an object");
        }
        return element.getAsJsonObject();
    }

    private void validateMarker(JsonObject marker) {
        boolean legacy = !marker.has(SCHEMA_VERSION);
        if (legacy) {
            validateLegacyMarker(marker);
        } else {
            int schemaVersion = requiredInteger(marker, SCHEMA_VERSION);
            if (schemaVersion != CURRENT_SCHEMA_VERSION) {
                throw new JsonParseException(
                        "Unsupported incident schema version: " + schemaVersion);
            }
        }

        for (String field : REQUIRED_INCIDENT_FIELDS) {
            requirePresent(marker, field);
        }

        requiredText(marker, "incidentId");
        requiredInstant(marker, "firstDetection");
        requiredInstant(marker, "lastDetection");
        validateFailures(requiredArray(marker, "failures"));
        requiredBoolean(marker, "previousWhitelistEnabled");
        requiredBoolean(marker, "guardianEnabledWhitelist");
        int attempts = requiredInteger(marker, "automaticRestartAttempts");
        if (attempts < 0) {
            throw new JsonParseException("automaticRestartAttempts must not be negative");
        }
        requiredBoolean(marker, "restartLoopStopped");
    }

    private void validateLegacyMarker(JsonObject marker) {
        boolean automaticRestartArmed = requiredBoolean(marker, "automaticRestartArmed");
        boolean restartLoopStopped = requiredBoolean(marker, "restartLoopStopped");
        if (automaticRestartArmed == restartLoopStopped) {
            throw new JsonParseException("Legacy restart state is inconsistent");
        }
    }

    private void validateFailures(JsonArray failures) {
        if (failures.isEmpty()) {
            throw new JsonParseException("failures must not be empty");
        }
        for (JsonElement element : failures) {
            if (!element.isJsonObject()) {
                throw new JsonParseException("Each failure must be an object");
            }
            JsonObject failure = element.getAsJsonObject();
            requiredText(failure, "configuredName");
            requireNullableText(failure, "detectedName");
            requiredText(failure, "status");
        }
    }

    private static void requirePresent(JsonObject object, String name) {
        if (!object.has(name)) {
            throw new JsonParseException("Missing required property: " + name);
        }
    }

    private static String requiredText(JsonObject object, String name) {
        JsonPrimitive primitive = requiredPrimitive(object, name);
        if (!primitive.isString()) {
            throw new JsonParseException(name + " must be a string");
        }
        String value = primitive.getAsString().trim();
        if (value.isEmpty()) {
            throw new JsonParseException(name + " must not be blank");
        }
        return value;
    }

    private static void requireNullableText(JsonObject object, String name) {
        requirePresent(object, name);
        JsonElement value = object.get(name);
        if (value.isJsonNull()) {
            return;
        }
        requiredText(object, name);
    }

    private static void requiredInstant(JsonObject object, String name) {
        try {
            Instant.parse(requiredText(object, name));
        } catch (DateTimeParseException exception) {
            throw new JsonParseException(name + " must be an ISO-8601 instant", exception);
        }
    }

    private static boolean requiredBoolean(JsonObject object, String name) {
        JsonPrimitive primitive = requiredPrimitive(object, name);
        if (!primitive.isBoolean()) {
            throw new JsonParseException(name + " must be a boolean");
        }
        return primitive.getAsBoolean();
    }

    private static int requiredInteger(JsonObject object, String name) {
        JsonPrimitive primitive = requiredPrimitive(object, name);
        if (!primitive.isNumber()) {
            throw new JsonParseException(name + " must be an integer");
        }
        try {
            BigDecimal value = primitive.getAsBigDecimal().stripTrailingZeros();
            if (value.scale() > 0) {
                throw new ArithmeticException("fractional value");
            }
            return value.intValueExact();
        } catch (ArithmeticException exception) {
            throw new JsonParseException(name + " must be an integer", exception);
        }
    }

    private static JsonArray requiredArray(JsonObject object, String name) {
        requirePresent(object, name);
        JsonElement value = object.get(name);
        if (!value.isJsonArray()) {
            throw new JsonParseException(name + " must be an array");
        }
        return value.getAsJsonArray();
    }

    private static JsonPrimitive requiredPrimitive(JsonObject object, String name) {
        requirePresent(object, name);
        JsonElement value = object.get(name);
        if (!value.isJsonPrimitive()) {
            throw new JsonParseException(name + " must be a primitive value");
        }
        return value.getAsJsonPrimitive();
    }

    private void replaceAtomically(Path temporaryFile) throws IOException {
        try {
            mover.move(
                    temporaryFile,
                    file,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            mover.move(temporaryFile, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void quarantineCorruptFile(Exception cause) {
        Path backup = file.resolveSibling(
                "active-incident.corrupt-" + System.currentTimeMillis() + ".json");

        try {
            persistCorruptionSentinel();
        } catch (IOException sentinelException) {
            quarantineFailed = true;
            logger.log(
                    Level.SEVERE,
                    "[StartupGuardian] Incident marker was invalid, but the persistent "
                            + "corruption sentinel could not be written. The invalid marker "
                            + "was left in place so a later process can detect it.",
                    sentinelException);
            logger.log(
                    Level.FINE,
                    "[StartupGuardian] Incident marker validation failure.",
                    cause);
            return;
        }

        try {
            mover.move(file, backup, StandardCopyOption.REPLACE_EXISTING);
            logger.log(
                    Level.SEVERE,
                    "[StartupGuardian] Incident marker was invalid and was moved to {0}. "
                            + "Persistent emergency protection remains active until reset.",
                    backup);
        } catch (IOException backupException) {
            quarantineFailed = true;
            logger.log(
                    Level.SEVERE,
                    "[StartupGuardian] Incident marker was invalid and could not be quarantined. "
                            + "Persistent emergency protection remains active.",
                    backupException);
        }

        logger.log(
                Level.FINE,
                "[StartupGuardian] Incident marker validation failure.",
                cause);
    }

    private void persistCorruptionSentinel() throws IOException {
        Path parent = corruptionSentinel.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(
                corruptionSentinel,
                "StartupGuardian detected an untrusted incident marker. "
                        + "Clear only through an explicit successful reset or trusted save.\n",
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING);
    }

    private static Gson createGson() {
        JsonSerializer<Instant> serializer =
                (Instant source, Type type, JsonSerializationContext context) ->
                        new JsonPrimitive(source.toString());

        return new GsonBuilder()
                .registerTypeAdapter(Instant.class, serializer)
                .registerTypeAdapter(
                        Instant.class,
                        (com.google.gson.JsonDeserializer<Instant>)
                                (element, type, context) -> Instant.parse(element.getAsString()))
                .setPrettyPrinting()
                .create();
    }

    @FunctionalInterface
    interface FileMover {

        Path move(Path source, Path target, CopyOption... options) throws IOException;
    }
}
