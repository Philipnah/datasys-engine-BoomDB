package dk.itu.boomdb;

/** Reports the source position of the first invalid SQL token. */
public final class SqlParseException extends RuntimeException {
    private final int line;
    private final int column;

    SqlParseException(String message, int line, int column) {
        super(message + " at line " + line + ", column " + column);
        this.line = line;
        this.column = column;
    }

    /**
     * Returns the 1-based source line.
     *
     * @return source line
     */
    public int line() {
        return line;
    }

    /**
     * Returns the 0-based source column.
     *
     * @return source column
     */
    public int column() {
        return column;
    }
}
