package dk.itu.boomdb;

import java.util.List;
import java.util.Optional;

/**
 * A parsed {@code SELECT *} statement.
 *
 * @param tableNames source tables in output column order
 * @param filters column-to-constant comparisons, combined with AND
 * @param joins column-to-column equalities, combined with AND
 */
public record SelectStatement(List<String> tableNames, List<Predicate> filters,
        List<JoinPredicate> joins) implements Statement {
    /** Copies the AST lists so a statement cannot change after binding. */
    public SelectStatement {
        tableNames = List.copyOf(tableNames);
        filters = List.copyOf(filters);
        joins = List.copyOf(joins);
        if (tableNames.isEmpty()) {
            throw new IllegalArgumentException("SELECT requires at least one table");
        }
        if (tableNames.stream().distinct().count() != tableNames.size()) {
            throw new IllegalArgumentException("duplicate FROM table; self-joins require aliases");
        }
    }

    /**
     * Creates a single-table select using the original API.
     *
     * @param tableName source table
     * @param where optional row predicate
     */
    public SelectStatement(String tableName, Optional<Predicate> where) {
        this(List.of(tableName), where.stream().toList(), List.of());
    }

    /**
     * Returns the predicate for a select representable by the original API.
     *
     * @return the optional single-table predicate
     * @throws IllegalStateException if the statement uses multiple tables or predicates
     */
    public Optional<Predicate> where() {
        if (tableNames.size() != 1 || filters.size() > 1 || !joins.isEmpty()) {
            throw new IllegalStateException("use filters() and joins() for a multi-predicate SELECT");
        }
        return filters.stream().findFirst();
    }
}
