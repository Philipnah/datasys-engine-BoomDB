package dk.itu.boomdb;

/** Indicates overlapping partition changes; DELETE retries before exposing a terminal conflict. */
public final class ConflictException extends RuntimeException {
    /**
     * Creates a conflict error with a message describing the competing versions.
     *
     * @param message description of the conflict
     */
    public ConflictException(String message) {
        super(message);
    }
}
