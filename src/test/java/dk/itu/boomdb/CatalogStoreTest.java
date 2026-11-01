package dk.itu.boomdb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CatalogStoreTest {
    private static final List<ColumnSpec> COLUMNS = List.of(new ColumnSpec("id", ColumnType.LONG));

    @Test
    void staleCopyKeepsBothAdditionsWithDistinctNumericIds(@TempDir Path directory) {
        CatalogStore store = new CatalogStore(directory);
        PartitionMetadata original = partition(0, "original.bin");
        TableCatalog base = new TableCatalog("t", COLUMNS, 1, List.of(original));
        store.save(base);
        store.commit(base, List.of(original, partition(1, "winner.bin")));

        assertEquals(3, store.commit(base, List.of(original, partition(1, "loser.bin"))));

        TableCatalog current = store.load("t").orElseThrow();
        assertEquals(List.of("original.bin", "winner.bin", "loser.bin"), names(current));
        assertEquals(List.of(0, 1, 2), current.partitions().stream().map(PartitionMetadata::id).toList());
        assertEquals(current, new CatalogStore(directory).load("t").orElseThrow());
    }

    @Test
    void staleCopyDoesNotRestoreARewrittenOriginal(@TempDir Path directory) {
        CatalogStore store = new CatalogStore(directory);
        PartitionMetadata original = partition(0, "original.bin");
        TableCatalog base = new TableCatalog("t", COLUMNS, 1, List.of(original));
        store.save(base);
        store.commit(base, List.of(partition(1, "survivors.bin")));

        store.commit(base, List.of(original, partition(1, "copy.bin")));

        assertEquals(List.of("survivors.bin", "copy.bin"), names(store.load("t").orElseThrow()));
    }

    @Test
    void staleDeleteKeepsConcurrentCopyAndOnlyItsOwnSurvivors(@TempDir Path directory) {
        CatalogStore store = new CatalogStore(directory);
        PartitionMetadata original = partition(0, "original.bin");
        PartitionMetadata carried = partition(1, "carried.bin");
        TableCatalog base = new TableCatalog("t", COLUMNS, 1, List.of(original, carried));
        store.save(base);
        store.commit(base, List.of(original, carried, partition(2, "copy.bin")));

        store.commit(base, List.of(partition(2, "survivors.bin"), carried));

        assertEquals(List.of("carried.bin", "copy.bin", "survivors.bin"),
                names(store.load("t").orElseThrow()));
    }

    @Test
    void deletesOfDisjointOriginalsRebase(@TempDir Path directory) {
        CatalogStore store = new CatalogStore(directory);
        PartitionMetadata first = partition(0, "first.bin");
        PartitionMetadata second = partition(1, "second.bin");
        TableCatalog base = new TableCatalog("t", COLUMNS, 1, List.of(first, second));
        store.save(base);
        store.commit(base, List.of(partition(2, "winner.bin"), second));

        assertEquals(3, store.commit(base, List.of(first, partition(2, "loser.bin"))));

        assertEquals(List.of("winner.bin", "loser.bin"), names(store.load("t").orElseThrow()));
    }

    @Test
    void overlappingDeletesRejectStaleSurvivorsEvenWhenWinnerRemovedEverything(@TempDir Path directory) {
        CatalogStore store = new CatalogStore(directory);
        TableCatalog base = new TableCatalog("t", COLUMNS, 1, List.of(partition(0, "original.bin")));
        store.save(base);
        store.commit(base, List.of());
        TableCatalog winner = store.load("t").orElseThrow();

        assertThrows(ConflictException.class,
                () -> store.commit(base, List.of(partition(1, "stale-survivors.bin"))));

        assertEquals(winner, store.load("t").orElseThrow());
        assertEquals(winner, new CatalogStore(directory).load("t").orElseThrow());
    }

    @Test
    void staleDeleteOfAllBaseRowsPreservesConcurrentInserts(@TempDir Path directory) {
        CatalogStore store = new CatalogStore(directory);
        PartitionMetadata original = partition(0, "original.bin");
        TableCatalog base = new TableCatalog("t", COLUMNS, 1, List.of(original));
        store.save(base);
        store.commit(base, List.of(original, partition(1, "copy.bin")));

        store.commit(base, List.of());

        assertEquals(List.of("copy.bin"), names(store.load("t").orElseThrow()));
    }

    @Test
    void rebaseNeedsOnlyPinnedBaseAndLatestVersionAfterRetention(@TempDir Path directory) {
        CatalogStore store = new CatalogStore(directory);
        PartitionMetadata original = partition(0, "original.bin");
        store.save(new TableCatalog("t", COLUMNS, 1, List.of(original)));
        try (CatalogStore.Snapshot pinned = store.acquire("t")) {
            for (int i = 0; i < 7; i++) {
                TableCatalog current = store.load("t").orElseThrow();
                List<PartitionMetadata> next = new ArrayList<>(current.partitions());
                next.add(partition(i + 1, "copy-" + i + ".bin"));
                store.commit(current, next);
            }
            assertFalse(Files.exists(directory.resolve("catalogs/versions/t-v2.json")));

            assertEquals(9, store.commit(pinned.table(), List.of(partition(1, "survivors.bin"))));

            assertEquals(List.of("copy-0.bin", "copy-1.bin", "copy-2.bin", "copy-3.bin",
                    "copy-4.bin", "copy-5.bin", "copy-6.bin", "survivors.bin"),
                    names(store.load("t").orElseThrow()));
        }
    }

    @Test
    void restartChoosesNumericFinalizedVersionAndIgnoresTemporaryFiles(@TempDir Path directory)
            throws Exception {
        CatalogStore store = new CatalogStore(directory);
        store.save(table("t", 2));
        store.save(table("t", 10));
        Files.writeString(directory.resolve("catalogs/versions/t-v99.json.tmp"), "unfinished");

        assertEquals(10, new CatalogStore(directory).load("t").orElseThrow().version());
        assertTrue(Files.exists(directory.resolve("catalogs/versions/t-v10.json")));
        assertThrows(IllegalArgumentException.class, () -> store.save(table("t", 10)));
    }

    @Test
    void pinnedVersionsSurviveCountRetentionUntilTheirLastLeaseCloses(@TempDir Path directory) {
        CatalogStore store = new CatalogStore(directory);
        store.save(table("t", 0));
        CatalogStore.Snapshot first = store.acquire("t");
        CatalogStore.Snapshot second = store.acquire("t", 0);
        for (int version = 1; version <= 6; version++) {
            store.save(table("t", version));
        }

        assertEquals(0, first.table().version());
        assertTrue(Files.exists(directory.resolve("catalogs/versions/t-v0.json")));
        first.close();
        first.close(); // Closing a lease twice must not release another reader's pin.
        assertTrue(Files.exists(directory.resolve("catalogs/versions/t-v0.json")));
        second.close();
        assertFalse(Files.exists(directory.resolve("catalogs/versions/t-v0.json")));
        assertThrows(IllegalArgumentException.class, () -> store.acquire("t", 0));
        assertEquals(6, store.load("t").orElseThrow().version());
    }

    @Test
    void expiredLegacyFilesAreRemovedInPlaceAndCannotResurrectOnRestart(@TempDir Path directory)
            throws Exception {
        Path legacy = directory.resolve("catalogs/t.json");
        write(legacy, table("t", 0));
        Clock future = Clock.fixed(Instant.now().plus(Duration.ofHours(2)), ZoneOffset.UTC);
        CatalogStore store = new CatalogStore(directory, future);
        try (CatalogStore.Snapshot pinned = store.acquire("t")) {
            store.save(table("t", 1));
            assertTrue(Files.exists(legacy));
            assertEquals(0, pinned.table().version());
        }

        assertFalse(Files.exists(legacy));
        assertEquals(1, new CatalogStore(directory, future).load("t").orElseThrow().version());
        assertThrows(IllegalArgumentException.class, () -> store.acquire("t", 0));
    }

    @Test
    void foreignLegacyTableThatLooksVersionedIsNotMistakenForThisTable(@TempDir Path directory)
            throws Exception {
        write(directory.resolve("catalogs/t-v1.json"), table("t-v1", 0));
        write(directory.resolve("catalogs/t-v3.json"), table("t", 3));

        CatalogStore store = new CatalogStore(directory);
        assertEquals(0, store.load("t-v1").orElseThrow().version());
        assertEquals(3, store.load("t").orElseThrow().version());
        store.save(table("t", 4));
        assertThrows(IllegalArgumentException.class, () -> store.save(table("t", 3)));
    }

    @Test
    void legacyCopiedCatalogRemainsReadable(@TempDir Path directory) throws Exception {
        Path legacy = directory.resolve("catalogs/t.json");
        Files.createDirectories(legacy.getParent());
        Files.writeString(legacy, """
                {"tableName":"t","columns":[{"name":"id","type":"LONG"}],
                 "copied":true,"partitions":[]}
                """);
        CatalogStore store = new CatalogStore(directory);
        assertEquals(0, store.load("t").orElseThrow().version());
        store.save(table("t", 1));
        assertEquals(1, new CatalogStore(directory).load("t").orElseThrow().version());
    }

    @Test
    void malformedIdentityAndDuplicateLogicalVersionsAreRejected(@TempDir Path directory)
            throws Exception {
        Path malformed = directory.resolve("catalogs/t-v2.json");
        write(malformed, table("t", 1));
        assertThrows(IllegalArgumentException.class, () -> new CatalogStore(directory).load("t"));

        write(malformed, table("t", 2));
        write(directory.resolve("catalogs/versions/t-v2.json"), table("t", 2));
        assertThrows(IllegalArgumentException.class, () -> new CatalogStore(directory).load("t"));
    }

    @Test
    void failedPublicationLeavesTheCommittedVersionReadable(@TempDir Path directory)
            throws Exception {
        CatalogStore store = new CatalogStore(directory);
        store.save(table("t", 0));
        Files.createDirectory(directory.resolve("catalogs/versions/t-v1.json"));

        assertThrows(RuntimeException.class, () -> store.save(table("t", 1)));
        assertEquals(0, store.load("t").orElseThrow().version());
        assertEquals(0, new CatalogStore(directory).load("t").orElseThrow().version());
    }

    @Test
    void snapshotsDefensivelyCopyPartitionListsAndStatistics(@TempDir Path directory) {
        List<ColumnStatistics> stats = new ArrayList<>(List.of(new ColumnStatistics("id", "1", "2")));
        PartitionMetadata partition = new PartitionMetadata(0, "p.bin", 2, stats);
        List<PartitionMetadata> partitions = new ArrayList<>(List.of(partition));
        List<ColumnSpec> columns = new ArrayList<>(COLUMNS);
        TableCatalog table = new TableCatalog("t", columns, 0, partitions);
        columns.clear();
        partitions.clear();
        stats.clear();

        CatalogStore store = new CatalogStore(directory);
        store.save(table);
        try (CatalogStore.Snapshot snapshot = store.acquire("t")) {
            assertEquals(COLUMNS, snapshot.table().columns());
            assertEquals(1, snapshot.table().partitions().size());
            assertEquals(List.of(new ColumnStatistics("id", "1", "2")),
                    snapshot.table().partitions().getFirst().statistics());
            assertThrows(UnsupportedOperationException.class, snapshot.table().partitions()::clear);
        }
    }

    private static PartitionMetadata partition(int id, String fileName) {
        return new PartitionMetadata(id, fileName, 1, List.of(new ColumnStatistics("id", "1", "1")));
    }

    private static List<String> names(TableCatalog table) {
        return table.partitions().stream().map(PartitionMetadata::fileName).toList();
    }

    private static TableCatalog table(String name, long version) {
        return new TableCatalog(name, COLUMNS, version, List.of());
    }

    private static void write(Path file, TableCatalog table) throws Exception {
        Files.createDirectories(file.getParent());
        new ObjectMapper().writeValue(file.toFile(), table);
    }
}
