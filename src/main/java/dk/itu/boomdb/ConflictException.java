package dk.itu.boomdb;

import java.util.List;

/** Indicates overlapping partition changes; DELETE retries before exposing a terminal conflict. */
public final class ConflictException extends RuntimeException {
    private final transient Details details;

    /**
     * Creates a conflict error with a message describing the competing versions.
     *
     * @param message description of the conflict
     */
    public ConflictException(String message) {
        this(message, null);
    }

    ConflictException(String message, Details details) {
        super(message);
        this.details = details;
    }

    /** Returns the detected overlap, if this exception originated in the catalog. */
    Details details() {
        return details;
    }

    /** Captures the versions checked at commit time and the conflicting IDs in the base version. */
    record Details(long baseVersion, long currentVersion, List<Integer> partitionIds) {}
}
