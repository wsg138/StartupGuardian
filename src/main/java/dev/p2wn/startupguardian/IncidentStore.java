package dev.p2wn.startupguardian;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializer;
import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class IncidentStore {

    private static final String ACTIVE_INCIDENT_FILE = "active-incident.json";

    private final Path file;
    private final Gson gson;

    private Optional<Incident> cachedIncident = Optional.empty();
    private boolean loaded;
    private boolean corruptionDetected;

    public IncidentStore(Path dataFolder) {
        file = Objects.requireNonNull(dataFolder, "dataFolder")
                .resolve(ACTIVE_INCIDENT_FILE);
        gson = createGson();
    }

    public Path path() {
        return file;
    }

    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    public synchronized Optional<Incident> load(Logger logger) {
        Objects.requireNonNull(logger, "logger");
        if (loaded) {
            return cachedIncident;
        }

        loaded = true;
        if (!Files.exists(file)) {
            return cachedIncident;
        }

        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Incident incident = gson.fromJson(reader, Incident.class);
            if (incident == null) {
                throw new IllegalStateException("Incident marker contained null");
            }
            cachedIncident = Optional.of(incident);
        } catch (IOException | RuntimeException exception) {
            corruptionDetected = true;
            cachedIncident = Optional.empty();
            quarantineCorruptFile(logger, exception);
        }

        return cachedIncident;
    }

    public synchronized boolean hasActiveIncident(Logger logger) {
        return load(logger).isPresent();
    }

    public synchronized void save(Incident incident) throws IOException {
        Objects.requireNonNull(incident, "incident");
        Files.createDirectories(file.getParent());

        Path temporaryFile = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.writeString(
                    temporaryFile,
                    gson.toJson(incident),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);
            replaceAtomically(temporaryFile);
            cachedIncident = Optional.of(incident);
            loaded = true;
        } finally {
            Files.deleteIfExists(temporaryFile);
        }
    }

    public synchronized void clear() throws IOException {
        Files.deleteIfExists(file);
        cachedIncident = Optional.empty();
        loaded = true;
        corruptionDetected = false;
    }

    public synchronized boolean corrupted() {
        return corruptionDetected;
    }

    private void replaceAtomically(Path temporaryFile) throws IOException {
        try {
            Files.move(
                    temporaryFile,
                    file,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(
                    temporaryFile,
                    file,
                    StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void quarantineCorruptFile(Logger logger, Exception cause) {
        Path backup = file.resolveSibling(
                "active-incident.corrupt-" + System.currentTimeMillis() + ".json");

        try {
            Files.move(
                    file,
                    backup,
                    StandardCopyOption.REPLACE_EXISTING);
            logger.log(
                    Level.SEVERE,
                    "[StartupGuardian] Incident marker was invalid and was moved to {0}. "
                            + "Automatic restart remains suppressed until reset or recovery.",
                    backup);
        } catch (IOException backupException) {
            logger.log(
                    Level.SEVERE,
                    "[StartupGuardian] Incident marker was invalid and could not be quarantined. "
                            + "Automatic restart remains suppressed.",
                    backupException);
        }

        logger.log(
                Level.FINE,
                "[StartupGuardian] Incident marker parse failure.",
                cause);
    }

    private static Gson createGson() {
        JsonSerializer<Instant> serializer =
                (Instant source, Type type, com.google.gson.JsonSerializationContext context) ->
                        new JsonPrimitive(source.toString());

        JsonDeserializer<Instant> deserializer =
                (element, type, context) -> Instant.parse(element.getAsString());

        return new GsonBuilder()
                .registerTypeAdapter(Instant.class, serializer)
                .registerTypeAdapter(Instant.class, deserializer)
                .setPrettyPrinting()
                .create();
    }
}
