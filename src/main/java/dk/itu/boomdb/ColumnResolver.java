package dk.itu.boomdb;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Resolves unqualified columns against FROM-ordered schemas for binding and planning. */
final class ColumnResolver {
    private final Map<String, ResolvedColumn> columns = new HashMap<>();
    private final Set<String> ambiguous = new HashSet<>();

    ColumnResolver(List<TableCatalog> tables) {
        int offset = 0;
        for (TableCatalog table : tables) {
            for (int index = 0; index < table.columns().size(); index++) {
                ColumnSpec column = table.columns().get(index);
                ResolvedColumn resolved = new ResolvedColumn(
                        table.tableName(), index, offset + index, column.type());
                if (columns.putIfAbsent(column.name(), resolved) != null) {
                    ambiguous.add(column.name());
                }
            }
            offset += table.columns().size();
        }
    }

    /** Rejects ambiguity only when that name is referenced; unique names remain usable. */
    ResolvedColumn resolve(String name) {
        if (ambiguous.contains(name)) {
            throw new IllegalArgumentException("ambiguous column: " + name);
        }
        ResolvedColumn column = columns.get(name);
        if (column == null) {
            throw new IllegalArgumentException("unknown column: " + name);
        }
        return column;
    }

    /** Positions are table-local for scans and FROM-concatenated for intermediate rows. */
    record ResolvedColumn(String tableName, int columnIndex, int joinedIndex, ColumnType type) { }
}
