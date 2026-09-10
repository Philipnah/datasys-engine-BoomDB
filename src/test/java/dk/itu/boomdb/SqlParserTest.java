package dk.itu.boomdb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class SqlParserTest {
    private final SqlParser parser = new SqlParser();

    @Test
    void parsesEveryStatementShape() {
        List<Statement> statements = parser.parse("""
                CREATE TABLE trips (city STRING, distance LONG, price DOUBLE);
                COPY trips FROM 'trips.csv';
                SELECT * FROM trips WHERE distance > 100;
                SELECT * FROM trips;
                """);

        assertEquals(List.of(
                new CreateTableStatement("trips", List.of(
                        new ColumnSpec("city", ColumnType.STRING),
                        new ColumnSpec("distance", ColumnType.LONG),
                        new ColumnSpec("price", ColumnType.DOUBLE))),
                new CopyStatement("trips", "trips.csv"),
                new SelectStatement("trips", Optional.of(
                        new Predicate("distance", Comparison.GREATER_THAN, 100L))),
                new SelectStatement("trips", Optional.empty())), statements);
    }

    @ParameterizedTest
    @MethodSource("literalCases")
    void parsesLiteralsWithTheirExactJavaType(String literal, Object expected) {
        SelectStatement statement = (SelectStatement) parser.parse(
                "SELECT * FROM trips WHERE value = " + literal + ";").getFirst();
        Object actual = statement.where().orElseThrow().constant();

        assertEquals(expected, actual);
        assertInstanceOf(expected.getClass(), actual);
    }

    private static Stream<Arguments> literalCases() {
        return Stream.of(
                Arguments.of("12", 12L),
                Arguments.of("12.0", 12.0),
                Arguments.of("'12'", "12"),
                Arguments.of("-1", -1L),
                Arguments.of("-1.5", -1.5));
    }

    @Test
    void acceptsLowercaseKeywordsAndPreservesIdentifierCase() {
        assertEquals(List.of(new SelectStatement("Trip_Data9", Optional.empty())),
                parser.parse("select * from Trip_Data9;"));
    }

    @Test
    void skipsCommentsAndWhitespace() {
        assertEquals(List.of(
                new CopyStatement("trips", "trips.csv"),
                new SelectStatement("trips", Optional.empty())),
                parser.parse("""
                        -- load later
                          COPY trips FROM 'trips.csv' ; -- source

                        SELECT * FROM trips ;
                        """));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("malformedSql")
    void reportsTheFirstSyntaxErrorPosition(
            String description, String sql, int expectedLine, int expectedColumn) {
        SqlParseException error = assertThrows(
                SqlParseException.class, () -> parser.parse(sql));

        assertEquals(expectedLine, error.line());
        assertEquals(expectedColumn, error.column());
    }

    private static Stream<Arguments> malformedSql() {
        return Stream.of(
                Arguments.of("missing semicolon", "SELECT * FROM trips", 1, 19),
                Arguments.of("unbalanced parentheses",
                        "CREATE TABLE trips (city STRING;", 1, 31),
                Arguments.of("unknown column type",
                        "CREATE TABLE trips (city TEXT);", 1, 25),
                Arguments.of("unterminated string", "COPY trips FROM 'trips.csv;", 1, 16),
                Arguments.of("missing FROM", "\nSELECT * trips;", 2, 9));
    }
}
