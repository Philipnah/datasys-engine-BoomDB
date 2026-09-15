package dk.itu.boomdb;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BinderIT {
    private static final List<ColumnSpec> TRIP_COLUMNS = List.of(
            new ColumnSpec("city", ColumnType.STRING),
            new ColumnSpec("distance", ColumnType.LONG),
            new ColumnSpec("price", ColumnType.DOUBLE));

    @TempDir
    Path directory;

    private StorageEngine engine;
    private Binder binder;

    @BeforeEach
    void createTripsTable() {
        engine = new StorageEngine(directory);
        engine.createTable("trips", TRIP_COLUMNS);
        binder = new Binder(engine);
    }

    @Test
    void schemaReturnsColumnsInCatalogOrder() {
        assertEquals(TRIP_COLUMNS, engine.schema("trips"));
    }

    @Test
    void validStatementsBindWithoutExecution() {
        assertDoesNotThrow(() -> binder.bind(new CreateTableStatement("trips", TRIP_COLUMNS)));
        assertDoesNotThrow(() -> binder.bind(new CopyStatement("trips", "missing.csv")));
        assertDoesNotThrow(() -> binder.bind(
                new SelectStatement("trips", Optional.empty())));
        assertDoesNotThrow(() -> binder.bind(new SelectStatement("trips", Optional.of(
                new Predicate("city", Comparison.EQUALS, "Odense")))));
        assertDoesNotThrow(() -> binder.bind(new SelectStatement("trips", Optional.of(
                new Predicate("distance", Comparison.GREATER_THAN, 100L)))));
        assertDoesNotThrow(() -> binder.bind(new SelectStatement("trips", Optional.of(
                new Predicate("price", Comparison.LESS_THAN, 50.0)))));
    }

    @Test
    void unknownTablesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> engine.schema("missing"));
        assertThrows(IllegalArgumentException.class,
                () -> binder.bind(new CopyStatement("missing", "trips.csv")));
        assertThrows(IllegalArgumentException.class,
                () -> binder.bind(new SelectStatement("missing", Optional.empty())));
    }

    @Test
    void unknownPredicateColumnIsRejected() {
        Statement statement = new SelectStatement("trips", Optional.of(
                new Predicate("missing", Comparison.EQUALS, 1L)));

        assertThrows(IllegalArgumentException.class, () -> binder.bind(statement));
    }

    @Test
    void predicateConstantMustExactlyMatchTheColumnType() {
        Statement statement = new SelectStatement("trips", Optional.of(
                new Predicate("distance", Comparison.EQUALS, "x")));

        assertThrows(IllegalArgumentException.class, () -> binder.bind(statement));
    }

    @Test
    void createRequiresAtLeastOneColumn() {
        assertThrows(IllegalArgumentException.class,
                () -> binder.bind(new CreateTableStatement("empty", List.of())));
    }

    @Test
    void createRejectsDuplicateColumnNames() {
        Statement statement = new CreateTableStatement("duplicate", List.of(
                new ColumnSpec("city", ColumnType.STRING),
                new ColumnSpec("city", ColumnType.LONG)));

        assertThrows(IllegalArgumentException.class, () -> binder.bind(statement));
    }
}
