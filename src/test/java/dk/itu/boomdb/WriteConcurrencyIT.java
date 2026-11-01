package dk.itu.boomdb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;

class WriteConcurrencyIT {
    private static final Path LOG_FILE = Path.of("logs/engine.log");

    @ParameterizedTest
    @CsvSource({"COPY,COPY,1 2 3 4 5", "COPY,DELETE,2 3 5", "DELETE,COPY,1 3 4"})
    void disjointWritesKeepBothEffectsWithoutRetry(String loser, String winner, String expected,
            @TempDir Path directory) throws Exception {
        StorageEngine storage = copied(directory, "1\n2\n3\n", 3);
        Path winning = directory.resolve("winner.csv");
        Path losing = directory.resolve("loser.csv");
        Files.writeString(winning, "4\n");
        Files.writeString(losing, "5\n");
        Executor sql = new Executor(storage);
        AtomicInteger attempts = new AtomicInteger();
        storage.beforeCommit = () -> {
            attempts.incrementAndGet();
            storage.beforeCommit = () -> {};
            if (winner.equals("COPY")) {
                storage.copyFile("t", winning.toString());
            } else {
                storage.delete("t", equalTo(1));
            }
        };
        int logStart = Files.readAllLines(LOG_FILE).size();

        sql.execute(loser.equals("COPY") ? "COPY t FROM '" + losing + "';"
                : "DELETE FROM t WHERE id = 2;");

        assertEquals(1, attempts.get());
        assertRows(storage, 3, expected);
        assertRows(new StorageEngine(directory.resolve("db")), 3, expected);
        assertFalse(logsSince(logStart).stream().anyMatch(line -> line.contains("event=WRITE_")));
    }

    @Test
    void simultaneousCopiesBothCommit(@TempDir Path directory) throws Exception {
        StorageEngine storage = copied(directory, "1\n", 1);
        CyclicBarrier prepared = new CyclicBarrier(2);
        storage.beforeCommit = () -> {
            try {
                prepared.await(5, TimeUnit.SECONDS);
            } catch (Exception error) {
                throw new RuntimeException(error);
            }
        };
        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(() -> storage.copyFile("t", directory.resolve("rows.csv").toString()));
            var second = workers.submit(() -> storage.copyFile("t", directory.resolve("rows.csv").toString()));
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        }
        assertRows(storage, 3, "1 1 1");
        assertRows(new StorageEngine(directory.resolve("db")), 3, "1 1 1");
    }

    @Test
    void deletesOfDifferentPartitionsRebaseWithoutPreparingAgain(@TempDir Path directory) throws Exception {
        StorageEngine storage = copied(directory, "1\n2\n3\n4\n", 2);
        AtomicInteger attempts = new AtomicInteger();
        storage.beforeCommit = () -> {
            attempts.incrementAndGet();
            storage.beforeCommit = () -> {};
            storage.delete("t", equalTo(1));
        };

        assertEquals(1, storage.delete("t", equalTo(3)));

        assertEquals(1, attempts.get());
        assertRows(storage, 3, "2 4");
        try (var files = Files.list(directory.resolve("db/data"))) {
            assertEquals(4, files.count()); // Two originals and exactly one rewrite per DELETE.
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void overlappingDeleteRetriesFromWinnerAndLogsTheSameStatement(boolean sql,
            @TempDir Path directory) throws Exception {
        StorageEngine storage = copied(directory, "1\n2\n3\n", 3);
        String session = UUID.randomUUID().toString();
        MDC.put("sessionId", session);
        MDC.put("statementNumber", "7");
        storage.beforeCommit = () -> {
            storage.beforeCommit = () -> {};
            storage.delete("t", equalTo(1));
            assertRows(storage, 2, "2 3");
        };
        int logStart = Files.readAllLines(LOG_FILE).size();

        if (sql) {
            // The conflict belongs to statement 2, including after the internal retry.
            new Executor(storage).execute("SELECT * FROM t; DELETE FROM t WHERE id = 2;");
        } else {
            assertEquals(1, storage.delete("t", equalTo(2)));
        }

        List<String> logs = logsSince(logStart);
        List<String> conflicts = events(logs, "WRITE_CONFLICT");
        List<String> retries = events(logs, "WRITE_RETRY");
        assertEquals(1, conflicts.size());
        assertEquals(1, retries.size());
        assertTrue(conflicts.getFirst().contains("baseVersion=1 currentVersion=2"));
        assertTrue(retries.getFirst().contains("snapshotVersion=2 attempt=2"));
        for (String event : List.of(conflicts.getFirst(), retries.getFirst())) {
            assertEquals(7, event.split(",", -1).length);
            assertEquals(session, event.split(",", -1)[1]);
            assertEquals(sql ? "2" : "7", event.split(",", -1)[2]);
        }
        assertFalse(logs.stream().anyMatch(line -> line.contains(",ERROR,")));
        assertRows(storage, 3, "3");
        assertRows(new StorageEngine(directory.resolve("db")), 3, "3");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void retryCanBecomeANoOpAfterWinnerDeletesTheSameRow(boolean sql,
            @TempDir Path directory) throws Exception {
        StorageEngine storage = copied(directory, "1\n", 1);
        storage.beforeCommit = () -> {
            storage.beforeCommit = () -> {};
            storage.delete("t", Optional.empty());
        };

        if (sql) {
            new Executor(storage).execute("DELETE FROM t WHERE id = 1;");
        } else {
            assertEquals(0, storage.delete("t", equalTo(1)));
        }

        assertRows(storage, 2, "");
        assertRows(new StorageEngine(directory.resolve("db")), 2, "");
    }

    @Test
    void retryEvaluatesMatchingInsertsInItsNewSnapshot(@TempDir Path directory) throws Exception {
        StorageEngine storage = copied(directory, "1\n2\n3\n", 3);
        Path inserted = directory.resolve("inserted.csv");
        Files.writeString(inserted, "2\n");
        storage.beforeCommit = () -> {
            storage.beforeCommit = () -> {};
            storage.delete("t", equalTo(1));
            storage.copyFile("t", inserted.toString());
        };

        assertEquals(2, storage.delete("t", equalTo(2)));

        assertRows(storage, 4, "3");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void thirdRetryCanCommit(boolean sql, @TempDir Path directory) throws Exception {
        StorageEngine storage = copied(directory, "1\n2\n3\n4\n5\n", 5);
        AtomicInteger attempts = competingDeletes(storage, 3);
        int logStart = Files.readAllLines(LOG_FILE).size();

        if (sql) {
            new Executor(storage).execute("DELETE FROM t WHERE id = 5;");
        } else {
            assertEquals(1, storage.delete("t", equalTo(5)));
        }

        assertEquals(4, attempts.get());
        assertRows(storage, 5, "4");
        assertRows(new StorageEngine(directory.resolve("db")), 5, "4");
        assertEquals(3, events(logsSince(logStart), "WRITE_CONFLICT").size());
        assertEquals(3, events(logsSince(logStart), "WRITE_RETRY").size());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void fourthConflictStopsRetriesAndReleasesAllFailedSnapshots(boolean sql,
            @TempDir Path directory) throws Exception {
        StorageEngine storage = copied(directory, "1\n2\n3\n4\n5\n6\n", 6);
        AtomicInteger attempts = competingDeletes(storage, 4);
        int logStart = Files.readAllLines(LOG_FILE).size();

        ConflictException error = assertThrows(ConflictException.class, () -> {
            if (sql) {
                new Executor(storage).execute("DELETE FROM t WHERE id = 6;");
            } else {
                storage.delete("t", equalTo(6));
            }
        });

        assertEquals("conflict table=t base=4 current=5", error.getMessage());
        assertEquals(4, attempts.get());
        assertRows(storage, 5, "5 6");
        assertRows(new StorageEngine(directory.resolve("db")), 5, "5 6");
        List<String> logs = logsSince(logStart);
        assertEquals(4, events(logs, "WRITE_CONFLICT").size());
        assertEquals(3, events(logs, "WRITE_RETRY").size());
        List<String> errors = logs.stream().filter(line -> line.contains(",ERROR,")).toList();
        assertEquals(1, errors.size());
        assertEquals(sql ? "Executor" : "StorageEngine", errors.getFirst().split(",", -1)[5]);

        storage.beforeCommit = () -> {};
        for (int i = 0; i < 6; i++) {
            storage.copyFile("t", directory.resolve("rows.csv").toString());
        }
        for (int version = 1; version <= 4; version++) {
            assertFalse(Files.exists(directory.resolve("db/catalogs/versions/t-v" + version + ".json")));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void publicationFailureDoesNotRetry(boolean sql, @TempDir Path directory) throws Exception {
        StorageEngine storage = copied(directory, "1\n2\n", 2);
        AtomicInteger attempts = new AtomicInteger();
        storage.beforeCommit = attempts::incrementAndGet;
        Files.createDirectory(directory.resolve("db/catalogs/versions/t-v2.json"));
        int logStart = Files.readAllLines(LOG_FILE).size();

        assertThrows(IllegalArgumentException.class, () -> {
            if (sql) {
                new Executor(storage).execute("DELETE FROM t WHERE id = 1;");
            } else {
                storage.delete("t", equalTo(1));
            }
        });

        assertEquals(1, attempts.get());
        assertRows(storage, 1, "1 2");
        assertFalse(logsSince(logStart).stream().anyMatch(line -> line.contains("event=WRITE_")));
    }

    private static StorageEngine copied(Path directory, String contents, int partitionSize) throws IOException {
        Path csv = directory.resolve("rows.csv");
        Files.writeString(csv, contents);
        StorageEngine storage = new StorageEngine(directory.resolve("db"), partitionSize);
        storage.createTable("t", List.of(new ColumnSpec("id", ColumnType.LONG)));
        storage.copyFile("t", csv.toString());
        return storage;
    }

    private static Optional<Predicate> equalTo(long id) {
        return Optional.of(new Predicate("id", Comparison.EQUALS, id));
    }

    /** Each winner rewrites the same original as the parked attempt, without invoking itself. */
    private static AtomicInteger competingDeletes(StorageEngine storage, int winners) {
        AtomicInteger attempts = new AtomicInteger();
        storage.beforeCommit = new Runnable() {
            @Override
            public void run() {
                int attempt = attempts.incrementAndGet();
                if (attempt <= winners) {
                    storage.beforeCommit = () -> {};
                    try {
                        storage.delete("t", equalTo(attempt));
                    } finally {
                        storage.beforeCommit = this;
                    }
                }
            }
        };
        return attempts;
    }

    private static void assertRows(StorageEngine storage, long version, String expected) {
        assertEquals(version, storage.table("t").version());
        // Concurrent commits can change partition order; compare the actual row multiset.
        String actual = storage.select("t", "id", Comparison.GREATER_THAN, Long.MIN_VALUE).stream()
                .map(row -> (Long) row[0]).sorted().map(String::valueOf)
                .collect(java.util.stream.Collectors.joining(" "));
        assertEquals(expected, actual);
    }

    private static List<String> logsSince(int start) throws IOException {
        return Files.readAllLines(LOG_FILE).stream().skip(start).toList();
    }

    private static List<String> events(List<String> logs, String event) {
        return logs.stream().filter(line -> line.contains("event=" + event + " ")).toList();
    }
}
