package dk.itu.boomdb;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Exercises catalog naming through the storage API, including legacy flat catalogs. */
class CatalogNamesIT {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void versionLikeTableNamesStayIndependentAcrossWritesAndRestart(boolean suffixFirst,
            @TempDir Path directory) throws Exception {
        StorageEngine storage = new StorageEngine(directory, 2);
        Map<String, List<ColumnSpec>> schemas = Map.of(
                "t", List.of(new ColumnSpec("n", ColumnType.LONG)),
                "t-v0", List.of(new ColumnSpec("zero", ColumnType.LONG)),
                "t-v1", List.of(new ColumnSpec("one", ColumnType.LONG)));
        // Version-looking suffixes are legal table names through the public storage API.
        List<String> creationOrder = suffixFirst
                ? List.of("t-v1", "t-v0", "t") : List.of("t", "t-v0", "t-v1");
        for (String table : creationOrder) {
            storage.createTable(table, schemas.get(table));
        }
        Path csv = directory.resolve("rows.csv");
        Files.writeString(csv, "1\n2\n");
        storage.copyFile("t", csv.toString());
        Files.writeString(csv, "10\n20\n");
        storage.copyFile("t-v0", csv.toString());
        Files.writeString(csv, "100\n200\n");
        storage.copyFile("t-v1", csv.toString());

        Files.writeString(csv, "3\n");
        storage.copyFile("t", csv.toString());

        assertEquals(List.of(1L, 2L, 3L), rows(storage, "t", "n"));
        assertEquals(List.of(10L, 20L), rows(storage, "t-v0", "zero"));
        assertEquals(List.of(100L, 200L), rows(storage, "t-v1", "one"));
        assertEquals(1, storage.delete("t", Optional.of(
                new Predicate("n", Comparison.EQUALS, 1L))));
        assertEquals(List.of(2L, 3L), rows(storage, "t", "n"));
        assertEquals(List.of(10L, 20L), rows(storage, "t-v0", "zero"));
        assertEquals(List.of(100L, 200L), rows(storage, "t-v1", "one"));

        Files.writeString(csv, "30\n");
        storage.copyFile("t-v0", csv.toString());
        assertEquals(1, storage.delete("t-v1", Optional.of(
                new Predicate("one", Comparison.EQUALS, 100L))));
        assertEquals(List.of(2L, 3L), rows(storage, "t", "n"));
        assertEquals(List.of(10L, 20L, 30L), rows(storage, "t-v0", "zero"));
        assertEquals(List.of(200L), rows(storage, "t-v1", "one"));

        StorageEngine restarted = new StorageEngine(directory, 2);
        for (String table : List.of("t", "t-v0", "t-v1")) {
            assertEquals(schemas.get(table), restarted.schema(table));
        }
        assertEquals(List.of(2L, 3L), rows(restarted, "t", "n"));
        assertEquals(List.of(10L, 20L, 30L), rows(restarted, "t-v0", "zero"));
        assertEquals(List.of(200L), rows(restarted, "t-v1", "one"));
    }

    @Test
    void updatingLegacyTablePreservesAnotherTableWithAVersionLikeName(@TempDir Path directory)
            throws Exception {
        StorageEngine storage = new StorageEngine(directory, 2);
        Path legacy = directory.resolve("catalogs/t.json");
        Path otherLegacy = directory.resolve("catalogs/t-v1.json");
        Files.writeString(legacy, """
                {"tableName":"t","columns":[{"name":"n","type":"LONG"}],
                 "copied":false,"partitions":[]}
                """);
        // This is the legacy table t-v1, rather than version 1 of table t.
        Files.writeString(otherLegacy, """
                {"tableName":"t-v1","columns":[{"name":"other","type":"LONG"}],
                 "copied":false,"partitions":[]}
                """);
        byte[] originalLegacy = Files.readAllBytes(legacy);
        byte[] originalOtherLegacy = Files.readAllBytes(otherLegacy);
        assertEquals(List.of(new ColumnSpec("n", ColumnType.LONG)), storage.schema("t"));
        assertEquals(List.of(new ColumnSpec("other", ColumnType.LONG)), storage.schema("t-v1"));
        Path csv = directory.resolve("rows.csv");
        Files.writeString(csv, "10\n");
        storage.copyFile("t-v1", csv.toString());
        Files.writeString(csv, "1\n2\n");

        storage.copyFile("t", csv.toString());

        assertTrue(Files.isRegularFile(directory.resolve("catalogs/versions/t-v1.json")));
        assertArrayEquals(originalLegacy, Files.readAllBytes(legacy));
        assertArrayEquals(originalOtherLegacy, Files.readAllBytes(otherLegacy));
        assertEquals(List.of(1L, 2L), rows(storage, "t", "n"));
        assertEquals(List.of(10L), rows(storage, "t-v1", "other"));
        StorageEngine restarted = new StorageEngine(directory, 2);
        assertEquals(List.of(new ColumnSpec("n", ColumnType.LONG)), restarted.schema("t"));
        assertEquals(List.of(new ColumnSpec("other", ColumnType.LONG)), restarted.schema("t-v1"));
        assertEquals(List.of(1L, 2L), rows(restarted, "t", "n"));
        assertEquals(List.of(10L), rows(restarted, "t-v1", "other"));
        assertArrayEquals(originalOtherLegacy, Files.readAllBytes(otherLegacy));
    }

    private static List<Long> rows(StorageEngine storage, String table, String column) {
        return storage.select(table, column, Comparison.GREATER_THAN, Long.MIN_VALUE).stream()
                .map(row -> (Long) row[0]).toList();
    }
}
