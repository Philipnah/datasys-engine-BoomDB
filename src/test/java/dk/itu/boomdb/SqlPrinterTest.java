package dk.itu.boomdb;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class SqlPrinterTest {
    private final SqlParser parser = new SqlParser();
    private final SqlPrinter printer = new SqlPrinter();

    @ParameterizedTest
    @MethodSource("statements")
    void printedSqlParsesBackToTheSameStatement(Statement statement) {
        assertEquals(List.of(statement), parser.parse(printer.print(statement)));
    }

    private static Stream<Arguments> statements() {
        return Stream.of(
                Arguments.of(new CreateTableStatement("trips", List.of(
                        new ColumnSpec("city", ColumnType.STRING),
                        new ColumnSpec("distance", ColumnType.LONG),
                        new ColumnSpec("price", ColumnType.DOUBLE)))),
                Arguments.of(new CopyStatement("trips", "trips.csv")),
                Arguments.of(new SelectStatement("trips", Optional.empty())),
                Arguments.of(new SelectStatement("trips", Optional.of(
                        new Predicate("city", Comparison.EQUALS, "Odense")))),
                Arguments.of(new SelectStatement("trips", Optional.of(
                        new Predicate("distance", Comparison.GREATER_THAN, -1L)))),
                Arguments.of(new SelectStatement("trips", Optional.of(
                        new Predicate("price", Comparison.LESS_THAN, -1.5)))),
                Arguments.of(new SelectStatement("trips", Optional.of(
                        new Predicate("price", Comparison.GREATER_THAN, 1.0e20)))),
                Arguments.of(new SelectStatement("trips", Optional.of(
                        new Predicate("price", Comparison.LESS_THAN, 1.0e-4)))),
                Arguments.of(new SelectStatement("trips", Optional.of(
                        new Predicate("price", Comparison.EQUALS, -0.0)))));
    }

    @Test
    void normalizesAFilteredSelect() {
        Statement statement = new SelectStatement("trips", Optional.of(
                new Predicate("distance", Comparison.GREATER_THAN, 100L)));

        assertEquals("SELECT * FROM trips WHERE distance > 100;", printer.print(statement));
    }
}
