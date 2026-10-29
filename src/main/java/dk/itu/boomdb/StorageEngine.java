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
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/** Stores typed tables in persistent PAX partitions and executes planned selections. */
public final class StorageEngine {
    private static final Logger LOGGER = LoggerFactory.getLogger(StorageEngine.class);
    private static final int DEFAULT_MAX_ROWS_PER_PARTITION = 10_000;

    private final CatalogStore catalogStore;
    private final Path partitionDirectory;
    private final int maxRowsPerPartition;
    private ScanStats lastScanStats = new ScanStats(0, 0, 0);
    // Test seam(шов) for interleaving a competing commit before a writer publishes.
    volatile Runnable beforeCommit = () -> {};

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
            create(new CreateTableStatement(tableName, columns));
        } catch (RuntimeException error) {
            logFailure("CREATE", tableName, started, error);
            throw error;
        }
    }

    void create(CreateTableStatement statement) {
        long started = System.nanoTime();
        if (catalogStore.load(statement.tableName()).isPresent()) {
            throw new IllegalArgumentException("table already exists: " + statement.tableName());
        }
        validateColumns(statement.columns());
        catalogStore.save(new TableCatalog(statement.tableName(), statement.columns(), 0, List.of()));
        LOGGER.debug("statement=CREATE table={} columns={} version=0 durationMs={}",
                clean(statement.tableName()), statement.columns().size(), elapsedMillis(started));
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

    TableCatalog table(String tableName) {
        return requireTable(tableName);
    }

    List<Object[]> readPartition(TableCatalog table, PartitionMetadata partition) {
        try {
            return StorageSupport.readPartition(
                    partitionPath(partition.fileName()), table.columns());
        } catch (IOException error) {
            throw new UncheckedIOException(
                    "cannot read partition " + partition.fileName(), error);
        }
    }

    /**
     * Appends a headerless CSV file as fresh immutable partitions and commits a new version.
     *
     * @param tableName destination table
     * @param csvFilePath path to the headerless ASCII CSV input
     * @throws IllegalArgumentException if the table or a CSV row is invalid
     * @throws ConflictException if another writer commits after this statement starts
     */
    public void copyFile(String tableName, String csvFilePath) {
        long started = System.nanoTime();
        try (CatalogStore.Snapshot snapshot = snapshot(tableName)) {
            copy(snapshot.table(), csvFilePath);
        } catch (RuntimeException error) {
            logFailure("COPY", tableName, started, error);
            throw error;
        }
    }

    void copy(TableCatalog table, String csvFilePath) {
        long started = System.nanoTime();
        Path csv = Path.of(csvFilePath);
        List<Object[]> rows = readCsv(csv, table.columns());
        List<PartitionMetadata> partitions = new ArrayList<>(table.partitions());
        int partitionId = nextPartitionId(table);
        try {
            // the start variable is always the first row of the partition
            for (int start = 0; start < rows.size(); start += maxRowsPerPartition) {
                // the end variable is always the last row of the partition
                int end = Math.min(start + maxRowsPerPartition, rows.size());
                List<Object[]> partitionRows = rows.subList(start, end);
                partitions.add(writePartition(table, partitionId++, partitionRows));
            }
            beforeCommit.run(); // Added a before commit hook for tests.
            long version = catalogStore.commit(table, partitions);
            LOGGER.debug("statement=COPY table={} file={} rows={} partitions={} version={} durationMs={}",
                    clean(table.tableName()), clean(csv.getFileName()), rows.size(),
                    partitions.size() - table.partitions().size(), version, elapsedMillis(started));
        } catch (IOException error) {
            throw new UncheckedIOException("cannot write partitions for " + csvFilePath, error);
        }
    }

    /**
     * Removes matching rows by publishing a new catalog with copy-on-write partitions.
     * A predicate that matches nothing leaves the table's version unchanged.
     *
     * @param tableName table to delete from
     * @param where optional typed predicate; empty removes all rows
     * @return number of rows deleted
     * @throws IllegalArgumentException if a referenced name or constant type is invalid
     * @throws ConflictException if another writer commits before this deletion is published
     */
    public long delete(String tableName, Optional<Predicate> where) {
        long started = System.nanoTime();
        try (CatalogStore.Snapshot snapshot = snapshot(tableName)) {
            DeleteStatement statement = new DeleteStatement(tableName, where);
            new Binder(this).bind(statement, snapshot.table());
            return delete(statement, snapshot.table());
        } catch (RuntimeException error) {
            logFailure("DELETE", tableName, started, error);
            throw error;
        }
    }

    long delete(DeleteStatement statement, TableCatalog table) {
        long started = System.nanoTime();
        Predicate predicate = statement.where().orElse(null);
        int columnIndex = predicate == null ? -1 : Planner.columnIndex(table.columns(), predicate.columnName());
        ColumnType type = predicate == null ? null : table.columns().get(columnIndex).type();
        List<PartitionMetadata> partitions = new ArrayList<>();
        int partitionId = nextPartitionId(table);
        int rewritten = 0;
        int carried = 0;
        long deleted = 0;
        try {
            for (PartitionMetadata partition : table.partitions()) {
                if (predicate != null) {
                    ColumnStatistics statistics = partition.statistics().get(columnIndex);
                    if (StorageSupport.shouldPrune(type, predicate.comparison(), predicate.constant(),
                            StorageSupport.decodeStatistic(type, statistics.min()),
                            StorageSupport.decodeStatistic(type, statistics.max()))) {
                        partitions.add(partition);
                        carried++;
                        continue;
                    }
                }
                List<Object[]> rows = readPartition(table, partition);
                List<Object[]> survivors = new ArrayList<>();
                for (Object[] row : rows) {
                    if (predicate != null && !StorageSupport.matches(type, predicate.comparison(),
                            row[columnIndex], predicate.constant())) {
                        survivors.add(row);
                    }
                }
                if (survivors.size() == rows.size()) {
                    // Min/max can admit a false positive; keep its original file and offsets.
                    partitions.add(partition);
                    carried++;
                    continue;
                }
                deleted += rows.size() - survivors.size();
                rewritten++;
                if (!survivors.isEmpty()) {
                    partitions.add(writePartition(table, partitionId++, survivors));
                }
            }
            long version = table.version();
            if (deleted > 0) {
                beforeCommit.run();
                version = catalogStore.commit(table, partitions);
            }
            LOGGER.debug("statement=DELETE table={} rowsDeleted={} partitionsRewritten={} partitionsCarried={} version={} durationMs={}",
                    clean(table.tableName()), deleted, rewritten, carried, version, elapsedMillis(started));
            return deleted;
        } catch (IOException error) {
            throw new UncheckedIOException("cannot rewrite partitions for " + table.tableName(), error);
        }
    }

    /** Writes a fresh partition and computes the same summaries for COPY and DELETE. */
    private PartitionMetadata writePartition(TableCatalog table, int partitionId,
            List<Object[]> partitionRows) throws IOException {
        String fileName = table.tableName() + "-" + UUID.randomUUID() + ".bin";
        // Compute statistics for each column in the partition
        List<ColumnStatistics> statistics = statisticsFor(
                table.tableName(), partitionId, table.columns(), partitionRows);
        // Write the partition to disk
        StorageSupport.writePartition(partitionPath(fileName), table.columns(), partitionRows);
        return new PartitionMetadata(partitionId, fileName, partitionRows.size(), statistics);
    }

    private static int nextPartitionId(TableCatalog table) {
        return Math.incrementExact(table.partitions().stream().mapToInt(PartitionMetadata::id).max().orElse(-1));
    }

    CatalogStore.Snapshot snapshot(String tableName) {
        return catalogStore.acquire(tableName);
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
        try (CatalogStore.Snapshot snapshot = snapshot(tableName)) {
            if (comparison == null) {
                throw new IllegalArgumentException("comparison is required");
            }

            SelectStatement statement = new SelectStatement(tableName, Optional.of(
                    new Predicate(columnName, comparison, constant)));
            // Binding validates names and constant types before planning.
            new Binder(this).bind(statement, snapshot.table());
            Operator operator = new Planner(this).plan(statement, snapshot.table());

            List<Object[]> result = new ArrayList<>();
            operator.open();
            try {
                for (Object[] row; (row = operator.next()) != null; ) {
                    result.add(row);
                }
            } finally {
                operator.close();
            }

            LOGGER.debug("statement=SELECT table={} column={} comparison={} const={} partitionsRead={} partitionsPruned={} rowsOut={} version={} durationMs={}",
                    clean(tableName), clean(columnName), comparison, clean(constant),
                    lastScanStats.partitionsRead(), lastScanStats.partitionsPruned(),
                    result.size(), snapshot.table().version(), elapsedMillis(started));
            return result;
        } catch (RuntimeException error) {
            logFailure("SELECT", tableName, started, error);
            throw error;
        }
    }

    /**
     * Returns planning statistics from the most recently completed select call.
     *
     * @return latest partition scan statistics
     */
    public ScanStats lastScanStats() {
        return lastScanStats;
    }

    void recordScanStats(ScanStats scanStats) {
        lastScanStats = Objects.requireNonNull(scanStats, "scanStats");
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
        // The enclosing operation owns failure logging, including SQL binding failures.
        return catalogStore.load(tableName)
                .orElseThrow(() -> new IllegalArgumentException("unknown table: " + tableName));
    }

    /**
     * Requires an exact predicate type and identifies its table and column on failure.
     *
     * @param operation statement being validated
     * @param tableName table being selected or deleted from
     * @param columnName predicate column
     * @param type column's declared type
     * @param constant predicate value to validate
     * @throws IllegalArgumentException if the constant is null or has an inexact type
     */
    static void requireConstantType(String operation,
            String tableName, String columnName, ColumnType type, Object constant) {
        Class<?> expected = switch (type) {
            case STRING -> String.class;
            case LONG -> Long.class;
            case DOUBLE -> Double.class;
        };
        if (constant == null || constant.getClass() != expected) {
            String actualType = constant == null ? "null" : constant.getClass().getSimpleName();
            throw new IllegalArgumentException(operation + " on \"" + tableName + "\": column \""
                    + columnName + "\" is " + type + " but constant is " + actualType);
        }
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
            LOGGER.debug("creating statistics for table={} partition={} column={} min={} max={}",
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
        LOGGER.error("table={} operation={} outcome=ERROR error={} durationMs={}",
                clean(tableName), operation, clean(error.getMessage()), elapsedMillis(started));
    }

    private static String clean(Object value) {
        // Keep direct API failure records ingestible under the same rules as SQL failures.
        return String.valueOf(value).replace(',', ';').replace('"', '\'')
                .replace('\n', ' ').replace('\r', ' ');
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
