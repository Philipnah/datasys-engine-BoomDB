package dk.itu.boomdb;

/** Pull-based relational operator with an explicit execution lifecycle. */
public interface Operator {
    /** Prepares this operator to produce rows. */
    void open();

    /**
     * Returns the next row in schema order.
     *
     * @return the next row, or {@code null} when exhausted
     */
    Object[] next();

    /** Releases this operator and any child operator state. */
    void close();
}
