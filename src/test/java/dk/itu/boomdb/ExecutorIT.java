package dk.itu.boomdb;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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

    @ParameterizedTest
    @ValueSource(doubles = {1.0e20, 1.0e-4, -0.0})
    void executesPrintedDoublePredicatesAfterRoundTrip(double constant, @TempDir Path directory)
            throws Exception {
        Path csv = directory.resolve("prices.csv");
        Files.writeString(csv, "0.0\n" + constant + "\n");
        // Separate partitions exercise pruning, including -0.0 versus +0.0.
        StorageEngine storage = new StorageEngine(directory.resolve("database"), 1);
        storage.createTable("prices", List.of(new ColumnSpec("price", ColumnType.DOUBLE)));
        storage.copyFile("prices", csv.toString());
        SelectStatement statement = new SelectStatement("prices", Optional.of(
                new Predicate("price", Comparison.EQUALS, constant)));

        List<Object[]> rows = new Executor(storage).execute(new SqlPrinter().print(statement));

        assertEquals(1, rows.size());
        assertArrayEquals(new Object[] {constant}, rows.getFirst());
        assertEquals(new ScanStats(2, 1, 1), storage.lastScanStats());

        List<Object[]> apiRows = storage.select("prices", "price", Comparison.EQUALS, constant);
        assertEquals(1, apiRows.size());
        assertArrayEquals(new Object[] {constant}, apiRows.getFirst());
        assertEquals(new ScanStats(2, 1, 1), storage.lastScanStats());
    }

    @Test
    void bothSelectEntryPointsKeepContextualTypeErrors(@TempDir Path directory) {
        StorageEngine storage = new StorageEngine(directory);
        storage.createTable("prices", List.of(new ColumnSpec("price", ColumnType.DOUBLE)));

        IllegalArgumentException apiError = assertThrows(IllegalArgumentException.class,
                () -> storage.select("prices", "price", Comparison.EQUALS, 1L));
        IllegalArgumentException sqlError = assertThrows(IllegalArgumentException.class,
                () -> new Executor(storage).execute("SELECT * FROM prices WHERE price = 1;"));

        assertEquals("SELECT on \"prices\": column \"price\" is DOUBLE but constant is Long",
                apiError.getMessage());
        assertEquals(apiError.getMessage(), sqlError.getMessage());
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
        assertEquals("1", MDC.get("statementNumber"));
    }

    @Test
    void parseFailureRunsNoStatementsAndKeepsStatementNumberOne(@TempDir Path directory) {
        StorageEngine storage = new StorageEngine(directory);
        String malformed = """
                CREATE TABLE before_error (city STRING);
                SELECT * trips;
                """;

        assertThrows(SqlParseException.class,
                () -> new Executor(storage).execute(malformed));

        assertThrows(IllegalArgumentException.class, () -> storage.schema("before_error"));
        assertEquals("1", MDC.get("statementNumber"));
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
                && field(line, 2).equals("1")));
        assertTrue(lines.stream().anyMatch(line -> line.contains("table=" + tableName + " columns=")
                && field(line, 2).equals("1")));
        assertTrue(lines.stream().anyMatch(line -> line.contains("table=" + tableName + " file=")
                && field(line, 2).equals("2")));
        assertTrue(lines.stream().anyMatch(line -> field(line, 5).equals("Planner")
                && line.contains("table=" + tableName)
                && field(line, 2).equals("3")));
        assertTrue(lines.stream().anyMatch(line -> field(line, 5).equals("FilterOperator")
                && field(line, 2).equals("3")));
        assertEquals("1", MDC.get("statementNumber"));
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
