package dev.p2wn.startupguardian;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

interface IncidentRepository {

    Optional<Incident> load();

    void save(Incident incident) throws IOException;

    void clear() throws IOException;

    boolean corrupted();

    Path path();

    default boolean hasActiveIncident() {
        return load().isPresent();
    }
}
