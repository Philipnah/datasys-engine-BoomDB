package dk.itu.boomdb;

import static dk.itu.boomdb.Exercise10TestApi.select;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class Exercise10BinderTest {
    @TempDir Path directory;
    private StorageEngine storage;
    private Binder binder;

    @BeforeEach
    void createSchemas() {
        storage = new StorageEngine(directory);
        Exercise10TestData.createTables(storage);
        binder = new Binder(storage);
    }

    @Test
    void bindsFourTablesWithJoinsAndFiltersAcrossTheirSchemas() {
        SelectStatement statement = select(Exercise10TestData.TABLES,
                List.of(new Predicate("o_quantity", Comparison.GREATER_THAN, 0L),
                        new Predicate("p_price", Comparison.LESS_THAN, 500.0),
                        new Predicate("c_city", Comparison.EQUALS, "Copenhagen")),
                "o_custname", "c_name", "p_name", "o_prodname", "c_city", "ct_city");

        assertDoesNotThrow(() -> binder.bind(statement));
    }

    @Test
    void rejectsAnUnknownTableBeyondTheFirstFromEntry() {
        SelectStatement statement = select(List.of("orders", "missing"), List.of());
        assertRejected(statement, "missing");
    }

    @Test
    void rejectsAnUnknownFilterColumn() {
        SelectStatement statement = select(List.of("orders", "customers"),
                List.of(new Predicate("missing", Comparison.EQUALS, 1L)), "o_custname", "c_name");
        assertRejected(statement, "missing");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rejectsAnUnknownColumnOnEitherSideOfAJoin(boolean unknownOnLeft) {
        SelectStatement statement = select(List.of("orders", "customers"), List.of(),
                unknownOnLeft ? "missing" : "o_custname", unknownOnLeft ? "c_name" : "missing");
        assertRejected(statement, "missing");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rejectsAmbiguousReferencesInFiltersAndJoins(boolean join) {
        storage.createTable("left_table", List.of(new ColumnSpec("id", ColumnType.LONG)));
        storage.createTable("right_table", List.of(new ColumnSpec("id", ColumnType.LONG),
                new ColumnSpec("right_id", ColumnType.LONG)));
        SelectStatement statement = join
                ? select(List.of("left_table", "right_table"), List.of(), "id", "right_id")
                : select(List.of("left_table", "right_table"),
                        List.of(new Predicate("id", Comparison.EQUALS, 1L)));

        assertRejected(statement, "ambiguous", "id");
    }

    @Test
    void rejectsLongToDoubleJoinInsteadOfSilentlyComparingUnequalJavaKeys() {
        SelectStatement statement = select(List.of("orders", "products"), List.of(),
                "o_orderkey", "p_price");
        assertRejected(statement, "LONG", "DOUBLE");
    }

    @Test
    void rejectsStringToLongJoin() {
        SelectStatement statement = select(List.of("orders", "cities"), List.of(),
                "o_custname", "ct_population");
        assertRejected(statement, "STRING", "LONG");
    }

    @Test
    void rejectsJoinColumnsOwnedByTheSameTable() {
        SelectStatement statement = select(List.of("orders", "customers"), List.of(),
                "o_custname", "o_prodname");
        assertRejected(statement, "table");
    }

    @Test
    void resolvesNamesOnlyAgainstListedTables() {
        // customers exists in the catalog but is deliberately absent from FROM.
        SelectStatement statement = select(List.of("orders", "products"), List.of(),
                "o_custname", "c_name");
        assertRejected(statement, "c_name");
    }

    @Test
    void checksFilterTypesOnTablesBeyondTheFirstFromEntry() {
        SelectStatement statement = select(List.of("orders", "products"),
                List.of(new Predicate("p_price", Comparison.LESS_THAN, 500L)),
                "o_prodname", "p_name");
        assertRejected(statement, "p_price", "DOUBLE");
    }

    private void assertRejected(SelectStatement statement, String... details) {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> binder.bind(statement));
        for (String detail : details) {
            assertTrue(error.getMessage().toLowerCase().contains(detail.toLowerCase()), error::getMessage);
        }
    }
}
