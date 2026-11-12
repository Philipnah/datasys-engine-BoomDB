package dk.itu.boomdb;

import static dk.itu.boomdb.Exercise10TestApi.call;
import static dk.itu.boomdb.Exercise10TestApi.create;
import static dk.itu.boomdb.Exercise10TestApi.read;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class Exercise10ColumnResolverTest {
    @ParameterizedTest
    @CsvSource({"o_orderkey, orders, 0, 0, LONG", "o_quantity, orders, 3, 3, LONG",
            "c_name, customers, 0, 4, STRING", "c_city, customers, 1, 5, STRING",
            "p_name, products, 0, 6, STRING", "p_price, products, 1, 7, DOUBLE",
            "ct_city, cities, 0, 8, STRING", "ct_population, cities, 2, 10, LONG"})
    void resolvesOwnershipLocalIndexAndConcatenatedIndex(String column, String table,
            int localIndex, int joinedIndex, ColumnType type) {
        Object resolver = resolver(Exercise10TestData.TABLES.stream()
                .map(name -> new TableCatalog(name, Exercise10TestData.columns(name), 0, List.of()))
                .toList());

        Object resolved = resolve(resolver, column);

        assertEquals(table, read(resolved, "tableName"));
        assertEquals(localIndex, read(resolved, "columnIndex"));
        assertEquals(joinedIndex, read(resolved, "joinedIndex"));
        assertEquals(type, read(resolved, "type"));
    }

    @Test
    void derivesOffsetsFromFromOrderRatherThanCatalogOrder() {
        Object resolver = resolver(List.of(
                new TableCatalog("products", Exercise10TestData.PRODUCTS, 0, List.of()),
                new TableCatalog("orders", Exercise10TestData.ORDERS, 0, List.of()),
                new TableCatalog("customers", Exercise10TestData.CUSTOMERS, 0, List.of())));

        assertEquals(1, read(resolve(resolver, "p_price"), "joinedIndex"));
        assertEquals(2, read(resolve(resolver, "o_orderkey"), "joinedIndex"));
        assertEquals(7, read(resolve(resolver, "c_city"), "joinedIndex"));
    }

    @Test
    void rejectsUnknownColumns() {
        Object resolver = resolver(List.of(
                new TableCatalog("orders", Exercise10TestData.ORDERS, 0, List.of())));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> resolve(resolver, "missing"));
        assertTrue(error.getMessage().contains("missing"));
    }

    @Test
    void rejectsAmbiguousColumnsButStillResolvesUniqueColumnsInThoseSchemas() {
        Object resolver = resolver(List.of(
                new TableCatalog("a", List.of(new ColumnSpec("id", ColumnType.LONG)), 0, List.of()),
                new TableCatalog("b", List.of(new ColumnSpec("id", ColumnType.LONG),
                        new ColumnSpec("b_name", ColumnType.STRING)), 0, List.of())));

        assertEquals("b", read(resolve(resolver, "b_name"), "tableName"));
        assertEquals(2, read(resolve(resolver, "b_name"), "joinedIndex"));
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> resolve(resolver, "id"));
        assertTrue(error.getMessage().toLowerCase().contains("ambiguous"));
        assertTrue(error.getMessage().contains("id"));
    }

    private static Object resolver(List<TableCatalog> tables) {
        return create("ColumnResolver", new Class<?>[] {List.class}, tables);
    }

    private static Object resolve(Object resolver, String column) {
        return call(resolver, "resolve", new Class<?>[] {String.class}, column);
    }
}
