package dk.itu.boomdb;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.ArrayList;
import java.util.Optional;
import java.util.UUID;
import java.io.UncheckedIOException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TransactionIT {
    @Test
    void repeatedCopyAppendsRowsAndKeepsExistingPartitionBytes(@TempDir Path directory)
            throws Exception {
        StorageEngine storage = new StorageEngine(directory, 2);
        Path csv = directory.resolve("rows.csv");
        Files.writeString(csv, "1\n2\n3\n");
        storage.createTable("numbers", List.of(new ColumnSpec("n", ColumnType.LONG)));
        storage.copyFile("numbers", csv.toString());
        TableCatalog first = storage.table("numbers");
        Path original = directory.resolve("data").resolve(first.partitions().getFirst().fileName());
        byte[] originalBytes = Files.readAllBytes(original);

        storage.copyFile("numbers", csv.toString());

        assertEquals(List.of(1L, 2L, 3L, 1L, 2L, 3L),
                new Executor(storage).execute("SELECT * FROM numbers;").stream()
                        .map(row -> row[0]).toList());
        assertEquals(first.partitions(), storage.table("numbers").partitions().subList(0, 2));
        assertArrayEquals(originalBytes, Files.readAllBytes(original));
    }

    @Test
    void commitsVersionsZeroThroughThreeAndSurvivesRestart(@TempDir Path directory)
            throws Exception {
        StorageEngine storage = numbers(directory, "1\n2\n3\n4\n");
        Executor executor = new Executor(storage);
        assertEquals(0, storage.table("numbers").version());
        assertEquals(List.of(), values(executor));
        storage.copyFile("numbers", directory.resolve("rows.csv").toString());
        assertEquals(1, storage.table("numbers").version());
        assertEquals(List.of(1L, 2L, 3L, 4L), values(executor));
        Files.writeString(directory.resolve("rows.csv"), "5\n6\n7\n8\n");
        storage.copyFile("numbers", directory.resolve("rows.csv").toString());
        assertEquals(2, storage.table("numbers").version());
        assertEquals(List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L), values(executor));

        assertEquals(List.of(), executor.execute("DELETE FROM numbers WHERE n > 6;"));

        assertEquals(3, storage.table("numbers").version());
        assertEquals(List.of(1L, 2L, 3L, 4L, 5L, 6L), values(executor));
        StorageEngine restarted = new StorageEngine(directory, 2);
        assertEquals(3, restarted.table("numbers").version());
        assertEquals(values(executor), values(new Executor(restarted)));
    }

    @Test
    void selectiveDeleteCarriesUnchangedPartitionsAndNeverModifiesOriginals(@TempDir Path directory)
            throws Exception {
        StorageEngine storage = numbers(directory, "1\n2\n3\n4\n5\n6\n7\n8\n");
        storage.copyFile("numbers", directory.resolve("rows.csv").toString());
        TableCatalog before = storage.table("numbers");
        List<byte[]> originalBytes = new ArrayList<>();
        for (PartitionMetadata partition : before.partitions()) {
            originalBytes.add(Files.readAllBytes(directory.resolve("data").resolve(partition.fileName())));
        }

        new Executor(storage).execute("DELETE FROM numbers WHERE n = 3;");

        TableCatalog after = storage.table("numbers");
        assertEquals(2, after.version());
        assertEquals(before.partitions().get(0), after.partitions().get(0));
        assertEquals(before.partitions().get(2), after.partitions().get(2));
        assertEquals(before.partitions().get(3), after.partitions().get(3));
        assertFalse(before.partitions().get(1).fileName().equals(after.partitions().get(1).fileName()));
        assertEquals(1, after.partitions().get(1).rowCount());
        assertEquals(List.of(new ColumnStatistics("n", "4", "4")),
                after.partitions().get(1).statistics());
        assertEquals(List.of(1L, 2L, 4L, 5L, 6L, 7L, 8L), values(new Executor(storage)));
        for (int index = 0; index < before.partitions().size(); index++) {
            assertArrayEquals(originalBytes.get(index), Files.readAllBytes(directory.resolve("data")
                    .resolve(before.partitions().get(index).fileName())));
        }
        try (var files = Files.list(directory.resolve("data"))) {
            assertEquals(5, files.count(), "only the affected partition needs a new file");
        }
    }

    @Test
    void noMatchDeleteKeepsCatalogVersionAndCreatesNoFiles(@TempDir Path directory)
            throws Exception {
        StorageEngine storage = numbers(directory, "1\n3\n");
        storage.copyFile("numbers", directory.resolve("rows.csv").toString());
        TableCatalog before = storage.table("numbers");
        Executor executor = new Executor(storage);

        executor.execute("DELETE FROM numbers WHERE n = 2;");
        assertEquals(before, storage.table("numbers"), "candidate with no actual match is carried");
        executor.execute("DELETE FROM numbers WHERE n > 99;");
        assertEquals(before, storage.table("numbers"), "pruned partitions are carried");
        try (var files = Files.list(directory.resolve("data"))) {
            assertEquals(1, files.count());
        }
        try (var files = Files.list(directory.resolve("catalogs"))) {
            assertEquals(2, files.count());
        }
    }

    @Test
    void deleteAllPublishesEmptyTableWithoutEmptyPartitionFiles(@TempDir Path directory)
            throws Exception {
        StorageEngine storage = numbers(directory, "1\n2\n3\n");
        storage.copyFile("numbers", directory.resolve("rows.csv").toString());

        assertEquals(3, storage.delete("numbers", Optional.empty()));
        assertEquals(2, storage.table("numbers").version());
        assertEquals(List.of(), storage.table("numbers").partitions());
        assertEquals(List.of(), values(new Executor(storage)));
        assertEquals(0, storage.delete("numbers", Optional.empty()));
        assertEquals(2, storage.table("numbers").version());
        try (var files = Files.list(directory.resolve("data"))) {
            assertEquals(2, files.count());
        }
        // A later COPY must allocate fresh paths even though the current table is empty.
        storage.copyFile("numbers", directory.resolve("rows.csv").toString());
        assertEquals(3, storage.table("numbers").version());
        assertEquals(List.of(1L, 2L, 3L), values(new Executor(storage)));
    }

    @Test
    void failedRepeatedCopyLeavesCommittedVersionReadable(@TempDir Path directory)
            throws Exception {
        StorageEngine storage = numbers(directory, "1\n2\n");
        storage.copyFile("numbers", directory.resolve("rows.csv").toString());
        TableCatalog before = storage.table("numbers");
        Files.writeString(directory.resolve("rows.csv"), "3\ninvalid\n");

        assertThrows(IllegalArgumentException.class,
                () -> storage.copyFile("numbers", directory.resolve("rows.csv").toString()));

        assertEquals(before, storage.table("numbers"));
        assertEquals(List.of(1L, 2L), values(new Executor(new StorageEngine(directory))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"COPY", "DELETE"})
    void failedCatalogPublicationLeavesNewPartitionsInvisible(String operation,
            @TempDir Path directory) throws Exception {
        StorageEngine storage = numbers(directory, "1\n2\n3\n4\n");
        storage.copyFile("numbers", directory.resolve("rows.csv").toString());
        TableCatalog before = storage.table("numbers");
        // A final-name directory deterministically prevents publication after partition writes.
        Files.createDirectory(directory.resolve("catalogs/numbers-v2.json"));
        String sql = operation.equals("COPY")
                ? "COPY numbers FROM '" + directory.resolve("rows.csv") + "';"
                : "DELETE FROM numbers WHERE n = 1;";

        assertThrows(UncheckedIOException.class, () -> new Executor(storage).execute(sql));

        assertEquals(before, storage.table("numbers"));
        assertEquals(List.of(1L, 2L, 3L, 4L), values(new Executor(new StorageEngine(directory))));
        try (var files = Files.list(directory.resolve("data"))) {
            assertTrue(files.count() > before.partitions().size(), "unpublished files stay invisible");
        }
    }

    @Test
    void pinnedStatementSurvivesNewVersionsAndReleasesRetentionLease(@TempDir Path directory)
            throws Exception {
        StorageEngine storage = numbers(directory, "1\n2\n");
        storage.copyFile("numbers", directory.resolve("rows.csv").toString());
        SelectStatement statement = new SelectStatement("numbers", Optional.empty());
        try (CatalogStore.Snapshot snapshot = storage.pin("numbers")) {
            for (int version = 2; version <= 7; version++) {
                storage.copyFile("numbers", directory.resolve("rows.csv").toString());
            }
            new Binder(storage).bind(statement, snapshot.table());
            Operator operator = new Planner(storage).plan(statement, snapshot.table());
            List<Object> rows = new ArrayList<>();
            operator.open();
            try {
                for (Object[] row; (row = operator.next()) != null;) {
                    rows.add(row[0]);
                }
            } finally {
                operator.close();
            }
            assertEquals(List.of(1L, 2L), rows);
            assertEquals(1, snapshot.table().version());
            assertTrue(Files.exists(directory.resolve("catalogs/numbers-v1.json")));
        }
        assertFalse(Files.exists(directory.resolve("catalogs/numbers-v1.json")));
        assertEquals(7, new StorageEngine(directory).table("numbers").version());
    }

    @Test
    void everySuccessfulStatementLogsItsCommittedOrReadVersion(@TempDir Path directory)
            throws Exception {
        String table = "versions_" + UUID.randomUUID().toString().replace("-", "");
        Path csv = directory.resolve("rows.csv");
        Files.writeString(csv, "1\n2\n3\n");
        StorageEngine storage = new StorageEngine(directory, 2);
        new Executor(storage).execute("""
                CREATE TABLE %s (n LONG);
                SELECT * FROM %s;
                COPY %s FROM '%s';
                COPY %s FROM '%s';
                DELETE FROM %s WHERE n = 2;
                DELETE FROM %s WHERE n > 99;
                SELECT * FROM %s;
                """.formatted(table, table, table, csv, table, csv, table, table, table));
        List<String> summaries = Files.readAllLines(Path.of("logs/engine.log")).stream()
                .filter(line -> line.contains("statement=") && line.contains(" table=" + table + " "))
                .toList();
        assertEquals(7, summaries.size());
        List<Integer> versions = List.of(0, 0, 1, 2, 3, 3, 3);
        for (int index = 0; index < summaries.size(); index++) {
            assertTrue(summaries.get(index).contains(" version=" + versions.get(index) + " "));
        }
        assertTrue(summaries.get(4).contains(
                "rowsDeleted=2 partitionsRewritten=2 partitionsCarried=2"));
        assertTrue(summaries.get(5).contains(
                "rowsDeleted=0 partitionsRewritten=0 partitionsCarried=4"));
    }

    private static StorageEngine numbers(Path directory, String csv) throws Exception {
        StorageEngine storage = new StorageEngine(directory, 2);
        Files.writeString(directory.resolve("rows.csv"), csv);
        storage.createTable("numbers", List.of(new ColumnSpec("n", ColumnType.LONG)));
        return storage;
    }

    private static List<Object> values(Executor executor) {
        return executor.execute("SELECT * FROM numbers;").stream().map(row -> row[0]).toList();
    }
}
