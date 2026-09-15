package dk.itu.boomdb;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Runs a large CSV import and scan workload intended for local profiling. */
@Disabled("Run explicitly when profiling the storage engine")
class StorageEngineLargeIT {
    private static final Path TRIPS_CSV = Path.of("src/test/resources/trips-100mb(in).csv");
    private static final List<ColumnSpec> TRIP_COLUMNS = List.of(
            new ColumnSpec("city", ColumnType.STRING),
            new ColumnSpec("distance", ColumnType.LONG),
            new ColumnSpec("price", ColumnType.DOUBLE));

    @Test
    void importsAndScansLargeCsv(@TempDir Path directory) {
        StorageEngine engine = new StorageEngine(directory);
        engine.createTable("trips", TRIP_COLUMNS);

        engine.copyFile("trips", TRIPS_CSV.toString());

        TableCatalog catalog = new CatalogStore(directory).load("trips").orElseThrow();
        assertTrue(catalog.copied());
        assertTrue(catalog.partitions().size() > 1);

        List<Object[]> allRows = engine.select(
                "trips", "distance", Comparison.GREATER_THAN, Long.MIN_VALUE);
        assertFalse(allRows.isEmpty());
        assertEquals(catalog.partitions().size(), engine.lastScanStats().partitionsTotal());
        assertEquals(catalog.partitions().size(), engine.lastScanStats().partitionsRead());
        assertEquals(0, engine.lastScanStats().partitionsPruned());

        List<Object[]> selectedRows = engine.select(
                "trips", "distance", Comparison.GREATER_THAN, 100L);
        assertFalse(selectedRows.isEmpty());
        assertTrue(selectedRows.stream().allMatch(row -> (long) row[1] > 100L));

        List<Object[]> fewRows = engine.select(
                "trips", "distance", Comparison.EQUALS, 42L);
        assertFalse(fewRows.isEmpty());
        assertTrue(fewRows.stream().allMatch(row -> (long) row[1] == 42L));
    }
}
