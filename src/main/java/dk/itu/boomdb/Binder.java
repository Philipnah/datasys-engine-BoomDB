package dk.itu.boomdb;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

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
        if (statement instanceof CreateTableStatement) {
            bind(statement, null);
        } else {
            try (CatalogStore.Snapshot snapshot = engine.snapshot(tableName(statement))) {
                bind(statement, snapshot.table());
            }
        }
    }

    /** Validates all names and types using the catalog pinned before binding. */
    void bind(Statement statement, TableCatalog table) {
        /*
          We check if the table exists in a requireTable() function, which is called when we use 'engine.schema()'
          SQL execution now passes its pinned catalog instead of looking up the schema again.
        */
        switch (statement) {
            case CreateTableStatement create -> StorageEngine.validateColumns(create.columns());
            case CopyStatement copy -> Objects.requireNonNull(table, "table");
            case SelectStatement select -> bindPredicate("SELECT", table, select.where());
            case DeleteStatement delete -> bindPredicate("DELETE", table, delete.where());
        }
    }

    static String tableName(Statement statement) {
        return switch (statement) {
            case CreateTableStatement create -> create.tableName();
            case CopyStatement copy -> copy.tableName();
            case SelectStatement select -> select.tableName();
            case DeleteStatement delete -> delete.tableName();
        };
    }

    private void bindPredicate(String operation, TableCatalog table, Optional<Predicate> where) {
        List<ColumnSpec> columns = table.columns();
        if (where.isEmpty()) {
            return;
        }

        Predicate predicate = where.orElseThrow();
        ColumnSpec column = columns.stream()
                .filter(candidate -> candidate.name().equals(predicate.columnName()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "unknown column: " + predicate.columnName()));
        StorageEngine.requireConstantType(
                operation, table.tableName(), predicate.columnName(), column.type(), predicate.constant());
    }
}
