package dk.itu.boomdb;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScanOperatorTest {
    private static final Path TRIPS_CSV = Path.of("src/test/resources/trips.csv");
    private static final List<ColumnSpec> COLUMNS = List.of(
            new ColumnSpec("city", ColumnType.STRING),
            new ColumnSpec("distance", ColumnType.LONG),
            new ColumnSpec("price", ColumnType.DOUBLE));

    @Test
    void returnsExactlyTheRowsInTheHandedInPartitions(@TempDir Path directory) {
        StorageEngine storage = copiedTrips(directory);
        TableCatalog table = storage.table("trips");
        ScanOperator scan = new ScanOperator(storage, table,
                List.of(table.partitions().get(1), table.partitions().get(3)));

        List<Object[]> rows = drain(scan);

        assertEquals(4, rows.size());
        assertArrayEquals(new Object[] {"Odense", 95L, 120.75}, rows.get(0));
        assertArrayEquals(new Object[] {"Copenhagen", 140L, 210.0}, rows.get(1));
        assertArrayEquals(new Object[] {"Copenhagen", 88L, 99.99}, rows.get(2));
        assertArrayEquals(new Object[] {"Esbjerg", 299L, 450.25}, rows.get(3));
    }

    @Test
    void emptyPartitionListReadsNothing(@TempDir Path directory) {
        StorageEngine storage = copiedTrips(directory);
        TableCatalog table = storage.table("trips");
        ScanOperator scan = new ScanOperator(storage, table, List.of());

        scan.open();
        Object[] row = scan.next();
        scan.close();

        assertNull(row);
    }

    private static StorageEngine copiedTrips(Path directory) {
        StorageEngine storage = new StorageEngine(directory, 2);
        storage.createTable("trips", COLUMNS);
        storage.copyFile("trips", TRIPS_CSV.toString());
        return storage;
    }

    private static List<Object[]> drain(Operator operator) {
        List<Object[]> rows = new ArrayList<>();
        operator.open();
        try {
            for (Object[] row; (row = operator.next()) != null; ) {
                rows.add(row);
            }
        } finally {
            operator.close();
        }
        return rows;
    }
}
