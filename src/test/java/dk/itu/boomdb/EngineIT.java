package dk.itu.boomdb;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EngineIT {
    @Test
    void noArgumentsPrintTeamAndUsageWithoutCreatingStorage(@TempDir Path directory) {
        Path database = directory.resolve("database");

        RunResult result = run(new String[0], database);

        assertEquals(0, result.status());
        assertEquals("""
                Team BoomDB
                Usage: boomdb '<SQL statement>' | boomdb -f <script.sql>
                """, result.stdout());
        assertEquals("", result.stderr());
        assertFalse(Files.exists(database));
    }

    @Test
    void oneArgumentExecutesOneStatement(@TempDir Path directory) {
        Path database = directory.resolve("database");

        RunResult result = run(
                new String[] {"CREATE TABLE cities (city STRING);"}, database);

        assertEquals(0, result.status());
        assertEquals("", result.stdout());
        assertEquals("", result.stderr());
        assertEquals(List.of(new ColumnSpec("city", ColumnType.STRING)),
                new StorageEngine(database).schema("cities"));
    }

    @Test
    void scriptPrintsSelectRowsAsHeaderlessCsv(@TempDir Path directory) throws Exception {
        Path csv = directory.resolve("trips.csv");
        Files.copy(Path.of("src/test/resources/trips.csv"), csv);
        Path script = directory.resolve("query.sql");
        Files.writeString(script, """
                CREATE TABLE trips (city STRING, distance LONG, price DOUBLE);
                COPY trips FROM '%s';
                SELECT * FROM trips WHERE distance > 100;
                """.formatted(csv));

        RunResult result = run(
                new String[] {"-f", script.toString()}, directory.resolve("database"));

        assertEquals(0, result.status());
        assertEquals("""
                Aarhus,187,301.0
                Copenhagen,140,210.0
                Aalborg,210,340.5
                Esbjerg,299,450.25
                """, result.stdout());
        assertEquals("", result.stderr());
    }

    @Test
    void failingScriptWritesOnlyTheErrorToStderr(@TempDir Path directory) throws Exception {
        Path csv = directory.resolve("trips.csv");
        Files.copy(Path.of("src/test/resources/trips.csv"), csv);
        Path script = directory.resolve("failing.sql");
        Files.writeString(script, """
                CREATE TABLE trips (city STRING, distance LONG, price DOUBLE);
                COPY trips FROM '%s';
                SELECT * FROM trips;
                SELECT * FROM missing;
                """.formatted(csv));

        RunResult result = run(
                new String[] {"-f", script.toString()}, directory.resolve("database"));

        assertEquals(1, result.status());
        assertEquals("", result.stdout());
        assertEquals("unknown table: missing\n", result.stderr());
    }

    @Test
    void invalidArgumentsWriteUsageToStderrWithoutCreatingStorage(@TempDir Path directory) {
        Path database = directory.resolve("database");

        RunResult result = run(new String[] {"--bad", "value"}, database);

        assertEquals(1, result.status());
        assertEquals("", result.stdout());
        assertEquals("Usage: boomdb '<SQL statement>' | boomdb -f <script.sql>\n",
                result.stderr());
        assertFalse(Files.exists(database));
    }

    @Test
    void selectsTheThreeGoldenQueryResults(@TempDir Path directory) {
        // Keep the golden example independent of the demo's console output.
        StorageEngine storage = new StorageEngine(directory);
        storage.createTable("trips", List.of(
                new ColumnSpec("city", ColumnType.STRING),
                new ColumnSpec("distance", ColumnType.LONG),
                new ColumnSpec("price", ColumnType.DOUBLE)));
        storage.copyFile("trips", "src/test/resources/trips.csv");

        List<Object[]> longTrips = storage.select(
                "trips", "distance", Comparison.GREATER_THAN, 100L);
        assertEquals(4, longTrips.size());
        assertArrayEquals(new Object[] {"Aarhus", 187L, 301.0}, longTrips.get(0));
        assertArrayEquals(new Object[] {"Copenhagen", 140L, 210.0}, longTrips.get(1));
        assertArrayEquals(new Object[] {"Aalborg", 210L, 340.5}, longTrips.get(2));
        assertArrayEquals(new Object[] {"Esbjerg", 299L, 450.25}, longTrips.get(3));

        List<Object[]> copenhagenTrips = storage.select(
                "trips", "city", Comparison.EQUALS, "Copenhagen");
        assertEquals(3, copenhagenTrips.size());
        assertArrayEquals(new Object[] {"Copenhagen", 12L, 23.5}, copenhagenTrips.get(0));
        assertArrayEquals(new Object[] {"Copenhagen", 140L, 210.0}, copenhagenTrips.get(1));
        assertArrayEquals(new Object[] {"Copenhagen", 88L, 99.99}, copenhagenTrips.get(2));

        List<Object[]> cheapTrips = storage.select(
                "trips", "price", Comparison.LESS_THAN, 50.0);
        assertEquals(2, cheapTrips.size());
        assertArrayEquals(new Object[] {"Copenhagen", 12L, 23.5}, cheapTrips.get(0));
        assertArrayEquals(new Object[] {"Roskilde", 31L, 45.0}, cheapTrips.get(1));
    }

    private static RunResult run(String[] args, Path database) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int status = Engine.run(args, database,
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8));
        return new RunResult(status, normalize(stdout), normalize(stderr));
    }

    private static String normalize(ByteArrayOutputStream output) {
        return output.toString(StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    private record RunResult(int status, String stdout, String stderr) { }
}
