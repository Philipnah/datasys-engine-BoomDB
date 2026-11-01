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
    void rebasesTheDeleteWhenACopyCommitsFirst(@TempDir Path directory) throws IOException {
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
        executor.execute("DELETE FROM users WHERE name = 'Zoi';");
        assertEquals(3, storage.table("users").version()); // the table is the winner's plus the rebased DELETE
        executor.execute("DELETE FROM users WHERE name = 'Zoi';"); // the rerun is now a no-op on version 3
        assertEquals(3, storage.table("users").version());
        List<String> names = executor.execute("SELECT * FROM users;").stream()
                .map(row -> (String) row[0]).sorted().toList();
        assertEquals(List.of("Alice", "Bob", "Kasper"), names);
    }

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
