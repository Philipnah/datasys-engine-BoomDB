package dk.itu.boomdb;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/** Stores typed tables in persistent PAX partitions and scans them with min/max pruning. */
public final class StorageEngine {
    private static final Logger LOGGER = LoggerFactory.getLogger(StorageEngine.class);
    private static final int DEFAULT_MAX_ROWS_PER_PARTITION = 10_000;

    private final CatalogStore catalogStore;
    private final Path partitionDirectory;
    private final int maxRowsPerPartition;
    private ScanStats lastScanStats = new ScanStats(0, 0, 0);

    /**
     * Opens or creates persistent storage with partitions of at most 10,000 rows.
     *
     * @param dataDirectory root directory for catalogs and partition files
     */
    public StorageEngine(Path dataDirectory) {
        this(dataDirectory, DEFAULT_MAX_ROWS_PER_PARTITION);
    }

    /**
     * Opens or creates persistent storage with a configured partition size.
     *
     * @param dataDirectory root directory for catalogs and partition files
     * @param maxRowsPerPartition maximum number of rows in each partition
     * @throws IllegalArgumentException if {@code maxRowsPerPartition} is not positive
     */
    public StorageEngine(Path dataDirectory, int maxRowsPerPartition) {
        if (maxRowsPerPartition <= 0) {
            throw new IllegalArgumentException("maxRowsPerPartition must be positive");
        }
        
        Path root = dataDirectory.toAbsolutePath().normalize();
        this.maxRowsPerPartition = maxRowsPerPartition;
        catalogStore = new CatalogStore(root);
        partitionDirectory = root.resolve("data");
        try {
            Files.createDirectories(partitionDirectory);
        } catch (IOException error) {
            throw new UncheckedIOException("cannot create partition directory", error);
        }
        initializeLoggingContext();
    }

    /**
     * Creates and persists an empty table schema.
     *
     * @param tableName table to create
     * @param columns ordered table columns
     * @throws IllegalArgumentException if the table exists or the schema is invalid
     */
    public void createTable(String tableName, List<ColumnSpec> columns) {
        long started = System.nanoTime();
        try {
            if (catalogStore.load(tableName).isPresent()) {
                throw new IllegalArgumentException("table already exists: " + tableName);
            }

            validateColumns(columns);
            catalogStore.save(new TableCatalog(tableName, List.copyOf(columns), false, List.of()));
            
            LOGGER.debug("table={} columns={} durationMs={}", clean(tableName), columns.size(),
                    elapsedMillis(started));
        } catch (RuntimeException error) {
            logFailure("CREATE", tableName, started, error);
            throw error;
        }
    }

    /**
     * Returns a table's schema in column order.
     *
     * @param tableName table whose schema to return
     * @return immutable ordered column definitions
     * @throws IllegalArgumentException if the table is unknown
     */
    public List<ColumnSpec> schema(String tableName) {
        return List.copyOf(requireTable(tableName).columns());
    }

    /**
     * Loads one headerless CSV file into newly written table partitions.
     *
     * @param tableName destination table
     * @param csvFilePath path to the headerless ASCII CSV input
     * @throws IllegalArgumentException if the table or a CSV row is invalid
     * @throws UnsupportedOperationException if the table has already been copied into
     */
    public void copyFile(String tableName, String csvFilePath) {
        long logStarted = System.nanoTime();
        try {
            TableCatalog table = requireTable(tableName);
            if (table.copied()) {
                throw new UnsupportedOperationException(
                        "table already contains copied data: " + tableName);
            }
            Path csv = Path.of(csvFilePath);
            List<Object[]> rows = readCsv(csv, table.columns());
            List<PartitionMetadata> partitions = new ArrayList<>();
            
            // the start variable is always the first row of the partition
            for (int start = 0; start < rows.size(); start += maxRowsPerPartition) {
                // the end variable is always the last row of the partition
                int end = Math.min(start + maxRowsPerPartition, rows.size());

                List<Object[]> partitionRows = rows.subList(start, end);
                int partitionId = partitions.size();
                String fileName = tableName + "-" + partitionId + ".bin";
                
                // Compute statistics for each column in the partition
                List<ColumnStatistics> statistics = statisticsFor(
                        tableName, partitionId, table.columns(), partitionRows);

                // Write the partition to disk
                StorageSupport.writePartition(
                        partitionPath(fileName), table.columns(), partitionRows);

                partitions.add(new PartitionMetadata(
                        partitionId, fileName, partitionRows.size(), statistics));
            }

            catalogStore.save(new TableCatalog(
                    table.tableName(), table.columns(), true, List.copyOf(partitions)));
            LOGGER.debug("table={} file={} rows={} partitions={} durationMs={}",
                    clean(tableName), clean(csv.getFileName()), rows.size(), partitions.size(),
                    elapsedMillis(logStarted));
        } catch (IOException error) {
            UncheckedIOException unchecked =
                    new UncheckedIOException("cannot write partitions for " + csvFilePath, error);
            logFailure("COPY", tableName, logStarted, unchecked);
            throw unchecked;
        } catch (RuntimeException error) {
            logFailure("COPY", tableName, logStarted, error);
            throw error;
        }
    }

    /**
     * Returns rows matching a typed comparison in their persisted order.
     *
     * @param tableName table to scan
     * @param columnName predicate column
     * @param comparison comparison to apply
     * @param constant exactly typed predicate constant
     * @return matching rows in schema column order
     * @throws IllegalArgumentException if a name or the constant type is invalid
     */
    public List<Object[]> select(String tableName, String columnName,
            Comparison comparison, Object constant) {
        long started = System.nanoTime();
        try {
            TableCatalog table = requireTable(tableName);
            int columnIndex = columnIndex(table, columnName);
            ColumnSpec column = table.columns().get(columnIndex);
            requireConstantType(column.type(), constant);
            if (comparison == null) {
                throw new IllegalArgumentException("comparison is required");
            }

            List<Object[]> result = new ArrayList<>();
            int partitionsRead = 0;
            int partitionsPruned = 0;
            for (PartitionMetadata partition : table.partitions()) {
                ColumnStatistics statistics = partition.statistics().get(columnIndex);
                Object min = decodeStatistic(column.type(), statistics.min());
                Object max = decodeStatistic(column.type(), statistics.max());
                boolean prune = StorageSupport.shouldPrune(
                        column.type(), comparison, constant, min, max);
                LOGGER.debug("table={} column={} comparison={} const={} partition={} min={} max={} decision={}",
                        clean(tableName), clean(columnName), comparison, clean(constant),
                        partition.id(), clean(min), clean(max), prune ? "PRUNED" : "READ");
                if (prune) {
                    partitionsPruned++;
                    continue;
                }

                partitionsRead++;
                try {
                    for (Object[] row : StorageSupport.readPartition(
                            partitionPath(partition.fileName()), table.columns())) {
                        if (StorageSupport.matches(
                                column.type(), comparison, row[columnIndex], constant)) {
                            result.add(row);
                        }
                    }
                } catch (IOException error) {
                    throw new UncheckedIOException(
                            "cannot read partition " + partition.fileName(), error);
                }
            }

            lastScanStats = new ScanStats(
                    table.partitions().size(), partitionsRead, partitionsPruned);
            LOGGER.debug("table={} column={} comparison={} const={} partitionsRead={} partitionsPruned={} rowsOut={} durationMs={}",
                    clean(tableName), clean(columnName), comparison, clean(constant),
                    partitionsRead, partitionsPruned, result.size(), elapsedMillis(started));
            return result;
        } catch (RuntimeException error) {
            logFailure("SELECT", tableName, started, error);
            throw error;
        }
    }

    /**
     * Returns statistics from the most recently completed select call.
     *
     * @return latest partition scan statistics
     */
    public ScanStats lastScanStats() {
        return lastScanStats;
    }

    static void validateColumns(List<ColumnSpec> columns) {
        if (columns == null || columns.isEmpty()) {
            throw new IllegalArgumentException("table must have at least one column");
        }
        Set<String> names = new HashSet<>();
        for (ColumnSpec column : columns) {
            if (column == null || column.name() == null || column.name().isBlank()
                    || column.type() == null) {
                throw new IllegalArgumentException("column name and type are required");
            }
            if (!names.add(column.name())) {
                throw new IllegalArgumentException("duplicate column: " + column.name());
            }
        }
    }

    private TableCatalog requireTable(String tableName) {
        return catalogStore.load(tableName)
                .orElseThrow(() -> new IllegalArgumentException("unknown table: " + tableName));
    }

    private static int columnIndex(TableCatalog table, String columnName) {
        for (int index = 0; index < table.columns().size(); index++) {
            if (table.columns().get(index).name().equals(columnName)) {
                return index;
            }
        }
        throw new IllegalArgumentException("unknown column: " + columnName);
    }

    static void requireConstantType(ColumnType type, Object constant) {
        Class<?> expected = switch (type) {
            case STRING -> String.class;
            case LONG -> Long.class;
            case DOUBLE -> Double.class;
        };
        if (constant == null || constant.getClass() != expected) {
            throw new IllegalArgumentException(
                    "expected " + expected.getSimpleName() + " constant for " + type);
        }
    }

    private static Object decodeStatistic(ColumnType type, String value) {
        return switch (type) {
            case STRING -> value;
            case LONG -> Long.valueOf(value);
            case DOUBLE -> Double.valueOf(value);
        };
    }

    private static List<Object[]> readCsv(Path csv, List<ColumnSpec> columns) {
        List<Object[]> rows = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(csv, StandardCharsets.US_ASCII)) {
            String line;
            int lineNumber = 1;
            while ((line = reader.readLine()) != null) {
                rows.add(StorageSupport.parseCsvLine(csv, lineNumber, line, columns));
                lineNumber++;
            }
            return rows;
        } catch (IOException error) {
            throw new UncheckedIOException("cannot read CSV " + csv, error);
        }
    }

    private static List<ColumnStatistics> statisticsFor(String tableName, int partitionId,
            List<ColumnSpec> columns, List<Object[]> rows) {
        List<ColumnStatistics> statistics = new ArrayList<>(columns.size());
        for (int columnIndex = 0; columnIndex < columns.size(); columnIndex++) {
            ColumnSpec column = columns.get(columnIndex);
            List<Object> values = new ArrayList<>(rows.size());
            for (Object[] row : rows) {
                values.add(row[columnIndex]);
            }
            MinMax minMax = StorageSupport.minMax(column.type(), values);
            statistics.add(new ColumnStatistics(column.name(),
                    String.valueOf(minMax.min()), String.valueOf(minMax.max())));
            LOGGER.debug("table={} partition={} column={} min={} max={}",
                    clean(tableName), partitionId, clean(column.name()),
                    clean(minMax.min()), clean(minMax.max()));
        }
        return List.copyOf(statistics);
    }

    private Path partitionPath(String fileName) {
        Path path = partitionDirectory.resolve(fileName).normalize();
        if (!partitionDirectory.equals(path.getParent())) {
            throw new IllegalArgumentException("invalid partition file name: " + fileName);
        }
        return path;
    }

    private static long elapsedMillis(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }

    private static void logFailure(
            String operation, String tableName, long started, RuntimeException error) {
        LOGGER.debug("table={} operation={} outcome=ERROR error={} durationMs={}",
                clean(tableName), operation, clean(error.getMessage()), elapsedMillis(started));
    }

    private static String clean(Object value) {
        return String.valueOf(value).replace(',', ';').replace('\n', ' ').replace('\r', ' ');
    }

    private static void initializeLoggingContext() {
        if (MDC.get("sessionId") == null) {
            MDC.put("sessionId", UUID.randomUUID().toString());
        }
        if (MDC.get("statementNumber") == null) {
            MDC.put("statementNumber", "0");
        }
    }
}
