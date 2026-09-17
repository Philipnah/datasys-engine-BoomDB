package dk.itu.boomdb;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.MDC;

class ExecutorIT {
    private static final Path LOG_FILE = Path.of("logs/engine.log");

    @Test
    void executesEveryStatementAndReturnsSelectRowsInScriptOrder(@TempDir Path directory)
            throws Exception {
        Path csv = writeCsv(directory, "trips.csv");
        StorageEngine storage = new StorageEngine(directory.resolve("database"), 2);
        String sql = script("trips", csv);

        List<Object[]> rows = new Executor(storage).execute(sql);

        assertEquals(3, rows.size());
        assertArrayEquals(new Object[] {"Aarhus", 187L, 301.0}, rows.get(0));
        assertArrayEquals(new Object[] {"Odense", 95L, 120.75}, rows.get(1));
        assertArrayEquals(new Object[] {"Aarhus", 187L, 301.0}, rows.get(2));
    }

    @Test
    void stopsAtFirstExecutionErrorAndRestoresStatementNumber(@TempDir Path directory) {
        StorageEngine storage = new StorageEngine(directory);
        String sql = """
                CREATE TABLE first (city STRING);
                COPY missing FROM 'missing.csv';
                CREATE TABLE never (city STRING);
                """;

        assertThrows(IllegalArgumentException.class,
                () -> new Executor(storage).execute(sql));

        assertEquals(List.of(new ColumnSpec("city", ColumnType.STRING)), storage.schema("first"));
        assertThrows(IllegalArgumentException.class, () -> storage.schema("never"));
        assertEquals("0", MDC.get("statementNumber"));
    }

    @Test
    void parseFailureRunsNoStatementsAndKeepsStatementNumberZero(@TempDir Path directory) {
        StorageEngine storage = new StorageEngine(directory);
        String malformed = """
                CREATE TABLE before_error (city STRING);
                SELECT * trips;
                """;

        assertThrows(SqlParseException.class,
                () -> new Executor(storage).execute(malformed));

        assertThrows(IllegalArgumentException.class, () -> storage.schema("before_error"));
        assertEquals("0", MDC.get("statementNumber"));
    }

    @Test
    void assignsOneBasedStatementNumbersAndResetsAfterTheScript(@TempDir Path directory)
            throws Exception {
        String tableName = "executor_" + UUID.randomUUID().toString().replace("-", "");
        Path csv = writeCsv(directory, tableName + ".csv");
        StorageEngine storage = new StorageEngine(directory.resolve("database"), 2);
        int linesBeforeExecution = Files.readAllLines(LOG_FILE).size();

        new Executor(storage).execute("""
                CREATE TABLE %s (city STRING, distance LONG, price DOUBLE);
                COPY %s FROM '%s';
                SELECT * FROM %s WHERE distance > 100;
                """.formatted(tableName, tableName, csv, tableName));

        List<String> lines = Files.readAllLines(LOG_FILE).stream()
                .skip(linesBeforeExecution)
                .toList();
        assertTrue(lines.stream().anyMatch(line -> field(line, 5).equals("SqlParser")
                && field(line, 2).equals("0")));
        assertTrue(lines.stream().anyMatch(line -> line.contains("table=" + tableName + " columns=")
                && field(line, 2).equals("1")));
        assertTrue(lines.stream().anyMatch(line -> line.contains("table=" + tableName + " file=")
                && field(line, 2).equals("2")));
        assertTrue(lines.stream().anyMatch(line -> field(line, 5).equals("Planner")
                && line.contains("table=" + tableName)
                && field(line, 2).equals("3")));
        assertTrue(lines.stream().anyMatch(line -> field(line, 5).equals("FilterOperator")
                && field(line, 2).equals("3")));
        assertEquals("0", MDC.get("statementNumber"));
    }

    private static String field(String line, int index) {
        return line.split(",", 7)[index];
    }

    private static Path writeCsv(Path directory, String fileName) throws Exception {
        Path csv = directory.resolve(fileName);
        Files.writeString(csv, "Odense,95,120.75\nAarhus,187,301.0\n");
        return csv;
    }

    private static String script(String tableName, Path csv) {
        return """
                CREATE TABLE %s (city STRING, distance LONG, price DOUBLE);
                COPY %s FROM '%s';
                SELECT * FROM %s WHERE distance > 100;
                SELECT * FROM %s;
                """.formatted(tableName, tableName, csv, tableName, tableName);
    }
}
