package dk.itu.boomdb;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** Checks the experiment's configuration and timing through real SQL execution. */
class ExperimentIT {
    private static final String PROPERTY = "boomdb.maxRowsPerPartition";

    @Test
    void absentPropertyKeepsTenThousandRowDefault(@TempDir Path directory) throws Exception {
        checkPartitioning(directory, null, 10_001, 2);
    }

    @Test
    void customPropertyControlsPartitioning(@TempDir Path directory) throws Exception {
        checkPartitioning(directory, "2", 5, 3);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "nope", "0", "-1", "2147483648", "2.5"})
    void invalidPropertyFailsWithoutCreatingStorage(String value, @TempDir Path directory) {
        String previous = System.getProperty(PROPERTY);
        try {
            System.setProperty(PROPERTY, value);
            ByteArrayOutputStream errors = new ByteArrayOutputStream();
            assertEquals(1, Engine.run(new String[] {"CREATE TABLE t (id LONG);"},
                    directory.resolve("database"), new PrintStream(new ByteArrayOutputStream()),
                    new PrintStream(errors)));
            assertTrue(errors.toString().contains(PROPERTY));
            assertFalse(Files.exists(directory.resolve("database")));
        } finally {
            restoreProperty(previous);
        }
    }

    @Test
    void successfulSelectLogsFractionalDurationAfterCleanup(@TempDir Path directory) throws Exception {
        String table = uniqueTable();
        StorageEngine storage = copiedTable(directory, table);
        new Executor(storage).execute("SELECT * FROM " + table + " WHERE id = 2;");
        List<String> lines = Files.readAllLines(Path.of("logs/engine.log"));
        List<String> summaries = lines.stream()
                .filter(line -> line.contains("statement=SELECT table=" + table))
                .toList();
        assertEquals(1, summaries.size());
        String summary = summaries.getFirst();
        assertTrue(summary.contains(",DEBUG,Executor,"));
        assertTrue(summary.contains("rowsOut=1 partitionsTotal=2 partitionsRead=1 partitionsPruned=1"));
        assertTrue(summary.matches(".*durationMs=[0-9]+\\.[0-9]+.*"));
        int summaryIndex = lines.indexOf(summary);
        assertTrue(lines.get(summaryIndex - 1).contains("FilterOperator,rowsIn=2 rowsOut=1"));
        assertEquals(7, summary.split(",", -1).length);
    }

    @Test
    void bindingFailureLogsErrorWithoutSuccessfulSample(@TempDir Path directory) throws Exception {
        String table = uniqueTable();
        assertThrows(IllegalArgumentException.class, () -> new Executor(new StorageEngine(directory))
                .execute("SELECT * FROM " + table + " WHERE id = 2;"));
        assertFailedSelect(table);
    }

    @Test
    void scanFailureLogsErrorWithoutSuccessfulSample(@TempDir Path directory) throws Exception {
        String table = uniqueTable();
        StorageEngine storage = copiedTable(directory, table);
        // A missing file exercises failure after planning, while closing the filter.
        Files.delete(directory.resolve("data/" + table + "-0.bin"));
        assertThrows(RuntimeException.class, () -> new Executor(storage)
                .execute("SELECT * FROM " + table + " WHERE id = 2;"));
        assertFailedSelect(table);
    }

    @Test
    void launcherReturnsFailureAndClosesItsSession(@TempDir Path directory) throws Exception {
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/java").toString(),
                "-cp", System.getProperty("java.class.path"), "dk.itu.boomdb.Engine",
                "SELECT * FROM missing;")
                .directory(directory.toFile()).redirectOutput(directory.resolve("stdout").toFile())
                .redirectError(directory.resolve("stderr").toFile()).start();
        assertEquals(1, process.waitFor());
        assertTrue(Files.readString(directory.resolve("stderr")).contains("unknown table: missing"));
        assertTrue(Files.readString(directory.resolve("logs/engine.log")).contains("engine stopped"));
    }

    private static void assertFailedSelect(String table) throws Exception {
        List<String> records = Files.readAllLines(Path.of("logs/engine.log")).stream()
                .filter(line -> line.contains("statement=SELECT table=" + table)).toList();
        assertEquals(1, records.size());
        assertTrue(records.getFirst().contains(",ERROR,Executor,"));
        assertFalse(records.getFirst().contains("durationMs="));
    }

    private static StorageEngine copiedTable(Path directory, String table) throws Exception {
        Path csv = directory.resolve("input.csv");
        Files.writeString(csv, "1\n2\n3\n4\n");
        StorageEngine storage = new StorageEngine(directory, 2);
        storage.createTable(table, List.of(new ColumnSpec("id", ColumnType.LONG)));
        storage.copyFile(table, csv.toString());
        return storage;
    }

    private static void checkPartitioning(Path directory, String value, int rows, int partitions)
            throws Exception {
        String previous = System.getProperty(PROPERTY);
        try {
            if (value == null) System.clearProperty(PROPERTY);
            else System.setProperty(PROPERTY, value);
            Path csv = directory.resolve("input.csv");
            Files.writeString(csv, "1\n".repeat(rows));
            assertEquals(0, Engine.run(new String[] {"CREATE TABLE t (id LONG); COPY t FROM '"
                    + csv + "';"}, directory.resolve("database"),
                    new PrintStream(new ByteArrayOutputStream()),
                    new PrintStream(new ByteArrayOutputStream())));
            TableCatalog catalog = new CatalogStore(directory.resolve("database")).load("t").orElseThrow();
            assertEquals(partitions, catalog.partitions().size());
            assertEquals(rows, catalog.partitions().stream().mapToInt(PartitionMetadata::rowCount).sum());
        } finally {
            restoreProperty(previous);
        }
    }

    private static void restoreProperty(String previous) {
        if (previous == null) System.clearProperty(PROPERTY);
        else System.setProperty(PROPERTY, previous);
    }

    private static String uniqueTable() {
        return "timing_" + UUID.randomUUID().toString().replace("-", "");
    }
}
