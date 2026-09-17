package dk.itu.boomdb;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.slf4j.MDC;

/** Executes SQL scripts statement by statement against one storage engine. */
public final class Executor {
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
        MDC.put("statementNumber", "0");
        try {
            List<Statement> statements = new SqlParser().parse(sqlText);
            Binder binder = new Binder(storage);
            Planner planner = new Planner(storage);
            List<Object[]> result = new ArrayList<>();
            int statementNumber = 0;

            for (Statement statement : statements) {
                MDC.put("statementNumber", String.valueOf(++statementNumber));
                binder.bind(statement);
                switch (statement) {
                    case CreateTableStatement create ->
                        storage.createTable(create.tableName(), create.columns());
                    case CopyStatement copy ->
                        storage.copyFile(copy.tableName(), copy.csvFilePath());
                    case SelectStatement select -> drain(planner.plan(select), result);
                }
            }
            return List.copyOf(result);
        } finally {
            MDC.put("statementNumber", "0");
        }
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
