package dk.itu.boomdb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlannerTest {
    private static final Path LOG_FILE = Path.of("logs/engine.log");
    private static final List<ColumnSpec> COLUMNS = List.of(
            new ColumnSpec("city", ColumnType.STRING),
            new ColumnSpec("distance", ColumnType.LONG),
            new ColumnSpec("price", ColumnType.DOUBLE));

    @Test
    void whereBuildsFilterOverPrunedScan(@TempDir Path directory) throws Exception {
        StorageEngine storage = copiedSortedTrips(directory, "trips");
        SelectStatement statement = filteredSelect("trips");
        new Binder(storage).bind(statement);

        Operator plan = new Planner(storage).plan(statement);

        FilterOperator filter = assertInstanceOf(FilterOperator.class, plan);
        ScanOperator scan = assertInstanceOf(ScanOperator.class, filter.child());
        assertEquals(List.of(storage.table("trips").partitions().get(3)), scan.partitions());
        assertEquals(new ScanStats(4, 1, 3), storage.lastScanStats());
    }

    @Test
    void selectWithoutWhereBuildsBareScanOverEveryPartition(@TempDir Path directory)
            throws Exception {
        StorageEngine storage = copiedSortedTrips(directory, "trips");
        SelectStatement statement = new SelectStatement("trips", Optional.empty());
        new Binder(storage).bind(statement);

        Operator plan = new Planner(storage).plan(statement);

        ScanOperator scan = assertInstanceOf(ScanOperator.class, plan);
        assertEquals(storage.table("trips").partitions(), scan.partitions());
        assertEquals(new ScanStats(4, 4, 0), storage.lastScanStats());
    }

    @Test
    void planningLogsDecisionsBeforeTheOperatorOpens(@TempDir Path directory) throws Exception {
        String tableName = "planner_" + UUID.randomUUID().toString().replace("-", "");
        StorageEngine storage = copiedSortedTrips(directory, tableName);
        int linesBeforePlanning = Files.readAllLines(LOG_FILE).size();

        new Planner(storage).plan(filteredSelect(tableName));

        List<String> decisions = Files.readAllLines(LOG_FILE).stream()
                .skip(linesBeforePlanning)
                .filter(line -> line.contains("table=" + tableName))
                .filter(line -> line.contains("decision="))
                .toList();
        assertEquals(4, decisions.size());
        assertTrue(decisions.stream().allMatch(line -> line.split(",", 7)[5].equals("Planner")));
        assertEquals(3, decisions.stream().filter(line -> line.contains("decision=PRUNED")).count());
        assertEquals(1, decisions.stream().filter(line -> line.contains("decision=READ")).count());
    }

    private static SelectStatement filteredSelect(String tableName) {
        return new SelectStatement(tableName, Optional.of(
                new Predicate("distance", Comparison.GREATER_THAN, 200L)));
    }

    private static StorageEngine copiedSortedTrips(Path directory, String tableName)
            throws Exception {
        Path csv = directory.resolve(tableName + ".csv");
        Files.writeString(csv, String.join("\n",
                "Copenhagen,12,23.5",
                "Roskilde,31,45.0",
                "Copenhagen,88,99.99",
                "Odense,95,120.75",
                "Copenhagen,140,210.0",
                "Aarhus,187,301.0",
                "Aalborg,210,340.5",
                "Esbjerg,299,450.25") + "\n");
        StorageEngine storage = new StorageEngine(directory, 2);
        storage.createTable(tableName, COLUMNS);
        storage.copyFile(tableName, csv.toString());
        return storage;
    }
}
