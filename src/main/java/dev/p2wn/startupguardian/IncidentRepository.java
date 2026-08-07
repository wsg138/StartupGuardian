package dev.p2wn.startupguardian;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

interface IncidentRepository {

    Optional<Incident> load();

    /**
     * Persists ordinary trusted incident state without resolving corruption.
     * Implementations must reject or preserve any unresolved corruption state.
     */
    void save(Incident incident) throws IOException;

    /**
     * Explicitly clears active incident and corruption state.
     */
    void clear() throws IOException;

    boolean corrupted();

    Path path();

    default boolean hasActiveIncident() {
        return load().isPresent() || corrupted();
    }
}
