package dk.itu.boomdb;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Builds executable operator trees and prunes partitions from bound selects. */
public final class Planner {
    private static final Logger LOGGER = LoggerFactory.getLogger(Planner.class);

    private final StorageEngine storage;
    private final JoinMode mode;
    private record Input(Operator operator, List<TableCatalog> layout, double estimatedRows) { }

    /**
     * Creates a planner backed by one storage catalog.
     *
     * @param storage storage and catalog used by generated scans
     * @throws NullPointerException if {@code storage} is null
     */
    public Planner(StorageEngine storage) {
        this(storage, JoinMode.OPTIMIZED);
    }

    /**
     * Creates a planner with the requested join policy.
     * @param storage storage and catalog used by generated scans
     * @param mode join planning policy
     * @throws NullPointerException if either argument is null
     */
    public Planner(StorageEngine storage, JoinMode mode) {
        this.storage = Objects.requireNonNull(storage, "storage");
        this.mode = Objects.requireNonNull(mode, "mode");
    }

    /**
     * Plans one already-bound select statement.
     *
     * @param statement select to plan
     * @return an executable plan whose output follows FROM order
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
        Map<String, Input> pipelines = new LinkedHashMap<>();
        int totalPartitions = 0;
        int partitionsRead = 0;
        for (TableCatalog table : tables) {
            List<Predicate> filters = statement.filters().stream()
                    .filter(filter -> resolver.resolve(filter.columnName()).tableName()
                            .equals(table.tableName())).toList();
            pipelines.put(table.tableName(), planTable(table, filters, resolver));
            totalPartitions += table.partitions().size();
            partitionsRead += storage.lastScanStats().partitionsRead();
        }
        storage.recordScanStats(new ScanStats(
                totalPartitions, partitionsRead, totalPartitions - partitionsRead));

        Map<String, Double> sizes = new LinkedHashMap<>();
        pipelines.forEach((name, input) -> sizes.put(name, input.estimatedRows()));
        GreedyJoinOrder.Order order = mode == JoinMode.OPTIMIZED
                ? GreedyJoinOrder.choose(statement, tables, sizes) : null;
        List<String> names = order == null ? statement.tableNames() : order.tableNames();
        LOGGER.debug("statement=SELECT joinOrder={} mode={}", clean(String.join(";", names)),
                mode.name().toLowerCase(java.util.Locale.ROOT));
        Input joined = pipelines.get(names.getFirst());
        for (int index = 1; index < names.size(); index++) {
            String nextTable = names.get(index);
            ColumnResolver.ResolvedColumn buildColumn = null;
            ColumnResolver.ResolvedColumn probeColumn = null;
            List<JoinPredicate> candidates = order == null ? statement.joins()
                    : List.of(order.steps().get(index - 1).predicate());
            for (JoinPredicate join : candidates) {
                var left = resolver.resolve(join.leftColumn());
                var right = resolver.resolve(join.rightColumn());
                // Orient the predicate from the joined prefix toward the new table.
                if (left.tableName().equals(nextTable)) {
                    var swap = left;
                    left = right;
                    right = swap;
                }
                if (right.tableName().equals(nextTable)
                        && names.subList(0, index).contains(left.tableName())) {
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
            Input next = pipelines.get(nextTable);
            TableCatalog leftTable = tables.get(statement.tableNames().indexOf(buildColumn.tableName()));
            TableCatalog rightTable = tables.get(statement.tableNames().indexOf(probeColumn.tableName()));
            double estimate = order == null ? CardinalityEstimator.joinRows(joined.estimatedRows(), next.estimatedRows(),
                    CardinalityEstimator.effectiveNdv(leftTable, buildColumn.columnIndex()),
                    CardinalityEstimator.effectiveNdv(rightTable, probeColumn.columnIndex()))
                    : order.steps().get(index - 1).estimatedRows();
            boolean swap = mode == JoinMode.OPTIMIZED && next.estimatedRows() < joined.estimatedRows();
            Input build = swap ? next : joined;
            Input probe = swap ? joined : next;
            String buildName = swap ? probeColumn.tableName() : buildColumn.tableName();
            int buildLocal = swap ? probeColumn.columnIndex() : buildColumn.columnIndex();
            String probeName = swap ? buildColumn.tableName() : probeColumn.tableName();
            int probeLocal = swap ? buildColumn.columnIndex() : probeColumn.columnIndex();
            List<TableCatalog> layout = new ArrayList<>(build.layout());
            layout.addAll(probe.layout());
            Operator join = new HashJoinOperator(build.operator(), probe.operator(),
                    offset(build.layout(), buildName, buildLocal), offset(probe.layout(), probeName, probeLocal),
                    index, names.subList(0, index + 1), tableNames(build.layout()), tableNames(probe.layout()), estimate);
            joined = new Input(join, List.copyOf(layout), estimate);
        }
        if (joined.layout().equals(tables)) return joined.operator();
        // Map by table and local position: unreferenced column names may be duplicated.
        int[] mapping = new int[tables.stream().mapToInt(t -> t.columns().size()).sum()];
        int position = 0;
        for (TableCatalog table : tables) {
            for (int local = 0; local < table.columns().size(); local++) {
                mapping[position++] = offset(joined.layout(), table.tableName(), local);
            }
        }
        return new ColumnOrderOperator(joined.operator(), mapping);
    }

    private static List<String> tableNames(List<TableCatalog> layout) {
        return layout.stream().map(TableCatalog::tableName).toList();
    }

    /** Finds a key or output column without relying on globally unique column names. */
    private static int offset(List<TableCatalog> layout, String tableName, int local) {
        int offset = 0;
        for (TableCatalog table : layout) {
            if (table.tableName().equals(tableName)) return offset + local;
            offset += table.columns().size();
        }
        throw new IllegalArgumentException("table absent from layout: " + tableName);
    }

    /** Applies every local filter to both partition pruning and the table's row pipeline. */
    private Input planTable(TableCatalog table, List<Predicate> filters, ColumnResolver resolver) {
        List<PartitionMetadata> allPartitions = List.copyOf(table.partitions());
        if (filters.isEmpty()) {
            for (PartitionMetadata partition : allPartitions) {
                LOGGER.debug("table={} partition={} decision=READ",
                        clean(table.tableName()), partition.id());
            }
            storage.recordScanStats(new ScanStats(
                    allPartitions.size(), allPartitions.size(), 0));
            return new Input(new ScanOperator(storage, table, allPartitions), List.of(table),
                    CardinalityEstimator.baseRows(allPartitions));
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
        double estimate = CardinalityEstimator.baseRows(surviving);
        for (Predicate filter : filters) {
            int column = resolver.resolve(filter.columnName()).columnIndex();
            estimate = CardinalityEstimator.filterRows(estimate, filter.comparison(),
                    CardinalityEstimator.effectiveNdv(table, column));
        }
        return new Input(pipeline, List.of(table), estimate);
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
        return String.valueOf(value).replace(',', ';').replace('"', '\'')
                .replace('\n', ' ').replace('\r', ' ');
    }
}
