package dk.itu.boomdb;

/** Indicates that a table changed after a statement read its base version. */
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
