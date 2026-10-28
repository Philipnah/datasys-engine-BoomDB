package dk.itu.boomdb;

import static java.nio.ByteOrder.LITTLE_ENDIAN;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class DeleteIT {
    @Test
    void selectiveDeleteRewritesOnlyMatchesAndPreservesOriginalFilesAndOffsets(@TempDir Path directory)
            throws Exception {
        StorageEngine storage = copied(directory, "1\n2\n3\n4\n5\n6\n7\n8\n");
        TableCatalog before = storage.table("t");
        List<byte[]> originals = before.partitions().stream().map(partition -> bytes(directory, partition)).toList();

        try (CatalogStore.Snapshot pinned = storage.snapshot("t")) {
            assertEquals(3, storage.delete("t", Optional.of(new Predicate("id", Comparison.GREATER_THAN, 5L))));
            TableCatalog after = storage.table("t");
            assertEquals(2, after.version());
            assertEquals(before.partitions().subList(0, 2), after.partitions().subList(0, 2));
            assertEquals(3, after.partitions().size());
            assertEquals(List.of(new ColumnStatistics("id", "5", "5")),
                    after.partitions().getLast().statistics());
            for (int i = 0; i < before.partitions().size(); i++) {
                byte[] actual = bytes(directory, before.partitions().get(i));
                assertArrayEquals(originals.get(i), actual);
                assertEquals(ByteBuffer.wrap(originals.get(i)).order(LITTLE_ENDIAN).getLong(16),
                        ByteBuffer.wrap(actual).order(LITTLE_ENDIAN).getLong(16));
            }
            ScanOperator oldScan = new ScanOperator(storage, pinned.table(), pinned.table().partitions());
            oldScan.open();
            int count = 0;
            try {
                while (oldScan.next() != null) {
                    count++;
                }
            } finally {
                oldScan.close();
            }
            assertEquals(8, count);
        }
        assertIds(storage, 1L, 2L, 3L, 4L, 5L);
        assertIds(new StorageEngine(directory.resolve("db")), 1L, 2L, 3L, 4L, 5L);
    }

    @Test
    void candidateWithoutMatchesKeepsItsIdentityAndDoesNotCommit(@TempDir Path directory)
            throws Exception {
        StorageEngine storage = copied(directory, "1\n3\n");
        TableCatalog before = storage.table("t");
        assertEquals(0, storage.delete("t", Optional.of(new Predicate("id", Comparison.EQUALS, 2L))));
        assertEquals(before, storage.table("t"));
        assertEquals(0, storage.delete("t", Optional.of(new Predicate("id", Comparison.EQUALS, 9L))));
        assertEquals(before, storage.table("t"));
        try (var files = Files.list(directory.resolve("db/data"))) {
            assertEquals(1, files.count());
        }
    }

    @Test
    void falsePositiveIsCarriedWhenAnotherPartitionActuallyChanges(@TempDir Path directory)
            throws Exception {
        StorageEngine storage = copied(directory, "1\n3\n2\n4\n");
        PartitionMetadata unchanged = storage.table("t").partitions().getFirst();
        assertEquals(1, storage.delete("t", Optional.of(new Predicate("id", Comparison.EQUALS, 2L))));
        assertEquals(unchanged, storage.table("t").partitions().getFirst());
        assertIds(storage, 1L, 3L, 4L);
    }

    @Test
    void deletingEverythingWritesNoEmptyReplacementAndCopyCanPopulateAgain(@TempDir Path directory)
            throws Exception {
        StorageEngine storage = copied(directory, "1\n2\n3\n");
        List<PartitionMetadata> old = storage.table("t").partitions();
        assertEquals(3, storage.delete("t", Optional.empty()));
        assertTrue(storage.table("t").partitions().isEmpty());
        assertEquals(2, storage.table("t").version());
        try (var files = Files.list(directory.resolve("db/data"))) {
            assertEquals(2, files.count());
        }
        for (PartitionMetadata partition : old) {
            assertTrue(Files.exists(directory.resolve("db/data/" + partition.fileName())));
        }
        assertIds(new StorageEngine(directory.resolve("db")));
        storage.copyFile("t", directory.resolve("rows.csv").toString());
        assertEquals(3, storage.table("t").version());
        assertIds(storage, 1L, 2L, 3L);
    }

    @Test
    void failedDeletePublicationKeepsTheOldCatalogAndRows(@TempDir Path directory)
            throws Exception {
        StorageEngine storage = copied(directory, "1\n2\n3\n4\n");
        TableCatalog before = storage.table("t");
        Files.createDirectory(directory.resolve("db/catalogs/versions/t-v2.json"));
        assertThrows(RuntimeException.class, () -> storage.delete("t",
                Optional.of(new Predicate("id", Comparison.GREATER_THAN, 1L))));
        assertEquals(before, storage.table("t"));
        assertIds(storage, 1L, 2L, 3L, 4L);
        assertIds(new StorageEngine(directory.resolve("db")), 1L, 2L, 3L, 4L);
    }

    @ParameterizedTest
    @MethodSource("typedPredicates")
    void deleteUsesTheSameComparisonsForEveryType(String predicate, List<Long> expected,
            @TempDir Path directory) throws Exception {
        Path csv = directory.resolve("typed.csv");
        Files.writeString(csv, "A,1,1.0\nB,2,2.0\nC,3,3.0\n");
        StorageEngine storage = new StorageEngine(directory.resolve("db"), 2);
        new Executor(storage).execute("CREATE TABLE t (city STRING, id LONG, price DOUBLE);"
                + "COPY t FROM '" + csv + "'; DELETE FROM t WHERE " + predicate + ";");
        List<Object[]> rows = new Executor(storage).execute("SELECT * FROM t;");
        assertEquals(expected, rows.stream().map(row -> (Long) row[1]).toList());
    }

    private static Stream<Arguments> typedPredicates() {
        return Stream.of(
                Arguments.of("city = 'B'", List.of(1L, 3L)),
                Arguments.of("city < 'B'", List.of(2L, 3L)),
                Arguments.of("city > 'B'", List.of(1L, 2L)),
                Arguments.of("id = 2", List.of(1L, 3L)),
                Arguments.of("id < 2", List.of(2L, 3L)),
                Arguments.of("id > 2", List.of(1L, 2L)),
                Arguments.of("price = 2.0", List.of(1L, 3L)),
                Arguments.of("price < 2.0", List.of(2L, 3L)),
                Arguments.of("price > 2.0", List.of(1L, 2L)));
    }

    @Test
    void summariesReportEachStatementsPinnedOrCommittedVersion(@TempDir Path directory)
            throws Exception {
        String table = "versions_" + UUID.randomUUID().toString().replace("-", "");
        Path csv = directory.resolve("rows.csv");
        Files.writeString(csv, "1\n2\n3\n4\n5\n6\n7\n8\n");
        new Executor(new StorageEngine(directory.resolve("db"), 2)).execute(
                "CREATE TABLE " + table + " (id LONG); COPY " + table + " FROM '" + csv + "';"
                + " DELETE FROM " + table + " WHERE id > 5; SELECT * FROM " + table + ";"
                + " DELETE FROM " + table + " WHERE id = 9;");
        List<String> summaries = Files.readAllLines(Path.of("logs/engine.log")).stream()
                .filter(line -> line.contains("statement=") && line.contains("table=" + table + " "))
                .toList();
        assertEquals(5, summaries.size());
        List<String> expected = List.of("CREATE", "COPY", "DELETE", "SELECT", "DELETE");
        List<Integer> versions = List.of(0, 1, 2, 2, 2);
        for (int i = 0; i < expected.size(); i++) {
            assertTrue(summaries.get(i).contains("statement=" + expected.get(i)));
            assertTrue(summaries.get(i).contains("version=" + versions.get(i) + " "));
            assertEquals(7, summaries.get(i).split(",", -1).length);
        }
        assertTrue(summaries.get(2).contains("rowsDeleted=3 partitionsRewritten=2 partitionsCarried=2"));
        assertTrue(summaries.get(3).contains("rowsOut=5 partitionsRead=3 partitionsPruned=0"));
        assertTrue(summaries.get(4).contains("rowsDeleted=0 partitionsRewritten=0 partitionsCarried=3"));
    }

    private static StorageEngine copied(Path directory, String data) throws Exception {
        Path csv = directory.resolve("rows.csv");
        Files.writeString(csv, data);
        StorageEngine storage = new StorageEngine(directory.resolve("db"), 2);
        storage.createTable("t", List.of(new ColumnSpec("id", ColumnType.LONG)));
        storage.copyFile("t", csv.toString());
        return storage;
    }

    private static byte[] bytes(Path directory, PartitionMetadata partition) {
        try {
            return Files.readAllBytes(directory.resolve("db/data/" + partition.fileName()));
        } catch (java.io.IOException error) {
            throw new java.io.UncheckedIOException(error);
        }
    }

    private static void assertIds(StorageEngine storage, Long... expected) {
        List<Object[]> rows = new Executor(storage).execute("SELECT * FROM t;");
        assertEquals(expected.length, rows.size());
        for (int i = 0; i < expected.length; i++) {
            assertArrayEquals(new Object[] {expected[i]}, rows.get(i));
        }
    }
}
