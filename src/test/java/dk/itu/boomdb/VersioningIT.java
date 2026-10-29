package dk.itu.boomdb;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class VersioningIT {
    @Test
    void abortsTheDeleteWhenACopyCommitsFirst(@TempDir Path directory) throws IOException {
        Path usersCsv = directory.resolve("users.csv");
        Path kasperCsv = directory.resolve("kasper.csv");
        Files.writeString(usersCsv, "Zoi\nAlice\nBob\n");
        Files.writeString(kasperCsv, "Kasper\n");
        StorageEngine storage = new StorageEngine(directory.resolve("db"), 3);
        Executor executor = new Executor(storage);
        executor.execute("CREATE TABLE users (name STRING);");
        executor.execute("COPY users FROM '" + usersCsv + "';");
        assertEquals(1, storage.table("users").version());

        // Pause DELETE after its rewrite so COPY publishes from the same base version first.
        storage.beforeCommit = () -> {
            storage.beforeCommit = () -> { }; // the COPY must not run this hook again
            executor.execute("COPY users FROM '" + kasperCsv + "';"); // the winner commits version 2
        };
        assertThrows(ConflictException.class,
                () -> executor.execute("DELETE FROM users WHERE name = 'Zoi';"));
        assertEquals(2, storage.table("users").version()); // the table is the winner's
        executor.execute("DELETE FROM users WHERE name = 'Zoi';"); // the rerun succeeds on version 2
        assertEquals(3, storage.table("users").version());
    }
//
//    @ParameterizedTest
//    @CsvSource({"COPY,COPY", "COPY,DELETE", "DELETE,COPY", "DELETE,DELETE"})
//    void staleDmlPreservesWinnerAndCanBeRetried(String loser, String winner,
//            @TempDir Path directory) throws IOException {
//        Path initial = directory.resolve("initial.csv");
//        Path winning = directory.resolve("winning.csv");
//        Path losing = directory.resolve("losing.csv");
//        Files.writeString(initial, "1\n2\n3\n");
//        Files.writeString(winning, "4\n");
//        Files.writeString(losing, "5\n");
//        Path db = directory.resolve("db");
//        StorageEngine storage = new StorageEngine(db, 3);
//        storage.createTable("t", List.of(new ColumnSpec("id", ColumnType.LONG)));
//        storage.copyFile("t", initial.toString());
//        Executor sql = new Executor(storage);
//        String winningSql = winner.equals("COPY")
//                ? "COPY t FROM '" + winning + "';" : "DELETE FROM t WHERE id = 1;";
//        String losingSql = loser.equals("COPY")
//                ? "COPY t FROM '" + losing + "';" : "DELETE FROM t WHERE id = 2;";
//        storage.beforeCommit = () -> {
//            storage.beforeCommit = () -> { };
//            sql.execute(winningSql);
//        };
//        long logStart = Files.readAllLines(Path.of("logs/engine.log")).size();
//
//        ConflictException error = assertThrows(ConflictException.class,
//                () -> sql.execute(losingSql));
//
//        assertEquals("conflict table=t base=1 current=2", error.getMessage());
//        List<String> errors = Files.readAllLines(Path.of("logs/engine.log")).stream()
//                .skip(logStart).filter(line -> line.contains(",ERROR,")).toList();
//        assertEquals(1, errors.size());
//        assertTrue(errors.getFirst().contains(error.getMessage()));
//        Long[] winnerRows = winner.equals("COPY") ? new Long[] {1L, 2L, 3L, 4L}
//                : new Long[] {2L, 3L};
//        assertVersionAndRows(storage, 2, winnerRows);
//        assertVersionAndRows(new StorageEngine(db), 2, winnerRows);
//
//        sql.execute(losingSql);
//
//        Long[] retryRows = loser.equals("COPY")
//                ? (winner.equals("COPY") ? new Long[] {1L, 2L, 3L, 4L, 5L} : new Long[] {2L, 3L, 5L})
//                : (winner.equals("COPY") ? new Long[] {1L, 3L, 4L} : new Long[] {3L});
//        assertVersionAndRows(storage, 3, retryRows);
//        assertVersionAndRows(new StorageEngine(db), 3, retryRows);
//    }
//
//    @Test
//    void simultaneousCopiesFromOneBaseHaveExactlyOneWinner(@TempDir Path directory)
//            throws Exception {
//        Path csv = directory.resolve("rows.csv");
//        Files.writeString(csv, "1\n");
//        StorageEngine storage = new StorageEngine(directory.resolve("db"), 3);
//        storage.createTable("t", List.of(new ColumnSpec("id", ColumnType.LONG)));
//        storage.copyFile("t", csv.toString());
//        CyclicBarrier prepared = new CyclicBarrier(2);
//        // Both writers must prepare from version 1 before either attempts publication.
//        storage.beforeCommit = () -> {
//            try {
//                prepared.await(5, TimeUnit.SECONDS);
//            } catch (Exception error) {
//                throw new RuntimeException(error);
//            }
//        };
//        try (var workers = Executors.newFixedThreadPool(2)) {
//            var operation = (java.util.concurrent.Callable<Boolean>) () -> {
//                try {
//                    storage.copyFile("t", csv.toString());
//                    return true;
//                } catch (ConflictException error) {
//                    assertEquals("conflict table=t base=1 current=2", error.getMessage());
//                    return false;
//                }
//            };
//            var first = workers.submit(operation);
//            var second = workers.submit(operation);
//            assertTrue(first.get(10, TimeUnit.SECONDS) ^ second.get(10, TimeUnit.SECONDS));
//        }
//        assertVersionAndRows(storage, 2, 1L, 1L);
//        assertVersionAndRows(new StorageEngine(directory.resolve("db")), 2, 1L, 1L);
//    }

    @Test
    void createCopyCopyAndDeletePublishVersionsZeroThroughThree(@TempDir Path directory)
            throws Exception {
        StorageEngine storage = new StorageEngine(directory.resolve("db"), 2);
        Executor executor = new Executor(storage);
        Path csv = directory.resolve("rows.csv");
        Files.writeString(csv, "1\n2\n3\n");
        executor.execute("CREATE TABLE t (id LONG);");
        assertVersionAndRows(storage, 0);
        executor.execute("COPY t FROM '" + csv + "';");
        assertVersionAndRows(storage, 1, 1L, 2L, 3L);
        executor.execute("COPY t FROM '" + csv + "';");
        assertVersionAndRows(storage, 2, 1L, 2L, 3L, 1L, 2L, 3L);
        executor.execute("DELETE FROM t WHERE id = 2;");
        assertVersionAndRows(storage, 3, 1L, 3L, 1L, 3L);
        assertVersionAndRows(new StorageEngine(directory.resolve("db")), 3, 1L, 3L, 1L, 3L);
    }

    @Test
    void emptyCopyCommitsButEmptyDeleteDoesNot(@TempDir Path directory) throws Exception {
        StorageEngine storage = new StorageEngine(directory.resolve("db"));
        Path csv = directory.resolve("empty.csv");
        Files.writeString(csv, "");
        storage.createTable("t", List.of(new ColumnSpec("id", ColumnType.LONG)));
        storage.copyFile("t", csv.toString());
        assertVersionAndRows(storage, 1);
        new Executor(storage).execute("DELETE FROM t;");
        assertVersionAndRows(storage, 1);
    }

    @Test
    void plannedSnapshotReadsOldRowsAfterNewCopiesAndRetention(@TempDir Path directory)
            throws Exception {
        Path csv = directory.resolve("rows.csv");
        Files.writeString(csv, "1\n2\n");
        StorageEngine storage = new StorageEngine(directory.resolve("db"), 2);
        storage.createTable("t", List.of(new ColumnSpec("id", ColumnType.LONG)));
        storage.copyFile("t", csv.toString());
        SelectStatement select = new SelectStatement("t", Optional.empty());
        try (CatalogStore.Snapshot snapshot = storage.snapshot("t")) {
            new Binder(storage).bind(select, snapshot.table());
            Operator plan = new Planner(storage).plan(select, snapshot.table());
            for (int i = 0; i < 6; i++) {
                storage.copyFile("t", csv.toString());
            }
            assertTrue(Files.exists(directory.resolve("db/catalogs/versions/t-v1.json")));
            List<Object[]> rows = drain(plan);
            assertEquals(2, rows.size());
            assertArrayEquals(new Object[] {1L}, rows.getFirst());
            assertArrayEquals(new Object[] {2L}, rows.getLast());
        }
        assertEquals(7, storage.table("t").version());
        assertTrue(Files.notExists(directory.resolve("db/catalogs/versions/t-v1.json")));
    }

    @Test
    void failedCopyPublicationPreservesPreviousRowsAndPartitionBytes(@TempDir Path directory)
            throws Exception {
        Path csv = directory.resolve("rows.csv");
        Files.writeString(csv, "1\n2\n");
        Path db = directory.resolve("db");
        StorageEngine storage = new StorageEngine(db, 2);
        storage.createTable("t", List.of(new ColumnSpec("id", ColumnType.LONG)));
        storage.copyFile("t", csv.toString());
        Path original = db.resolve("data/" + storage.table("t").partitions().getFirst().fileName());
        byte[] bytes = Files.readAllBytes(original);
        Files.createDirectory(db.resolve("catalogs/versions/t-v2.json"));

        assertThrows(RuntimeException.class, () -> storage.copyFile("t", csv.toString()));
        assertArrayEquals(bytes, Files.readAllBytes(original));
        assertVersionAndRows(storage, 1, 1L, 2L);
        assertVersionAndRows(new StorageEngine(db), 1, 1L, 2L);
    }

    private static List<Object[]> drain(Operator operator) {
        List<Object[]> rows = new ArrayList<>();
        operator.open();
        try {
            for (Object[] row; (row = operator.next()) != null;) {
                rows.add(row);
            }
        } finally {
            operator.close();
        }
        return rows;
    }

    private static void assertVersionAndRows(StorageEngine storage, long version, Long... expected) {
        assertEquals(version, storage.table("t").version());
        List<Object[]> actual = new Executor(storage).execute("SELECT * FROM t;");
        assertEquals(expected.length, actual.size());
        for (int i = 0; i < expected.length; i++) {
            assertArrayEquals(new Object[] {expected[i]}, actual.get(i));
        }
    }
}
