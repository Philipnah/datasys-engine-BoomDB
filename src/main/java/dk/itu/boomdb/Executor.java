package dk.itu.boomdb;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/** Executes SQL scripts statement by statement against one storage engine. */
public final class Executor {
    private static final Logger LOGGER = LoggerFactory.getLogger(Executor.class);
    private final StorageEngine storage;

    /**
     * Creates an executor for one persistent database.
     *
     * @param storage database used for binding, planning, and execution
     * @throws NullPointerException if {@code storage} is null
     */
    public Executor(StorageEngine storage) {
        this.storage = Objects.requireNonNull(storage, "storage");
    }

    /**
     * Parses and executes a complete SQL script, stopping at the first error.
     * SELECT failures are logged once across binding, planning, and execution.
     *
     * @param sqlText semicolon-terminated SQL statements
     * @return immutable rows from all selects in statement order
     * @throws RuntimeException if parsing, binding, planning, or execution fails
     */
    public List<Object[]> execute(String sqlText) {
        MDC.put("statementNumber", "1");
        try {
            List<Statement> statements = new SqlParser().parse(sqlText);
            Binder binder = new Binder(storage);
            Planner planner = new Planner(storage);
            List<Object[]> result = new ArrayList<>();
            int statementNumber = 1;

            for (Statement statement : statements) {
                MDC.put("statementNumber", String.valueOf(statementNumber++));
                switch (statement) {
                    case CreateTableStatement create -> {
                        binder.bind(create);
                        storage.createTable(create.tableName(), create.columns());
                    }
                    case CopyStatement copy -> {
                        synchronized (storage) {
                            executeCopy(copy, binder);
                        }
                    }
                    case DeleteStatement delete -> {
                        synchronized (storage) {
                            executeDelete(delete, binder);
                        }
                    }
                    case SelectStatement select ->
                        executeSelect(select, binder, planner, result);
                }
            }
            return List.copyOf(result);
        } finally {
            MDC.put("statementNumber", "1");
        }
    }

    private void executeCopy(CopyStatement copy, Binder binder) {
        try (CatalogStore.Snapshot snapshot = storage.pin(copy.tableName())) {
            binder.bind(copy, snapshot.table());
            storage.copyFile(copy.tableName(), copy.csvFilePath(), snapshot.table());
        } catch (RuntimeException error) {
            logMutationFailure("COPY", copy.tableName(), error);
            throw error;
        }
    }

    private void executeDelete(DeleteStatement delete, Binder binder) {
        try (CatalogStore.Snapshot snapshot = storage.pin(delete.tableName())) {
            binder.bind(delete, snapshot.table());
            storage.delete(delete.tableName(), delete.where(), snapshot.table());
        } catch (RuntimeException error) {
            logMutationFailure("DELETE", delete.tableName(), error);
            throw error;
        }
    }

    private static void logMutationFailure(String operation, String table, RuntimeException error) {
        LOGGER.error("table={} operation={} outcome=ERROR error={}",
                clean(table), operation, clean(error.getMessage()));
    }

    /** Times binding through operator cleanup, excluding the summary log itself. */
    private void executeSelect(SelectStatement select, Binder binder, Planner planner,
            List<Object[]> destination) {
        int before = destination.size();
        long started = System.nanoTime();
        try (CatalogStore.Snapshot snapshot = storage.pin(select.tableName())) {
            // Binding and planning share the catalog acquired at statement start.
            binder.bind(select, snapshot.table());
            drain(planner.plan(select, snapshot.table()), destination);
            // close() has finished, including its diagnostic logging.
            double durationMs = (System.nanoTime() - started) / 1_000_000.0;
            ScanStats stats = storage.lastScanStats();
            LOGGER.debug("statement=SELECT table={} rowsOut={} partitionsTotal={} partitionsRead={} partitionsPruned={} version={} durationMs={}",
                    clean(select.tableName()), destination.size() - before,
                    stats.partitionsTotal(), stats.partitionsRead(), stats.partitionsPruned(), snapshot.table().version(),
                    String.format(Locale.ROOT, "%.6f", durationMs));
        } catch (RuntimeException error) {
            // SQL SELECT bypasses StorageEngine.select, so this boundary owns its log.
            // Failed statements are diagnostics, never successful timing samples.
            LOGGER.error("table={} operation=SELECT outcome=ERROR error={}",
                    clean(select.tableName()), clean(error.getMessage()));
            throw error;
        }
    }

    private static String clean(String text) {
        return String.valueOf(text).replace(',', ';').replace('"', '\'')
                .replace('\n', ' ').replace('\r', ' ');
    }

    private static void drain(Operator operator, List<Object[]> destination) {
        operator.open();
        try {
            for (Object[] row; (row = operator.next()) != null; ) {
                destination.add(row);
            }
        } finally {
            operator.close();
        }
    }
}
