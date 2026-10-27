package dk.itu.boomdb;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DeleteFrontEndTest {
    @Test
    void parsesTheExpectedDeleteAstWithAndWithoutWhere() {
        assertEquals(List.of(new DeleteStatement("trips", Optional.empty()),
                new DeleteStatement("trips", Optional.of(
                        new Predicate("distance", Comparison.GREATER_THAN, 100L)))),
                new SqlParser().parse("DELETE FROM trips; DELETE FROM trips WHERE distance > 100;"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"DELETE FROM trips;", "DELETE FROM trips WHERE distance > 100;",
            "DELETE FROM trips WHERE city = 'Odense';", "DELETE FROM trips WHERE price < 1.5;"})
    void deleteRoundTripsThroughParserAndPrinter(String sql) {
        SqlParser parser = new SqlParser();
        Statement statement = parser.parse(sql).getFirst();

        assertEquals(sql, new SqlPrinter().print(statement));
        assertEquals(List.of(statement), parser.parse(new SqlPrinter().print(statement)));
    }

    @Test
    void bindsDeleteWithAndWithoutPredicate(@TempDir Path directory) {
        Binder binder = binder(directory);
        for (Statement statement : new SqlParser().parse(
                "delete from trips; delete from trips where distance = 12;")) {
            assertDoesNotThrow(() -> binder.bind(statement));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"DELETE FROM missing;", "DELETE FROM trips WHERE missing = 1;",
            "DELETE FROM trips WHERE distance = 'x';", "DELETE FROM trips WHERE price = 1;"})
    void rejectsUnknownNamesAndMistypedConstants(String sql, @TempDir Path directory) {
        Binder binder = binder(directory);
        Statement statement = new SqlParser().parse(sql).getFirst();
        assertThrows(IllegalArgumentException.class, () -> binder.bind(statement));
    }

    private static Binder binder(Path directory) {
        StorageEngine storage = new StorageEngine(directory);
        storage.createTable("trips", List.of(new ColumnSpec("city", ColumnType.STRING),
                new ColumnSpec("distance", ColumnType.LONG),
                new ColumnSpec("price", ColumnType.DOUBLE)));
        return new Binder(storage);
    }
}
