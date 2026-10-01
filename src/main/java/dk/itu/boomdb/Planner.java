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
     * @return a bare scan or a filter over a pruned scan
     * @throws IllegalArgumentException if a referenced table or column is unknown
     * @throws NullPointerException if {@code statement} is null
     */
    public Operator plan(SelectStatement statement) {
        Objects.requireNonNull(statement, "statement");
        TableCatalog table = storage.table(statement.tableName());
        List<PartitionMetadata> allPartitions = List.copyOf(table.partitions());

        if (statement.where().isEmpty()) {
            for (PartitionMetadata partition : allPartitions) {
                LOGGER.debug("table={} partition={} decision=READ",
                        clean(table.tableName()), partition.id());
            }
            storage.recordScanStats(new ScanStats(
                    allPartitions.size(), allPartitions.size(), 0));
            return new ScanOperator(storage, table, allPartitions);
        }

        Predicate predicate = statement.where().orElseThrow();
        int columnIndex = columnIndex(table.columns(), predicate.columnName());
        ColumnType columnType = table.columns().get(columnIndex).type();
        List<PartitionMetadata> surviving = new ArrayList<>();

        for (PartitionMetadata partition : allPartitions) {
            ColumnStatistics statistics = partition.statistics().get(columnIndex);
            Object min = StorageSupport.decodeStatistic(columnType, statistics.min());
            Object max = StorageSupport.decodeStatistic(columnType, statistics.max());
            boolean prune = StorageSupport.shouldPrune(
                    columnType, predicate.comparison(), predicate.constant(), min, max);
            LOGGER.debug("table={} column={} comparison={} const={} partition={} min={} max={} decision={}",
                    clean(table.tableName()), clean(predicate.columnName()), predicate.comparison(),
                    clean(predicate.constant()), partition.id(), clean(min), clean(max),
                    prune ? "PRUNED" : "READ");
            if (!prune) {
                surviving.add(partition);
            }
        }

        storage.recordScanStats(new ScanStats(allPartitions.size(), surviving.size(),
                allPartitions.size() - surviving.size()));
        return new FilterOperator(new ScanOperator(storage, table, surviving),
                predicate, table.columns());
    }

    private static int columnIndex(List<ColumnSpec> columns, String columnName) {
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
