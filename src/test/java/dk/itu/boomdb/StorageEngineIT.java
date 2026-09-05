package dk.itu.boomdb;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class StorageEngineIT {
    private static final Path TRIPS_CSV = Path.of("src/test/resources/trips.csv");
    private static final List<ColumnSpec> TRIP_COLUMNS = List.of(
            new ColumnSpec("city", ColumnType.STRING),
            new ColumnSpec("distance", ColumnType.LONG),
            new ColumnSpec("price", ColumnType.DOUBLE));
    private static final List<Object[]> GOLDEN_ROWS = List.<Object[]>of(
            new Object[] {"Copenhagen", 12L, 23.5},
            new Object[] {"Aarhus", 187L, 301.0},
            new Object[] {"Odense", 95L, 120.75},
            new Object[] {"Copenhagen", 140L, 210.0},
            new Object[] {"Aalborg", 210L, 340.5},
            new Object[] {"Roskilde", 31L, 45.0},
            new Object[] {"Copenhagen", 88L, 99.99},
            new Object[] {"Esbjerg", 299L, 450.25});

    @TempDir
    Path testDirectory;

    @Test
    void schemaSurvivesEngineRestart(@TempDir Path directory) {
        new StorageEngine(directory).createTable("trips", TRIP_COLUMNS);

        StorageEngine restarted = new StorageEngine(directory);

        assertThrows(IllegalArgumentException.class,
                () -> restarted.createTable("trips", TRIP_COLUMNS));
    }

    @Test
    void duplicateTableIsRejected(@TempDir Path directory) {
        StorageEngine engine = new StorageEngine(directory);
        engine.createTable("trips", TRIP_COLUMNS);

        assertThrows(IllegalArgumentException.class,
                () -> engine.createTable("trips", TRIP_COLUMNS));
    }

    @Test
    void emptySchemaIsRejected(@TempDir Path directory) {
        StorageEngine engine = new StorageEngine(directory);

        assertThrows(IllegalArgumentException.class,
                () -> engine.createTable("trips", List.of()));
    }

    @Test
    void duplicateColumnNameIsRejected(@TempDir Path directory) {
        StorageEngine engine = new StorageEngine(directory);
        List<ColumnSpec> duplicateColumns = List.of(
                new ColumnSpec("city", ColumnType.STRING),
                new ColumnSpec("city", ColumnType.LONG));

        assertThrows(IllegalArgumentException.class,
                () -> engine.createTable("trips", duplicateColumns));
    }

    @Test
    void nonPositivePartitionSizeIsRejected(@TempDir Path directory) {
        assertThrows(IllegalArgumentException.class,
                () -> new StorageEngine(directory, 0));
    }

    @Test
    void copyCreatesFourBinaryPartitionsWithCatalogStatistics(@TempDir Path directory)
            throws Exception {
        StorageEngine engine = createTripsTable(directory, 2);

        engine.copyFile("trips", TRIPS_CSV.toString());

        TableCatalog catalog = new CatalogStore(directory).load("trips").orElseThrow();
        assertEquals(4, catalog.partitions().size());
        assertEquals(List.of(
                new ColumnStatistics("city", "Aarhus", "Copenhagen"),
                new ColumnStatistics("distance", "12", "187"),
                new ColumnStatistics("price", "23.5", "301.0")),
                catalog.partitions().getFirst().statistics());
        assertEquals(List.of("trips-0.bin", "trips-1.bin", "trips-2.bin", "trips-3.bin"),
                catalog.partitions().stream().map(PartitionMetadata::fileName).toList());
        assertEquals(4, Files.list(directory.resolve("data")).count());
        List<Object[]> firstPartition = StorageSupport.readPartition(
                directory.resolve("data/trips-0.bin"), TRIP_COLUMNS);
        assertArrayEquals(new Object[] {"Copenhagen", 12L, 23.5}, firstPartition.getFirst());
        assertArrayEquals(new Object[] {"Aarhus", 187L, 301.0}, firstPartition.getLast());
    }

    @Test
    void copyToUnknownTableIsRejected(@TempDir Path directory) {
        StorageEngine engine = new StorageEngine(directory);

        assertThrows(IllegalArgumentException.class,
                () -> engine.copyFile("missing", TRIPS_CSV.toString()));
    }

    @Test
    void secondCopyToTableIsRejected(@TempDir Path directory) {
        StorageEngine engine = createTripsTable(directory, 2);
        engine.copyFile("trips", TRIPS_CSV.toString());

        assertThrows(UnsupportedOperationException.class,
                () -> engine.copyFile("trips", TRIPS_CSV.toString()));
    }

    @Test
    void malformedCopyReportsFileAndLineWithoutPublishingData(@TempDir Path directory)
            throws Exception {
        Path csv = directory.resolve("bad.csv");
        Files.writeString(csv, "Copenhagen,12,23.5\nAarhus,nope,301.0\n");
        StorageEngine engine = createTripsTable(directory, 2);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> engine.copyFile("trips", csv.toString()));

        assertTrue(error.getMessage().contains(csv.toString()));
        assertTrue(error.getMessage().contains("line 2"));
        assertFalse(new CatalogStore(directory).load("trips").orElseThrow().copied());
        assertEquals(0, Files.list(directory.resolve("data")).count());
    }

    @Test
    void wrongFieldCountReportsFileAndLineWithoutPublishingData(@TempDir Path directory)
            throws Exception {
        Path csv = directory.resolve("short.csv");
        Files.writeString(csv, "Copenhagen,12\n");
        StorageEngine engine = createTripsTable(directory, 2);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> engine.copyFile("trips", csv.toString()));

        assertTrue(error.getMessage().contains(csv.toString()));
        assertTrue(error.getMessage().contains("line 1"));
        assertFalse(new CatalogStore(directory).load("trips").orElseThrow().copied());
        assertEquals(0, Files.list(directory.resolve("data")).count());
    }

    @ParameterizedTest(name = "{0} {1} {2}")
    @MethodSource("selectCases")
    void selectSupportsEveryComparisonForEveryType(String column, Comparison comparison,
            Object constant, int[] expectedRowIndexes) {
        StorageEngine engine = createTripsTable(testDirectory, 2);
        engine.copyFile("trips", TRIPS_CSV.toString());

        List<Object[]> actual = engine.select("trips", column, comparison, constant);

        assertRows(actual, expectedRowIndexes);
    }

    private static Stream<Arguments> selectCases() {
        return Stream.of(
                Arguments.of("city", Comparison.EQUALS, "Copenhagen", new int[] {0, 3, 6}),
                Arguments.of("city", Comparison.LESS_THAN, "Copenhagen", new int[] {1, 4}),
                Arguments.of("city", Comparison.GREATER_THAN, "Copenhagen", new int[] {2, 5, 7}),
                Arguments.of("distance", Comparison.EQUALS, 95L, new int[] {2}),
                Arguments.of("distance", Comparison.LESS_THAN, 95L, new int[] {0, 5, 6}),
                Arguments.of("distance", Comparison.GREATER_THAN, 100L, new int[] {1, 3, 4, 7}),
                Arguments.of("price", Comparison.EQUALS, 99.99, new int[] {6}),
                Arguments.of("price", Comparison.LESS_THAN, 50.0, new int[] {0, 5}),
                Arguments.of("price", Comparison.GREATER_THAN, 300.0, new int[] {1, 4, 7}));
    }

    @Test
    void selectCanReturnAnEmptyResult(@TempDir Path directory) {
        StorageEngine engine = createTripsTable(directory, 2);
        engine.copyFile("trips", TRIPS_CSV.toString());

        assertEquals(List.of(),
                engine.select("trips", "distance", Comparison.GREATER_THAN, 500L));
    }

    @Test
    void selectRejectsUnknownNamesAndAnInexactConstantType(@TempDir Path directory) {
        StorageEngine engine = createTripsTable(directory, 2);

        assertThrows(IllegalArgumentException.class,
                () -> engine.select("missing", "distance", Comparison.EQUALS, 12L));
        assertThrows(IllegalArgumentException.class,
                () -> engine.select("trips", "missing", Comparison.EQUALS, 12L));
        assertThrows(IllegalArgumentException.class,
                () -> engine.select("trips", "distance", Comparison.EQUALS, 12));
    }

    @Test
    void selectiveScanPrunesSortedPartitions(@TempDir Path directory) throws Exception {
        Path sortedCsv = directory.resolve("trips-sorted.csv");
        Files.writeString(sortedCsv, String.join("\n",
                "Copenhagen,12,23.5",
                "Roskilde,31,45.0",
                "Copenhagen,88,99.99",
                "Odense,95,120.75",
                "Copenhagen,140,210.0",
                "Aarhus,187,301.0",
                "Aalborg,210,340.5",
                "Esbjerg,299,450.25") + "\n");
        StorageEngine engine = createTripsTable(directory, 2);
        engine.copyFile("trips", sortedCsv.toString());

        List<Object[]> rows = engine.select(
                "trips", "distance", Comparison.GREATER_THAN, 200L);

        assertEquals(2, rows.size());
        assertArrayEquals(new Object[] {"Aalborg", 210L, 340.5}, rows.get(0));
        assertArrayEquals(new Object[] {"Esbjerg", 299L, 450.25}, rows.get(1));
        assertEquals(new ScanStats(4, 1, 3), engine.lastScanStats());
    }

    @Test
    void copiedDataSurvivesEngineRestart(@TempDir Path directory) {
        StorageEngine first = createTripsTable(directory, 2);
        first.copyFile("trips", TRIPS_CSV.toString());

        StorageEngine restarted = new StorageEngine(directory, 2);
        List<Object[]> rows = restarted.select(
                "trips", "distance", Comparison.GREATER_THAN, -1L);

        assertRows(rows, 0, 1, 2, 3, 4, 5, 6, 7);
    }

    @Test
    void failedApiCallsStillWriteSevenFieldCsvLogLines(@TempDir Path directory)
            throws Exception {
        String suffix = UUID.randomUUID().toString();
        StorageEngine engine = new StorageEngine(directory);

        assertThrows(IllegalArgumentException.class,
                () -> engine.createTable("create-" + suffix, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> engine.copyFile("copy-" + suffix, TRIPS_CSV.toString()));
        assertThrows(IllegalArgumentException.class,
                () -> engine.select("select-" + suffix,
                        "distance", Comparison.EQUALS, 12L));

        List<String> lines = Files.readAllLines(Path.of("logs/engine.log"));
        assertCsvErrorLog(lines, "create-" + suffix, "CREATE");
        assertCsvErrorLog(lines, "copy-" + suffix, "COPY");
        assertCsvErrorLog(lines, "select-" + suffix, "SELECT");
    }

    private static void assertCsvErrorLog(
            List<String> lines, String tableName, String operation) {
        assertTrue(lines.stream()
                .filter(line -> line.contains(
                        "table=" + tableName + " operation=" + operation + " outcome=ERROR"))
                .anyMatch(line -> line.split(",", -1).length == 7));
    }

    private static void assertRows(List<Object[]> actual, int... expectedRowIndexes) {
        assertEquals(expectedRowIndexes.length, actual.size());
        for (int index = 0; index < expectedRowIndexes.length; index++) {
            assertArrayEquals(GOLDEN_ROWS.get(expectedRowIndexes[index]), actual.get(index));
        }
    }

    private static StorageEngine createTripsTable(Path directory, int partitionSize) {
        StorageEngine engine = new StorageEngine(directory, partitionSize);
        engine.createTable("trips", TRIP_COLUMNS);
        return engine;
    }
}
