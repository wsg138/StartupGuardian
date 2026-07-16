package dev.p2wn.startupguardian;

import com.google.gson.*;
import java.io.*;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.Optional;

public final class IncidentStore {
    private final Path file; private volatile boolean corrupted;
    private final Gson gson = new GsonBuilder().registerTypeAdapter(Instant.class, new JsonSerializer<Instant>() { public JsonElement serialize(Instant x, Type t, JsonSerializationContext c) { return new JsonPrimitive(x.toString()); }}).registerTypeAdapter(Instant.class, new JsonDeserializer<Instant>() { public Instant deserialize(JsonElement x, Type t, JsonDeserializationContext c) { return Instant.parse(x.getAsString()); }}).setPrettyPrinting().create();
    public IncidentStore(Path dataFolder) { file = dataFolder.resolve("active-incident.json"); }
    public Path path() { return file; }
    public Optional<Incident> load(java.util.logging.Logger log) {
        if (!Files.exists(file)) return Optional.empty();
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) { return Optional.ofNullable(gson.fromJson(r, Incident.class)); }
        catch (Exception ex) { corrupted = true; try { Files.move(file, file.resolveSibling("active-incident.corrupt-" + System.currentTimeMillis() + ".json"), StandardCopyOption.REPLACE_EXISTING); } catch (IOException ignored) {} log.severe("[StartupGuardian] Incident marker was malformed and was backed up; no automatic restart will be armed until an incident is reset or a healthy startup occurs."); return Optional.empty(); }
    }
    public void save(Incident incident) throws IOException { Files.createDirectories(file.getParent()); Path temp = file.resolveSibling(file.getFileName() + ".tmp"); Files.writeString(temp, gson.toJson(incident), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING); try { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); } catch (AtomicMoveNotSupportedException ex) { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING); } }
    public void clear() throws IOException { Files.deleteIfExists(file); corrupted = false; }
    public boolean corrupted() { return corrupted; }
}
