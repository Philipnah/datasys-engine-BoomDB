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
                        binder.bind(copy);
                        storage.copyFile(copy.tableName(), copy.csvFilePath());
                    }
                    case SelectStatement select ->
                        executeSelect(select, binder, planner, result);
                    default -> throw new IllegalStateException("unsupported statement");
                }
            }
            return List.copyOf(result);
        } finally {
            MDC.put("statementNumber", "1");
        }
    }

    /** Times binding through operator cleanup, excluding the summary log itself. */
    private void executeSelect(SelectStatement select, Binder binder, Planner planner,
            List<Object[]> destination) {
        int before = destination.size();
        long started = System.nanoTime();
        try {
            binder.bind(select);
            drain(planner.plan(select), destination);
            // close() has finished, including its diagnostic logging.
            double durationMs = (System.nanoTime() - started) / 1_000_000.0;
            ScanStats stats = storage.lastScanStats();
            LOGGER.debug("statement=SELECT table={} rowsOut={} partitionsTotal={} partitionsRead={} partitionsPruned={} durationMs={}",
                    clean(select.tableName()), destination.size() - before,
                    stats.partitionsTotal(), stats.partitionsRead(), stats.partitionsPruned(),
                    String.format(Locale.ROOT, "%.6f", durationMs));
        } catch (RuntimeException error) {
            // Failed statements are diagnostics, never successful timing samples.
            LOGGER.error("statement=SELECT table={} outcome=ERROR error={}",
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
