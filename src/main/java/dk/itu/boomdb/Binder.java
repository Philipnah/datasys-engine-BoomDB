package dk.itu.boomdb;

import java.util.List;
import java.util.Objects;

/** Validates parsed SQL statements against a storage catalogue. */
public final class Binder {
    private final StorageEngine engine;

    /**
     * Creates a binder backed by an engine's catalogue.
     *
     * @param engine storage engine used for schema lookups
     * @throws NullPointerException if {@code engine} is null
     */
    public Binder(StorageEngine engine) {
        this.engine = Objects.requireNonNull(engine, "engine");
    }

    /**
     * Validates a statement and stops at the first violation.
     *
     * @param statement statement to validate
     * @throws IllegalArgumentException if a referenced name or value type is invalid
     */
    public void bind(Statement statement) {
        /*
          We check if the table exists in a requireTable() function, which is called when we use 'engine.schema()'
        */
        switch (statement) {
            case CreateTableStatement create -> StorageEngine.validateColumns(create.columns());
            case CopyStatement copy -> engine.schema(copy.tableName());
            case SelectStatement select -> bindSelect(select);
        }
    }

    private void bindSelect(SelectStatement select) {
        List<ColumnSpec> columns = engine.schema(select.tableName());
        if (select.where().isEmpty()) {
            return;
        }

        Predicate predicate = select.where().orElseThrow();
        ColumnSpec column = columns.stream()
                .filter(candidate -> candidate.name().equals(predicate.columnName()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "unknown column: " + predicate.columnName()));
        StorageEngine.requireConstantType(
                select.tableName(), predicate.columnName(), column.type(), predicate.constant());
    }
}
