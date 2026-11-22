package dk.itu.boomdb;

import static dk.itu.boomdb.Exercise10TestApi.drain;
import static dk.itu.boomdb.Exercise10TestApi.parse;
import static dk.itu.boomdb.Exercise10TestApi.select;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Exercise11PlannerTest {
    @Test
    void optimizedPlanRestoresFromColumnsAfterReordering(@TempDir Path dir) throws Exception {
        StorageEngine storage = Exercise10TestData.loaded(dir);
        SelectStatement statement = parse(Exercise10TestData.QUERIES.get(2));
        new Binder(storage).bind(statement);
        Operator plan = new Planner(storage).plan(statement);
        assertEquals("ColumnOrderOperator", plan.getClass().getSimpleName());
        HashJoinOperator outer = (HashJoinOperator) ((ColumnOrderOperator) plan).child();
        assertEquals(storage.table("products").partitions(), ((ScanOperator) outer.build()).partitions());
        HashJoinOperator middle = (HashJoinOperator) outer.probe();
        assertEquals(storage.table("orders").partitions(), ((ScanOperator) middle.build()).partitions());
        HashJoinOperator inner = (HashJoinOperator) middle.probe();
        assertEquals(storage.table("cities").partitions(), ((ScanOperator) inner.build()).partitions());
        assertEquals(Exercise10TestData.golden(3), Exercise10TestData.sortedRows(drain(plan)));
    }

    @Test
    void duplicateUnreferencedNamesSurviveMultipleSwaps(@TempDir Path dir) throws Exception {
        StorageEngine storage = new StorageEngine(dir.resolve("db"), 10);
        Path a = dir.resolve("a.csv"), b = dir.resolve("b.csv"), c = dir.resolve("c.csv");
        Files.writeString(a, "1,10\n1,11\n1,12\n");
        Files.writeString(b, "1,20\n1,21\n");
        Files.writeString(c, "1,30\n");
        String sql = "CREATE TABLE a (ak LONG, shared LONG); CREATE TABLE b (bk LONG, shared LONG); "
                + "CREATE TABLE c (ck LONG, value LONG); COPY a FROM '" + a + "'; COPY b FROM '" + b
                + "'; COPY c FROM '" + c + "'; SELECT * FROM a, b, c WHERE bk = ak AND ck = bk;";
        List<String> rows = Exercise10TestData.sortedRows(new Executor(storage).execute(sql));
        assertEquals(List.of("1,10,1,20,1,30", "1,10,1,21,1,30", "1,11,1,20,1,30",
                "1,11,1,21,1,30", "1,12,1,20,1,30", "1,12,1,21,1,30"), rows);
    }

    @Test
    void tiesBuildLogicalLeftAndFilterPruningAffectsBuildChoice(@TempDir Path dir) {
        StorageEngine storage = Exercise10TestData.loaded(dir);
        SelectStatement tied = select(List.of("products", "cities"), List.of(), "p_name", "ct_city");
        HashJoinOperator tie = (HashJoinOperator) new Planner(storage).plan(tied);
        assertEquals(storage.table("products").partitions(), ((ScanOperator) tie.build()).partitions());
        SelectStatement filtered = select(List.of("orders", "customers"),
                List.of(new Predicate("o_orderkey", Comparison.EQUALS, 100L)), "o_custname", "c_name");
        Operator planned = new Planner(storage).plan(filtered);
        HashJoinOperator join = (HashJoinOperator) planned;
        assertInstanceOf(FilterOperator.class, join.build());
        assertEquals(new ScanStats(7, 4, 3), storage.lastScanStats());
    }

    @Test
    void optimizedAcceptsConnectedGraphWithDisconnectedFromPrefix(@TempDir Path dir) {
        StorageEngine storage = Exercise10TestData.loaded(dir);
        SelectStatement statement = select(List.of("products", "customers", "orders"), List.of(),
                "p_name", "o_prodname", "c_name", "o_custname");
        assertThrows(IllegalArgumentException.class, () -> new Planner(storage, JoinMode.SYNTAX).plan(statement));
        assertEquals(List.of(
                "Bike,2999.0,Anna,Copenhagen,100,Anna,Bike,1",
                "Bike,2999.0,Carol,Copenhagen,102,Carol,Bike,1",
                "Helmet,299.0,Anna,Copenhagen,103,Anna,Helmet,1",
                "Helmet,299.0,Bob,Aarhus,101,Bob,Helmet,2"),
                Exercise10TestData.sortedRows(drain(new Planner(storage).plan(statement))));
    }
}
