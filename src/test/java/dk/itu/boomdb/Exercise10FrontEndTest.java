package dk.itu.boomdb;

import static dk.itu.boomdb.Exercise10TestApi.parse;
import static dk.itu.boomdb.Exercise10TestApi.read;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class Exercise10FrontEndTest {
    @Test
    void parsesTableListsWithoutPredicates() {
        SelectStatement statement = parse("SELECT * FROM orders, customers, products, cities;");

        assertEquals(Exercise10TestData.TABLES, read(statement, "tableNames"));
        assertEquals(List.of(), read(statement, "filters"));
        assertEquals(List.of(), read(statement, "joins"));
    }

    @Test
    void separatesInterleavedFiltersAndJoinsWithoutLosingTheirOrderOrLiteralTypes() {
        SelectStatement statement = parse("""
                SELECT * FROM orders, customers, products
                WHERE o_quantity > 1 AND o_custname = c_name AND p_price < 500.0
                AND o_prodname = p_name AND c_city = 'Copenhagen';
                """);

        assertEquals(List.of("orders", "customers", "products"), read(statement, "tableNames"));
        assertEquals(List.of(new Predicate("o_quantity", Comparison.GREATER_THAN, 1L),
                new Predicate("p_price", Comparison.LESS_THAN, 500.0),
                new Predicate("c_city", Comparison.EQUALS, "Copenhagen")), read(statement, "filters"));
        List<?> joins = assertInstanceOf(List.class, read(statement, "joins"));
        assertEquals(2, joins.size());
        assertEquals("JoinPredicate", joins.getFirst().getClass().getSimpleName());
        assertEquals("o_custname", read(joins.get(0), "leftColumn"));
        assertEquals("c_name", read(joins.get(0), "rightColumn"));
        assertEquals("o_prodname", read(joins.get(1), "leftColumn"));
        assertEquals("p_name", read(joins.get(1), "rightColumn"));
    }

    @Test
    void parsesLowercaseAndAndKeepsAndInsideStringLiterals() {
        SelectStatement statement = parse("""
                select * from orders where o_quantity > 0 and o_custname = 'Anna AND Bob';
                """);

        assertEquals(List.of("orders"), read(statement, "tableNames"));
        assertEquals(List.of(new Predicate("o_quantity", Comparison.GREATER_THAN, 0L),
                new Predicate("o_custname", Comparison.EQUALS, "Anna AND Bob")), read(statement, "filters"));
        assertEquals(List.of(), read(statement, "joins"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "SELECT * FROM orders, customers;",
            "SELECT * FROM orders, customers WHERE o_custname = c_name;",
            "SELECT * FROM orders WHERE o_quantity > 0 AND o_orderkey < 103;",
            "SELECT * FROM orders, customers, products, cities WHERE o_custname = c_name "
                    + "AND o_quantity > 0 AND o_prodname = p_name AND p_price < 500.0 "
                    + "AND c_city = ct_city AND ct_country = 'Denmark';"})
    void printerRoundTripsTheNewStatementShapes(String sql) {
        SelectStatement statement = parse(sql);
        String printed = new SqlPrinter().print(statement);

        assertEquals(List.of(statement), assertDoesNotThrow(() -> new SqlParser().parse(printed)));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "DELETE FROM orders WHERE o_orderkey = 100 AND o_quantity = 1;",
            "DELETE FROM orders WHERE o_custname = o_prodname;",
            "DELETE FROM orders, customers;",
            "SELECT * FROM orders WHERE o_orderkey = 100 OR o_quantity = 1;",
            "SELECT * FROM orders WHERE (o_orderkey = 100);",
            "SELECT * FROM orders, customers WHERE o_custname < c_name;"})
    void keepsDeleteRestrictedAndRejectsUnsupportedConditionSyntax(String sql) {
        assertThrows(SqlParseException.class, () -> new SqlParser().parse(sql));
    }
}
