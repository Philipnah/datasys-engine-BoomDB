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
                try {
                    binder.bind(statement);
                    switch (statement) {
                        case CreateTableStatement create ->
                            storage.createTable(create.tableName(), create.columns());
                        case CopyStatement copy ->
                            storage.copyFile(copy.tableName(), copy.csvFilePath());
                        case SelectStatement select -> drain(planner.plan(select), result);
                    }
                } catch (RuntimeException error) {
                    if (statement instanceof SelectStatement select) {
                        // SQL SELECT bypasses StorageEngine.select, so this boundary owns its log.
                        LOGGER.error("table={} operation=SELECT outcome=ERROR error={}",
                                clean(select.tableName()), clean(error.getMessage()));
                    }
                    throw error;
                }
            }
            return List.copyOf(result);
        } finally {
            MDC.put("statementNumber", "1");
        }
    }

    private static String clean(Object value) {
        return String.valueOf(value).replace(',', ';').replace('\n', ' ').replace('\r', ' ');
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
