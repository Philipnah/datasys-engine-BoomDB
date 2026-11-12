package dk.itu.boomdb;

import static dk.itu.boomdb.Exercise10TestApi.drain;
import static dk.itu.boomdb.Exercise10TestApi.read;
import static dk.itu.boomdb.Exercise10TestApi.select;
import static dk.itu.boomdb.Exercise10TestData.sortedRows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Exercise10PlannerTest {
    @Test
    void fourTablesBuildExactlyThreeLeftDeepHashJoinsInFromOrder(@TempDir Path directory) {
        StorageEngine storage = Exercise10TestData.loaded(directory);
        Operator outer = plan(storage, fourTableSelect(List.of()));

        pipeline(probe(outer), storage, "cities", List.of(0, 1), false);
        Operator middle = build(outer);
        pipeline(probe(middle), storage, "products", List.of(0, 1), false);
        Operator inner = build(middle);
        pipeline(probe(inner), storage, "customers", List.of(0, 1, 2), false);
        pipeline(build(inner), storage, "orders", List.of(0, 1, 2, 3), false);
        assertEquals(4, drain(outer).size());
    }

    @Test
    void pushesFiltersBelowEveryJoinAndPrunesEachTablesPartitions(@TempDir Path directory) {
        StorageEngine storage = Exercise10TestData.loaded(directory);
        Operator outer = plan(storage, fourTableSelect(List.of(
                new Predicate("o_quantity", Comparison.EQUALS, 1L),
                new Predicate("c_city", Comparison.EQUALS, "Copenhagen"),
                new Predicate("p_price", Comparison.GREATER_THAN, 1000.0),
                new Predicate("ct_population", Comparison.GREATER_THAN, 1_000_000L))));

        pipeline(probe(outer), storage, "cities", List.of(0), true);
        Operator middle = build(outer);
        pipeline(probe(middle), storage, "products", List.of(0), true);
        Operator inner = build(middle);
        pipeline(probe(inner), storage, "customers", List.of(0, 2), true);
        pipeline(build(inner), storage, "orders", List.of(0, 2, 3), true);
        assertEquals(List.of(
                "100,Anna,Bike,1,Anna,Copenhagen,Bike,2999.0,Copenhagen,Denmark,1400000",
                "102,Carol,Bike,1,Carol,Copenhagen,Bike,2999.0,Copenhagen,Denmark,1400000"),
                sortedRows(drain(outer)));
    }

    @Test
    void conjunctiveFiltersStillProduceAccuratePerTableScanStats(@TempDir Path directory) {
        StorageEngine storage = Exercise10TestData.loaded(directory);
        SelectStatement statement = select(List.of("orders"), List.of(
                new Predicate("o_orderkey", Comparison.GREATER_THAN, 100L),
                new Predicate("o_quantity", Comparison.EQUALS, 1L)));

        Operator operator = plan(storage, statement);

        pipeline(operator, storage, "orders", List.of(2, 3), true);
        assertEquals(new ScanStats(4, 2, 2), storage.lastScanStats());
        assertEquals(List.of("102,Carol,Bike,1", "103,Anna,Helmet,1"), sortedRows(drain(operator)));
    }

    @Test
    void usesConcatenatedBuildOffsetsAndTableLocalProbeOffsetsWithReversedOperands(
            @TempDir Path directory) throws Exception {
        StorageEngine storage = Exercise10TestData.loaded(directory);
        SelectStatement statement = select(Exercise10TestData.TABLES, List.of(),
                "c_name", "o_custname", "p_name", "o_prodname", "ct_city", "c_city");

        assertEquals(Exercise10TestData.golden(3), sortedRows(drain(plan(storage, statement))));
    }

    @Test
    void fromReorderingChangesOutputColumnOrderAndKeepsCorrectJoinKeys(@TempDir Path directory) {
        StorageEngine storage = Exercise10TestData.loaded(directory);
        SelectStatement statement = select(List.of("products", "orders", "customers", "cities"),
                List.of(), "o_prodname", "p_name", "o_custname", "c_name", "c_city", "ct_city");
        Operator outer = plan(storage, statement);

        pipeline(build(build(build(outer))), storage, "products", List.of(0, 1), false);
        pipeline(probe(build(build(outer))), storage, "orders", List.of(0, 1, 2, 3), false);
        assertEquals(List.of(
                "Bike,2999.0,100,Anna,Bike,1,Anna,Copenhagen,Copenhagen,Denmark,1400000",
                "Bike,2999.0,102,Carol,Bike,1,Carol,Copenhagen,Copenhagen,Denmark,1400000",
                "Helmet,299.0,101,Bob,Helmet,2,Bob,Aarhus,Aarhus,Denmark,290000",
                "Helmet,299.0,103,Anna,Helmet,1,Anna,Copenhagen,Copenhagen,Denmark,1400000"),
                sortedRows(drain(outer)));
    }

    @Test
    void rejectsATableConnectedOnlyToALaterFromEntryAndSuggestsReordering(@TempDir Path directory) {
        StorageEngine storage = Exercise10TestData.loaded(directory);
        SelectStatement statement = select(List.of("orders", "cities", "customers"), List.of(),
                "o_custname", "c_name", "c_city", "ct_city");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new Planner(storage).plan(statement));

        assertTrue(error.getMessage().contains("cities"), error::getMessage);
        assertTrue(error.getMessage().toLowerCase().contains("reorder"), error::getMessage);
        assertTrue(error.getMessage().toUpperCase().contains("FROM"), error::getMessage);
    }

    @Test
    void rejectsATableListWithoutJoinPredicates(@TempDir Path directory) {
        StorageEngine storage = Exercise10TestData.loaded(directory);
        SelectStatement statement = select(List.of("orders", "customers"), List.of());

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new Planner(storage).plan(statement));
        assertTrue(error.getMessage().toLowerCase().contains("reorder"), error::getMessage);
    }

    @Test
    void rejectsASecondConnectingPredicateInsteadOfIgnoringIt(@TempDir Path directory) {
        StorageEngine storage = Exercise10TestData.loaded(directory);
        SelectStatement statement = select(List.of("orders", "customers"), List.of(),
                "o_custname", "c_name", "o_prodname", "c_city");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new Planner(storage).plan(statement));
        assertTrue(error.getMessage().toLowerCase().contains("predicate"), error::getMessage);
    }

    private static SelectStatement fourTableSelect(List<Predicate> filters) {
        return select(Exercise10TestData.TABLES, filters,
                "o_custname", "c_name", "o_prodname", "p_name", "c_city", "ct_city");
    }

    private static Operator plan(StorageEngine storage, SelectStatement statement) {
        new Binder(storage).bind(statement);
        return new Planner(storage).plan(statement);
    }

    private static Operator build(Operator join) {
        assertEquals("HashJoinOperator", join.getClass().getSimpleName());
        return assertInstanceOf(Operator.class, read(join, "build"));
    }

    private static Operator probe(Operator join) {
        assertEquals("HashJoinOperator", join.getClass().getSimpleName());
        return assertInstanceOf(Operator.class, read(join, "probe"));
    }

    private static void pipeline(Operator operator, StorageEngine storage, String table,
            List<Integer> retainedPartitions, boolean filtered) {
        if (filtered) {
            assertInstanceOf(FilterOperator.class, operator);
            while (operator instanceof FilterOperator filter) {
                operator = filter.child();
            }
        }
        ScanOperator scan = assertInstanceOf(ScanOperator.class, operator);
        List<PartitionMetadata> allPartitions = storage.table(table).partitions();
        assertEquals(retainedPartitions.stream().map(allPartitions::get).toList(), scan.partitions());
        // Each scan must retain its own counts when later tables are folded into the join tree.
        assertEquals(new ScanStats(allPartitions.size(), retainedPartitions.size(),
                allPartitions.size() - retainedPartitions.size()), read(scan, "scanStats"));
    }
}
