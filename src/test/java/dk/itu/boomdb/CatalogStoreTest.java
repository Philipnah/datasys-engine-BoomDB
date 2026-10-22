package dk.itu.boomdb;

import com.fasterxml.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class CatalogStoreTest {
    @Test
    void newVersionsUseASeparateDirectory(@TempDir Path directory) {
        CatalogStore store = new CatalogStore(directory);

        store.save(table(0, System.currentTimeMillis()));

        assertTrue(Files.isRegularFile(directory.resolve("catalogs/versions/trips-v0.json")));
        assertFalse(Files.exists(directory.resolve("catalogs/trips-v0.json")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"trips.json", "trips-v1.json"})
    void saveRejectsAnExistingLogicalVersionInEitherFlatLayout(String fileName,
            @TempDir Path directory) throws Exception {
        CatalogStore store = new CatalogStore(directory);
        Path old = directory.resolve("catalogs").resolve(fileName);
        writeCatalog(old, table(1, System.currentTimeMillis()));
        String original = Files.readString(old);

        assertThrows(UncheckedIOException.class, () -> store.save(table(1, 0)));

        assertEquals(original, Files.readString(old));
        assertFalse(Files.exists(directory.resolve("catalogs/versions/trips-v1.json")));
        store.save(table(2, System.currentTimeMillis()));
        assertEquals(2, new CatalogStore(directory).load("trips").orElseThrow().version());
    }

    @ParameterizedTest
    @CsvSource({"trips.json,trips-v1.json", "trips.json,versions/trips-v1.json",
            "trips-v1.json,versions/trips-v1.json"})
    void duplicateVersionsAcrossLayoutsAreRejected(String first, String second,
            @TempDir Path directory) throws Exception {
        CatalogStore store = new CatalogStore(directory);
        TableCatalog duplicate = table(1, System.currentTimeMillis());
        writeCatalog(directory.resolve("catalogs").resolve(first), duplicate);
        writeCatalog(directory.resolve("catalogs").resolve(second), duplicate);

        assertThrows(UncheckedIOException.class, () -> store.load("trips"));
        assertThrows(UncheckedIOException.class, () -> store.save(table(2, System.currentTimeMillis())));
        assertFalse(Files.exists(directory.resolve("catalogs/versions/trips-v2.json")));
    }

    @Test
    void discoveryOrdersBothLayoutsNumericallyAndIgnoresTemporaryFiles(@TempDir Path directory)
            throws Exception {
        CatalogStore store = new CatalogStore(directory);
        writeCatalog(directory.resolve("catalogs/trips-v2.json"), table(2, System.currentTimeMillis()));
        writeCatalog(directory.resolve("catalogs/versions/trips-v10.json"), table(10, System.currentTimeMillis()));
        Files.writeString(directory.resolve("catalogs/versions/trips-v11.json.tmp"), "unfinished");

        assertEquals(10, store.load("trips").orElseThrow().version());
        try (CatalogStore.Snapshot snapshot = store.pin("trips", 2)) {
            assertEquals(2, snapshot.table().version());
        }
    }

    @Test
    void retentionRemovesOldFlatVersionsWithoutResurrectionOrForeignDeletion(@TempDir Path directory)
            throws Exception {
        CatalogStore store = new CatalogStore(directory);
        Path old = directory.resolve("catalogs/trips-v0.json");
        writeCatalog(old, table(0, System.currentTimeMillis()));
        Path foreign = directory.resolve("catalogs/trips-v1.json");
        writeCatalog(foreign, new TableCatalog("trips-v1", List.of(
                new ColumnSpec("other", ColumnType.STRING)), false, List.of(), 0, System.currentTimeMillis()));
        String foreignBytes = Files.readString(foreign);
        try (CatalogStore.Snapshot snapshot = store.pin("trips", 0)) {
            for (long version = 1; version <= 6; version++) {
                store.save(table(version, System.currentTimeMillis()));
            }
            assertEquals(0, snapshot.table().version());
            assertTrue(Files.exists(old), "an active lease protects an old flat catalog");
        }

        assertFalse(Files.exists(old));
        CatalogStore restarted = new CatalogStore(directory);
        assertThrows(IllegalArgumentException.class, () -> restarted.pin("trips", 0));
        assertEquals(6, restarted.load("trips").orElseThrow().version());
        assertEquals(foreignBytes, Files.readString(foreign));
        assertEquals("other", restarted.load("trips-v1").orElseThrow().columns().getFirst().name());
    }

    @ParameterizedTest
    @ValueSource(strings = {"malformed", "wrong-table", "wrong-version", "missing-version"})
    void invalidNewCatalogsAreNotSilentlyIgnored(String defect, @TempDir Path directory)
            throws Exception {
        CatalogStore store = new CatalogStore(directory);
        Path path = directory.resolve("catalogs/versions/trips-v1.json");
        Files.createDirectories(path.getParent());
        switch (defect) {
            case "malformed" -> Files.writeString(path, "unfinished JSON");
            case "wrong-table" -> writeCatalog(path, new TableCatalog("other", List.of(
                    new ColumnSpec("n", ColumnType.LONG)), true, List.of(), 1, System.currentTimeMillis()));
            case "wrong-version" -> writeCatalog(path, table(2, System.currentTimeMillis()));
            case "missing-version" -> Files.writeString(path, """
                    {"tableName":"trips","columns":[{"name":"distance","type":"LONG"}],
                     "copied":true,"partitions":[]}
                    """);
            default -> throw new AssertionError(defect);
        }

        assertThrows(UncheckedIOException.class, () -> store.load("trips"));
    }

    @Test
    void malformedFlatCatalogsAreNotMisclassifiedAsForeign(@TempDir Path directory) throws Exception {
        CatalogStore store = new CatalogStore(directory);
        Files.writeString(directory.resolve("catalogs/trips-v1.json"), "unfinished JSON");

        assertThrows(UncheckedIOException.class, () -> store.load("trips"));
    }

    @Test
    void aMissingTableIdentityCannotBeIgnoredAsAnotherTablesCatalog(@TempDir Path directory)
            throws Exception {
        CatalogStore store = new CatalogStore(directory);
        Files.writeString(directory.resolve("catalogs/null.json"), """
                {"tableName":null,"columns":[{"name":"n","type":"LONG"}],
                 "copied":false,"partitions":[]}
                """);

        assertThrows(UncheckedIOException.class, () -> store.load("null"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "true", "\"1\"", "1.9", "-1", "9223372036854775808"})
    void malformedExplicitVersionsAreNotCoercedIntoValidIdentities(String version,
            @TempDir Path directory) throws Exception {
        CatalogStore store = new CatalogStore(directory);
        Files.writeString(directory.resolve("catalogs/trips.json"), """
                {"tableName":"trips","columns":[{"name":"distance","type":"LONG"}],
                 "copied":true,"partitions":[],"version":%s}
                """.formatted(version));

        assertThrows(UncheckedIOException.class, () -> store.load("trips"));
    }

    private static void writeCatalog(Path path, TableCatalog table) throws Exception {
        Files.createDirectories(path.getParent());
        new ObjectMapper().writeValue(path.toFile(), table);
    }

    @Test
    void snapshotsDoNotChangeWhenSourceListsAreMutated() {
        List<ColumnSpec> columns = new ArrayList<>(List.of(
                new ColumnSpec("distance", ColumnType.LONG)));
        List<ColumnStatistics> statistics = new ArrayList<>(List.of(
                new ColumnStatistics("distance", "1", "1")));
        List<PartitionMetadata> partitions = new ArrayList<>(List.of(
                new PartitionMetadata(0, "original.bin", 1, statistics)));
        TableCatalog table = new TableCatalog("trips", columns, true, partitions);

        columns.clear();
        statistics.clear();
        partitions.clear();

        assertEquals(1, table.columns().size());
        assertEquals(1, table.partitions().size());
        assertEquals(1, table.partitions().getFirst().statistics().size());
        assertThrows(UnsupportedOperationException.class, () -> table.partitions().clear());
    }

    @Test
    void restartDiscoversHighestNumericFinalizedVersion(@TempDir Path directory) throws Exception {
        CatalogStore store = new CatalogStore(directory);
        Path catalogs = directory.resolve("catalogs");
        Files.writeString(catalogs.resolve("trips-v2.json"), catalogJson(2, "older.bin"));
        Files.writeString(catalogs.resolve("trips-v10.json"), catalogJson(10, "newer.bin"));
        Files.writeString(catalogs.resolve("trips-v11.json.tmp"), "unfinished");

        assertTrue(store.load("trips").isPresent(), "finalized versions must be discoverable");
        assertEquals("newer.bin", store.load("trips").orElseThrow()
                .partitions().getFirst().fileName());
    }

    @Test
    void unsupportedNamesCannotEscapeTheCatalogDirectory(@TempDir Path directory) {
        CatalogStore store = new CatalogStore(directory);

        assertThrows(IllegalArgumentException.class, () -> store.load("../outside"));
        assertThrows(IllegalArgumentException.class, () -> store.load(""));
    }

    @Test
    void savePublishesACompleteVersionAndRejectsReplacement(@TempDir Path directory)
            throws Exception {
        CatalogStore store = new CatalogStore(directory);
        store.save(table(0, System.currentTimeMillis()));
        Path committed = directory.resolve("catalogs/versions/trips-v0.json");
        String original = Files.readString(committed);

        assertThrows(UncheckedIOException.class, () -> store.save(table(0, 0)));

        assertEquals(original, Files.readString(committed));
        assertEquals(0, new CatalogStore(directory).load("trips").orElseThrow().version());
        try (var files = Files.list(directory.resolve("catalogs/versions"))) {
            assertEquals(List.of("trips-v0.json"), files
                    .map(path -> path.getFileName().toString()).toList());
        }
    }

    @Test
    void failedPublicationPreservesTheLastCommittedVersion(@TempDir Path directory)
            throws Exception {
        CatalogStore store = new CatalogStore(directory);
        store.save(table(0, System.currentTimeMillis()));
        Path blocked = directory.resolve("catalogs/versions/trips-v1.json");
        Files.createDirectory(blocked);
        Files.writeString(blocked.resolve("block-move"), "occupied");

        assertThrows(UncheckedIOException.class,
                () -> store.save(table(1, System.currentTimeMillis())));

        assertEquals(0, store.load("trips").orElseThrow().version());
        try (var files = Files.list(directory.resolve("catalogs/versions"))) {
            assertFalse(files.anyMatch(path -> path.getFileName().toString().endsWith(".tmp")));
        }
    }

    @Test
    void oldLegacyCatalogsHaveAConsistentMigrationVersion(@TempDir Path directory)
            throws Exception {
        CatalogStore store = new CatalogStore(directory);
        Files.writeString(directory.resolve("catalogs/trips.json"), """
                {"tableName":"trips","columns":[{"name":"distance","type":"LONG"}],
                 "copied":true,"partitions":[]}
                """);

        assertEquals(1, store.load("trips").orElseThrow().version());
        store.save(table(2, System.currentTimeMillis()));

        assertEquals(2, new CatalogStore(directory).load("trips").orElseThrow().version());
    }

    @Test
    void expiredVersionsAreRemovedButTheLatestVersionAlwaysSurvives(@TempDir Path directory) {
        CatalogStore store = new CatalogStore(directory);
        long oldCommit = System.currentTimeMillis() - Duration.ofHours(2).toMillis();
        store.save(table(0, oldCommit));
        assertEquals(0, store.load("trips").orElseThrow().version());

        store.save(table(1, System.currentTimeMillis()));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> store.pin("trips", 0));
        assertTrue(error.getMessage().contains("version 0"));
        assertEquals(1, store.load("trips").orElseThrow().version());
    }

    @Test
    void leasesAcrossStoresOverrideRetentionUntilEveryLeaseCloses(@TempDir Path directory)
            throws Exception {
        CatalogStore writer = new CatalogStore(directory);
        CatalogStore reader = new CatalogStore(directory);
        Path partition = directory.resolve("data/original.bin");
        Files.createDirectories(partition.getParent());
        Files.writeString(partition, "immutable partition bytes");
        writer.save(table(0, System.currentTimeMillis() - Duration.ofHours(2).toMillis()));
        CatalogStore.Snapshot first = reader.pin("trips");
        CatalogStore.Snapshot second = reader.pin("trips", 0);

        for (long version = 1; version <= 6; version++) {
            writer.save(table(version, System.currentTimeMillis()));
        }

        assertEquals(0, first.table().version());
        assertEquals(6, reader.load("trips").orElseThrow().version());
        assertTrue(Files.exists(directory.resolve("catalogs/versions/trips-v0.json")));
        assertFalse(Files.exists(directory.resolve("catalogs/versions/trips-v1.json")));
        first.close();
        first.close();
        assertTrue(Files.exists(directory.resolve("catalogs/versions/trips-v0.json")));
        second.close();

        assertThrows(IllegalArgumentException.class, () -> reader.pin("trips", 0));
        try (var files = Files.list(directory.resolve("catalogs/versions"))) {
            assertEquals(5, files.count());
        }
        assertEquals("immutable partition bytes", Files.readString(partition));
    }

    private static TableCatalog table(long version, long committedAtMillis) {
        return new TableCatalog("trips", List.of(new ColumnSpec("distance", ColumnType.LONG)),
                version > 0, List.of(), version, committedAtMillis);
    }

    private static String catalogJson(long version, String fileName) {
        return """
                {"tableName":"trips","columns":[{"name":"distance","type":"LONG"}],
                 "copied":true,"partitions":[{"id":0,"fileName":"%s","rowCount":1,
                 "statistics":[{"columnName":"distance","min":"1","max":"1"}]}],
                 "version":%d,"committedAtMillis":%d}
                """.formatted(fileName, version, System.currentTimeMillis());
    }
}
