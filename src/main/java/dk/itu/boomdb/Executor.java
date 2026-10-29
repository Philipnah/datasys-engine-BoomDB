package dk.itu.boomdb;

import java.util.ArrayList;
import java.util.List;
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
                long started = System.nanoTime();
                try {
                    try (CatalogStore.Snapshot snapshot = statement instanceof CreateTableStatement
                            ? null : storage.snapshot(Binder.tableName(statement))) {
                        TableCatalog table = snapshot == null ? null : snapshot.table();
                        binder.bind(statement, table);
                        switch (statement) {
                            case CreateTableStatement create -> storage.create(create);
                            case CopyStatement copy -> storage.copy(table, copy.csvFilePath());
                            case DeleteStatement delete -> storage.delete(delete, table);
                            case SelectStatement select -> {
                                int before = result.size();
                                drain(planner.plan(select, table), result);
                                // close() has finished, including its diagnostic logging.
                                ScanStats stats = storage.lastScanStats();
                                LOGGER.debug("statement=SELECT table={} rowsOut={} partitionsRead={} partitionsPruned={} version={} durationMs={}",
                                        clean(table.tableName()), result.size() - before,
                                        stats.partitionsRead(), stats.partitionsPruned(), table.version(),
                                        (System.nanoTime() - started) / 1_000_000);
                            }
                        }
                    }
                } catch (RuntimeException error) {
                    // SQL SELECT bypasses StorageEngine.select, so this boundary owns its log.
                    // The same boundary owns SQL mutation failures; direct API calls log in storage.
                    String operation = switch (statement) {
                        case CreateTableStatement create -> "CREATE";
                        case CopyStatement copy -> "COPY";
                        case SelectStatement select -> "SELECT";
                        case DeleteStatement delete -> "DELETE";
                    };
                    LOGGER.error("table={} operation={} outcome=ERROR error={}",
                            clean(Binder.tableName(statement)), operation, clean(error.getMessage()));
                    throw error;
                }
            }
            return List.copyOf(result);
        } finally {
            MDC.put("statementNumber", "1");
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
