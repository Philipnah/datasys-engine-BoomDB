package dk.itu.boomdb;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Builds executable operator trees and prunes partitions from bound selects. */
public final class Planner {
    private static final Logger LOGGER = LoggerFactory.getLogger(Planner.class);

    private final StorageEngine storage;

    /**
     * Creates a planner backed by one storage catalog.
     *
     * @param storage storage and catalog used by generated scans
     * @throws NullPointerException if {@code storage} is null
     */
    public Planner(StorageEngine storage) {
        this.storage = Objects.requireNonNull(storage, "storage");
    }

    /**
     * Plans one already-bound select statement.
     *
     * @param statement select to plan
     * @return scans and filters joined left to right in FROM order
     * @throws IllegalArgumentException if a name is invalid, a table is disconnected,
     *         or a new table has more than one connecting predicate
     * @throws NullPointerException if {@code statement} is null
     */
    public Operator plan(SelectStatement statement) {
        Objects.requireNonNull(statement, "statement");
        return plan(statement, statement.tableNames().stream().map(storage::table).toList());
    }

    /** Plans against the statement's pinned catalog without another metadata lookup. */
    Operator plan(SelectStatement statement, TableCatalog table) {
        return plan(statement, List.of(table));
    }

    /** Plans against every pinned table, with no metadata lookup during the fold. */
    Operator plan(SelectStatement statement, List<TableCatalog> tables) {
        ColumnResolver resolver = new ColumnResolver(tables);
        List<Operator> pipelines = new ArrayList<>();
        int totalPartitions = 0;
        int partitionsRead = 0;
        for (TableCatalog table : tables) {
            List<Predicate> filters = statement.filters().stream()
                    .filter(filter -> resolver.resolve(filter.columnName()).tableName()
                            .equals(table.tableName())).toList();
            pipelines.add(planTable(table, filters, resolver));
            totalPartitions += table.partitions().size();
            partitionsRead += storage.lastScanStats().partitionsRead();
        }
        storage.recordScanStats(new ScanStats(
                totalPartitions, partitionsRead, totalPartitions - partitionsRead));

        Operator joined = pipelines.getFirst();
        for (int index = 1; index < tables.size(); index++) {
            String nextTable = tables.get(index).tableName();
            ColumnResolver.ResolvedColumn buildColumn = null;
            ColumnResolver.ResolvedColumn probeColumn = null;
            for (JoinPredicate join : statement.joins()) {
                var left = resolver.resolve(join.leftColumn());
                var right = resolver.resolve(join.rightColumn());
                // Either operand can name the new table; only earlier tables may build.
                if (left.tableName().equals(nextTable)) {
                    var swap = left;
                    left = right;
                    right = swap;
                }
                if (right.tableName().equals(nextTable)
                        && statement.tableNames().indexOf(left.tableName()) < index) {
                    if (buildColumn != null) {
                        throw new IllegalArgumentException("multiple connecting join predicates for table: "
                                + nextTable + "; only one predicate per new table is supported");
                    }
                    buildColumn = left;
                    probeColumn = right;
                }
            }
            if (buildColumn == null) {
                throw new IllegalArgumentException("no join connects table " + nextTable
                        + " to earlier tables; reorder the FROM list to avoid a cross product");
            }
            joined = new HashJoinOperator(joined, pipelines.get(index),
                    buildColumn.joinedIndex(), probeColumn.columnIndex());
        }
        return joined;
    }

    /** Applies every local filter to both partition pruning and the table's row pipeline. */
    private Operator planTable(TableCatalog table, List<Predicate> filters, ColumnResolver resolver) {
        List<PartitionMetadata> allPartitions = List.copyOf(table.partitions());
        if (filters.isEmpty()) {
            for (PartitionMetadata partition : allPartitions) {
                LOGGER.debug("table={} partition={} decision=READ",
                        clean(table.tableName()), partition.id());
            }
            storage.recordScanStats(new ScanStats(
                    allPartitions.size(), allPartitions.size(), 0));
            return new ScanOperator(storage, table, allPartitions);
        }

        List<PartitionMetadata> surviving = new ArrayList<>();
        for (PartitionMetadata partition : allPartitions) {
            boolean prune = false;
            for (Predicate predicate : filters) {
                var column = resolver.resolve(predicate.columnName());
                ColumnStatistics statistics = partition.statistics().get(column.columnIndex());
                Object min = StorageSupport.decodeStatistic(column.type(), statistics.min());
                Object max = StorageSupport.decodeStatistic(column.type(), statistics.max());
                prune = StorageSupport.shouldPrune(
                        column.type(), predicate.comparison(), predicate.constant(), min, max);
                LOGGER.debug("table={} column={} comparison={} const={} partition={} min={} max={} decision={}",
                        clean(table.tableName()), clean(predicate.columnName()), predicate.comparison(),
                        clean(predicate.constant()), partition.id(), clean(min), clean(max),
                        prune ? "PRUNED" : "READ");
                if (prune) {
                    break;
                }
            }
            if (!prune) {
                surviving.add(partition);
            }
        }

        storage.recordScanStats(new ScanStats(allPartitions.size(), surviving.size(),
                allPartitions.size() - surviving.size()));
        Operator pipeline = new ScanOperator(storage, table, surviving);
        for (Predicate predicate : filters) {
            pipeline = new FilterOperator(pipeline, predicate, table.columns());
        }
        return pipeline;
    }

    static int columnIndex(List<ColumnSpec> columns, String columnName) {
        for (int index = 0; index < columns.size(); index++) {
            if (columns.get(index).name().equals(columnName)) {
                return index;
            }
        }
        throw new IllegalArgumentException("unknown column: " + columnName);
    }

    private static String clean(Object value) {
        return String.valueOf(value).replace(',', ';').replace('\n', ' ').replace('\r', ' ');
    }
}
