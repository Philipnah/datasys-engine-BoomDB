package dk.itu.boomdb;

import static dk.itu.boomdb.Exercise10TestData.golden;
import static dk.itu.boomdb.Exercise10TestData.sortedRows;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class Exercise10JoinIT {
    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4})
    void executorMatchesSortedDuckDbResultsForEveryTestbedQuery(int query,
            @TempDir Path directory) throws Exception {
        StorageEngine storage = new StorageEngine(directory.resolve("db"), 1);

        List<Object[]> rows = assertDoesNotThrow(() -> new Executor(storage).execute(
                Exercise10TestData.setupSql() + Exercise10TestData.QUERIES.get(query - 1)));

        assertEquals(golden(query), sortedRows(rows));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4})
    void packagedLauncherMatchesSortedDuckDbResultsThroughTheFileFlag(int query,
            @TempDir Path directory) throws Exception {
        Path script = directory.resolve("exercise10.sql");
        Files.writeString(script,
                Exercise10TestData.setupSql() + Exercise10TestData.QUERIES.get(query - 1));
        Path stdout = directory.resolve("stdout.csv");
        Path stderr = directory.resolve("stderr.log");
        // Use the project's real launcher and a fresh working directory, as LauncherIT does.
        Process process = new ProcessBuilder(Path.of("engine.sh").toAbsolutePath().toString(),
                "-f", script.toString()).directory(directory.toFile())
                .redirectOutput(stdout.toFile()).redirectError(stderr.toFile()).start();

        assertTrue(process.waitFor(30, TimeUnit.SECONDS), "engine did not finish within 30 seconds");
        String diagnostics = Files.readString(stderr);
        assertEquals(0, process.exitValue(), diagnostics);
        assertEquals(golden(query), Files.readAllLines(stdout).stream().sorted().toList(), diagnostics);
    }

    @Test
    void fourTableExecutionLogsEachIntermediateJoinCardinality(@TempDir Path directory)
            throws Exception {
        StorageEngine storage = Exercise10TestData.loaded(directory);
        Path log = Path.of("logs/engine.log");
        long before = Files.readAllLines(log).size();

        List<Object[]> rows = assertDoesNotThrow(
                () -> new Executor(storage).execute(Exercise10TestData.QUERIES.get(2)));

        assertEquals(golden(3), sortedRows(rows));
        List<String> summaries = Files.readAllLines(log).stream().skip(before)
                .filter(line -> line.contains(",HashJoinOperator,"))
                .filter(line -> line.contains("rowsBuild=") && line.contains("rowsProbe=")
                        && line.contains("rowsOut=")).toList();
        assertEquals(3, summaries.size());
        assertEquals(1, summaries.stream().filter(line -> hasJoinCounts(line, 4, 3, 4)).count());
        assertEquals(2, summaries.stream().filter(line -> hasJoinCounts(line, 4, 2, 4)).count());
    }

    @ParameterizedTest
    @CsvSource({"orders, 5", "customers, 6", "products, 6", "cities, 7"})
    void pinsEveryTablesSnapshotBeforePlanningStarts(String table, int rowsAfterCopy,
            @TempDir Path directory) throws Exception {
        StorageEngine storage = Exercise10TestData.loaded(directory);
        Path duplicate = directory.resolve("duplicate.csv");
        Files.writeString(duplicate,
                Files.readAllLines(Exercise10TestData.RESOURCES.resolve(table + ".csv")).getFirst() + "\n");
        AtomicBoolean copied = new AtomicBoolean();
        LoggerContext context = (LoggerContext) LogManager.getContext(false);
        var root = context.getConfiguration().getRootLogger();
        // A synchronous observer commits after statement-start pinning, before any scan opens.
        // No production hook or timing-dependent background thread is needed.
        var observer = new AbstractAppender("join-snapshot-test", null, null, false, Property.EMPTY_ARRAY) {
            @Override
            public void append(LogEvent event) {
                if (event.getLoggerName().equals(Planner.class.getName())
                        && copied.compareAndSet(false, true)) {
                    storage.copyFile(table, duplicate.toString());
                }
            }
        };
        observer.start();
        root.addAppender(observer, null, null);
        List<Object[]> rows;
        try {
            rows = assertDoesNotThrow(
                    () -> new Executor(storage).execute(Exercise10TestData.QUERIES.get(2)));
        } finally {
            root.removeAppender(observer.getName());
            observer.stop();
        }

        assertTrue(copied.get(), "the copy must overlap planning to exercise the pinned snapshots");
        assertEquals(2, storage.table(table).version());
        assertEquals(golden(3), sortedRows(rows));
        assertEquals(rowsAfterCopy,
                new Executor(storage).execute(Exercise10TestData.QUERIES.get(2)).size());
    }

    private static boolean hasJoinCounts(String line, int build, int probe, int out) {
        return line.matches(".*\\browsBuild=" + build + "\\b.*")
                && line.matches(".*\\browsProbe=" + probe + "\\b.*")
                && line.matches(".*\\browsOut=" + out + "\\b.*");
    }
}
