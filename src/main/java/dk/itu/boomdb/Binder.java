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
        /*
          We check if the table exists in a requireTable() function, which is called when we use 'engine.schema()'
          A snapshot lookup now performs that existence check and keeps all validation on one version.
        */
        switch (statement) {
            case CreateTableStatement create -> StorageEngine.validateColumns(create.columns());
            case CopyStatement copy -> bindPinned(copy, copy.tableName());
            case SelectStatement select -> bindPinned(select, select.tableName());
            case DeleteStatement delete -> bindPinned(delete, delete.tableName());
        }
    }

    /** Validates against the caller's pinned catalog without looking up newer metadata. */
    void bind(Statement statement, TableCatalog table) {
        switch (statement) {
            case CreateTableStatement create -> StorageEngine.validateColumns(create.columns());
            case CopyStatement copy -> requirePinnedTable(copy.tableName(), table);
            case SelectStatement select ->
                    bindPredicate("SELECT", select.tableName(), select.where(), table);
            case DeleteStatement delete ->
                    bindPredicate("DELETE", delete.tableName(), delete.where(), table);
        }
    }

    private void bindPinned(Statement statement, String tableName) {
        try (CatalogStore.Snapshot snapshot = engine.pin(tableName)) {
            bind(statement, snapshot.table());
        }
    }

    private static void bindPredicate(String operation, String tableName,
            Optional<Predicate> where, TableCatalog table) {
        requirePinnedTable(tableName, table);
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
                operation, tableName, predicate.columnName(), column.type(), predicate.constant());
    }

    private static void requirePinnedTable(String tableName, TableCatalog table) {
        Objects.requireNonNull(table, "table");
        if (!table.tableName().equals(tableName)) {
            throw new IllegalArgumentException("pinned catalog does not match table: " + tableName);
        }
    }
}
