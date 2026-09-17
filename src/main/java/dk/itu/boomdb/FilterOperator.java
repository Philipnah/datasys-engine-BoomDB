package dk.itu.boomdb;

import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Emits only child rows that satisfy one typed comparison predicate. */
public final class FilterOperator implements Operator {
    private static final Logger LOGGER = LoggerFactory.getLogger(FilterOperator.class);

    private final Operator child;
    private final Predicate predicate;
    private final int columnIndex;
    private final ColumnType columnType;

    private long rowsIn;
    private long rowsOut;

    /**
     * Creates a filter over a child operator.
     *
     * @param child source of rows in schema order
     * @param predicate comparison to apply
     * @param columns child schema in row order
     * @throws IllegalArgumentException if the predicate column is unknown
     */
    public FilterOperator(Operator child, Predicate predicate, List<ColumnSpec> columns) {
        this.child = Objects.requireNonNull(child, "child");
        this.predicate = Objects.requireNonNull(predicate, "predicate");
        Objects.requireNonNull(columns, "columns");
        this.columnIndex = columnIndex(columns, predicate.columnName());
        this.columnType = columns.get(columnIndex).type();
    }

    @Override
    public void open() {
        rowsIn = 0;
        rowsOut = 0;
        child.open();
    }

    @Override
    public Object[] next() {
        for (Object[] row; (row = child.next()) != null; ) {
            rowsIn++;
            if (StorageSupport.matches(columnType, predicate.comparison(),
                    row[columnIndex], predicate.constant())) {
                rowsOut++;
                return row;
            }
        }
        return null;
    }

    @Override
    public void close() {
        try {
            child.close();
        } finally {
            LOGGER.debug("rowsIn={} rowsOut={}", rowsIn, rowsOut);
        }
    }

    Operator child() {
        return child;
    }

    private static int columnIndex(List<ColumnSpec> columns, String columnName) {
        for (int index = 0; index < columns.size(); index++) {
            if (columns.get(index).name().equals(columnName)) {
                return index;
            }
        }
        throw new IllegalArgumentException("unknown column: " + columnName);
    }
}
