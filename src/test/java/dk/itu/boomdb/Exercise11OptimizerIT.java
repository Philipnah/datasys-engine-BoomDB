package dk.itu.boomdb;

import static dk.itu.boomdb.Exercise10TestData.golden;
import static dk.itu.boomdb.Exercise10TestData.sortedRows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Exercise11OptimizerIT {
    @Test
    void bothModesMatchAllOracleQueriesAndRecordJoins(@TempDir Path directory) throws Exception {
        StorageEngine storage = Exercise10TestData.loaded(directory);
        for (int q = 0; q < 4; q++) {
            List<List<String>> answers = new ArrayList<>();
            for (JoinMode mode : JoinMode.values()) {
                try (Capture capture = new Capture(null)) {
                    List<Object[]> rows = new Executor(storage, mode).execute(Exercise10TestData.QUERIES.get(q));
                    answers.add(sortedRows(rows));
                    assertEquals(golden(q + 1), sortedRows(rows));
                    int tables = q == 0 ? 2 : q == 2 ? 4 : 3;
                    assertSummaries(capture.events, "1", tables - 1);
                }
            }
            assertEquals(answers.get(0), answers.get(1));
        }
    }

    @Test
    void reordersUnequalInputsAndKeepsSyntaxBuildSides(@TempDir Path directory) throws Exception {
        StorageEngine storage = Exercise10TestData.loaded(directory);
        for (int q : List.of(1, 2)) {
            String[] plans = new String[2];
            int i = 0;
            for (JoinMode mode : List.of(JoinMode.SYNTAX, JoinMode.OPTIMIZED)) {
                try (Capture capture = new Capture(null)) {
                    assertEquals(golden(q + 1), sortedRows(new Executor(storage, mode).execute(Exercise10TestData.QUERIES.get(q))));
                    plans[i++] = capture.order();
                }
            }
            assertNotEquals(plans[0], plans[1]);
            assertTrue(plans[0].contains("joinOrder=orders;customers;products"));
            assertTrue(plans[1].contains(q == 1 ? "joinOrder=orders;products" : "joinOrder=customers;cities"));
        }
    }

    @Test
    void scriptAttributionEmptyResultsAndReversedFrom(@TempDir Path directory) throws Exception {
        StorageEngine storage = Exercise10TestData.loaded(directory);
        String reversed = "SELECT * FROM products, orders, customers WHERE o_prodname = p_name AND o_custname = c_name;";
        String empty = Exercise10TestData.QUERIES.get(1).replace(";", " AND c_city = 'nowhere';");
        for (JoinMode mode : JoinMode.values()) {
            try (Capture capture = new Capture(null)) {
                assertTrue(new Executor(storage, mode).execute(empty + "SELECT * FROM orders;").size() > 0);
                assertSummaries(capture.events, "1", 2);
                assertSummaries(capture.events, "2", 0);
            }
            assertEquals(sortedRows(new Executor(storage, JoinMode.SYNTAX).execute(reversed)),
                    sortedRows(new Executor(storage, mode).execute(reversed)));
            assertTrue(new Executor(storage, mode).execute(empty).isEmpty());
        }
    }

    @Test
    void tiedLogicalOrderStillChangesPhysicalFingerprint(@TempDir Path directory) throws Exception {
        StorageEngine storage = new StorageEngine(directory.resolve("db"));
        new Executor(storage).execute(Exercise10TestData.setupSql());
        List<String> fingerprints = new ArrayList<>();
        for (JoinMode mode : List.of(JoinMode.SYNTAX, JoinMode.OPTIMIZED)) {
            try (Capture capture = new Capture(null)) {
                assertEquals(golden(2), sortedRows(new Executor(storage, mode).execute(Exercise10TestData.QUERIES.get(1))));
                assertTrue(capture.order().contains("joinOrder=orders;customers;products"));
                fingerprints.add(capture.summaries().stream().map(m -> m.replaceAll(" estRows=.*", "")).sorted().toList().toString());
            }
        }
        assertNotEquals(fingerprints.get(0), fingerprints.get(1));
    }

    @Test
    void duplicateRowsAndTwoSelectStatementsKeepMultiplicityAndContext(@TempDir Path directory) throws Exception {
        StorageEngine storage = Exercise10TestData.loaded(directory);
        Path duplicate = directory.resolve("duplicate.csv");
        Files.writeString(duplicate, Files.readAllLines(Exercise10TestData.RESOURCES.resolve("orders.csv")).getFirst() + "\n");
        storage.copyFile("orders", duplicate.toString());
        String first = Exercise10TestData.QUERIES.get(1);
        String second = Exercise10TestData.QUERIES.get(2);
        List<String> expected = null;
        for (JoinMode mode : JoinMode.values()) {
            try (Capture capture = new Capture(null)) {
                List<String> rows = sortedRows(new Executor(storage, mode).execute(first + second));
                assertEquals(10, rows.size());
                assertTrue(rows.stream().distinct().count() < rows.size());
                if (expected == null) expected = rows; else assertEquals(expected, rows);
                assertSummaries(capture.events, "1", 2);
                assertSummaries(capture.events, "2", 3);
            }
        }
    }

    @Test
    void planningAndRowsUseSamePinnedSnapshotsDuringCopy(@TempDir Path directory) throws Exception {
        StorageEngine storage = Exercise10TestData.loaded(directory);
        String query = Exercise10TestData.QUERIES.get(2);
        String baselineOrder;
        List<String> baselineSummaries;
        try (Capture capture = new Capture(null)) {
            assertEquals(golden(3), sortedRows(new Executor(storage).execute(query)));
            baselineOrder = capture.order();
            baselineSummaries = capture.summaries();
            assertEquals(List.of(6.0, 24.0, 48.0), capture.estimates());
        }
        Path duplicate = directory.resolve("duplicate.csv");
        Files.writeString(duplicate, Files.readAllLines(Exercise10TestData.RESOURCES.resolve("orders.csv")).getFirst() + "\n");
        AtomicBoolean copied = new AtomicBoolean();
        try (Capture capture = new Capture(() -> {
            if (copied.compareAndSet(false, true)) storage.copyFile("orders", duplicate.toString());
        })) {
            assertEquals(golden(3), sortedRows(new Executor(storage).execute(query)));
            assertEquals(baselineOrder, capture.order());
            assertEquals(baselineSummaries, capture.summaries());
            assertEquals(List.of(6.0, 24.0, 48.0), capture.estimates());
        }
        assertTrue(copied.get());
        try (Capture capture = new Capture(null)) {
            assertEquals(5, new Executor(storage).execute(query).size());
            assertNotEquals(baselineSummaries, capture.summaries());
            assertEquals(List.of(6.0, 30.0, 60.0), capture.estimates());
        }
    }

    @Test
    void packagedCliSupportsModesAndBothCommandForms(@TempDir Path directory) throws Exception {
        Path script = directory.resolve("setup.sql");
        Files.writeString(script, Exercise10TestData.setupSql());
        runCli(directory, "-j", "syntax", "-f", script.toString());
        String query = Exercise10TestData.QUERIES.get(1);
        Files.writeString(script, query);
        String output = runCli(directory, "-f", script.toString(), "-j", "syntax");
        assertEquals(golden(2), output.lines().sorted().toList());
        output = runCli(directory, "-c", query, "-j", "syntax");
        assertEquals(golden(2), output.lines().sorted().toList());
        String log = Files.readString(directory.resolve("logs/engine.log"));
        assertTrue(log.contains("joinOrder=orders;customers;products mode=syntax"));
        assertTrue(log.contains("buildTables=orders probeTables=customers"));
        assertTrue(log.contains("buildTables=orders;customers probeTables=products"));
        output = runCli(directory, "-j", "optimized", "-c", query);
        assertEquals(golden(2), output.lines().sorted().toList());
        String logAfter = Files.readString(directory.resolve("logs/engine.log"));
        assertTrue(logAfter.contains("mode=optimized"));
        long optimizedBefore = logAfter.lines().filter(line -> line.contains("mode=optimized")).count();
        assertEquals(golden(2), runCli(directory, "-c", query).lines().sorted().toList());
        assertEquals(optimizedBefore + 1, Files.readString(directory.resolve("logs/engine.log")).lines()
                .filter(line -> line.contains("mode=optimized")).count());
        for (int queryIndex : List.of(1, 2)) {
            Files.writeString(script, Exercise10TestData.QUERIES.get(queryIndex));
            for (String mode : List.of("syntax", "optimized")) {
                assertEquals(golden(queryIndex + 1), runCli(directory,
                        "-j", mode, "-f", script.toString()).lines().sorted().toList());
            }
        }
        List<String> candidates = logAfter.lines().filter(line -> line.contains("event=JOIN_CANDIDATE")).toList();
        assertFalse(candidates.isEmpty());
        assertTrue(candidates.stream().allMatch(line -> line.split(",", -1).length == 7));
    }

    private static String runCli(Path directory, String... args) throws Exception {
        Path out = directory.resolve("stdout"), err = directory.resolve("stderr");
        Process process = TestLauncher.command(args).directory(directory.toFile())
                .redirectOutput(out.toFile()).redirectError(err.toFile()).start();
        assertTrue(process.waitFor(30, TimeUnit.SECONDS));
        assertEquals(0, process.exitValue(), Files.readString(err));
        return Files.readString(out).replace("\r\n", "\n");
    }

    private static void assertSummaries(List<LogEvent> events, String statement, int count) {
        var joins = events.stream().filter(e -> e.getLoggerName().equals(HashJoinOperator.class.getName()))
                .filter(e -> statement.equals(e.getContextData().getValue("statementNumber"))).toList();
        assertEquals(count, joins.size());
        var numbers = new java.util.HashSet<String>();
        for (LogEvent event : joins) {
            String message = event.getMessage().getFormattedMessage();
            var matcher = Pattern.compile("join=(\\d+).*estRows=([^ ]+).*rowsOut=(\\d+)").matcher(message);
            assertTrue(matcher.find(), message);
            assertTrue(message.contains("completed=true"), message);
            assertTrue(Double.isFinite(Double.parseDouble(matcher.group(2))));
            assertTrue(numbers.add(matcher.group(1)));
            assertNotNull(event.getContextData().getValue("sessionId"));
        }
    }

    private static final class Capture implements AutoCloseable {
        final List<LogEvent> events = new ArrayList<>();
        final org.apache.logging.log4j.core.config.LoggerConfig root;
        final AbstractAppender appender;
        Capture(Runnable onPlanner) {
            root = ((LoggerContext) LogManager.getContext(false)).getConfiguration().getRootLogger();
            appender = new AbstractAppender("optimizer-capture", null, null, false, Property.EMPTY_ARRAY) {
                @Override public void append(LogEvent event) {
                    events.add(event.toImmutable());
                    if (onPlanner != null && event.getLoggerName().equals(Planner.class.getName())) onPlanner.run();
                }
            };
            appender.start();
            root.addAppender(appender, null, null);
        }
        String order() {
            return events.stream().filter(e -> e.getLoggerName().equals(Planner.class.getName()))
                    .map(e -> e.getMessage().getFormattedMessage()).filter(m -> m.contains("joinOrder="))
                    .findFirst().orElseThrow();
        }
        List<String> summaries() {
            return events.stream().filter(e -> e.getLoggerName().equals(HashJoinOperator.class.getName()))
                    .map(e -> e.getMessage().getFormattedMessage()).sorted().toList();
        }
        List<Double> estimates() {
            return summaries().stream().map(m -> {
                var matcher = Pattern.compile("estRows=([^ ]+)").matcher(m);
                assertTrue(matcher.find());
                return Double.parseDouble(matcher.group(1));
            }).toList();
        }
        @Override public void close() { root.removeAppender(appender.getName()); appender.stop(); }
    }
}
