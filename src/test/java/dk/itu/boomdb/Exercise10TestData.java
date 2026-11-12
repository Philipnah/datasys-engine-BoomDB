package dk.itu.boomdb;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/** The exercise's hand-checkable data and independently recorded DuckDB answers. */
final class Exercise10TestData {
    static final Path RESOURCES = Path.of("src/test/resources/exercise10");
    static final List<String> TABLES = List.of("orders", "customers", "products", "cities");
    static final List<ColumnSpec> ORDERS = List.of(
            new ColumnSpec("o_orderkey", ColumnType.LONG),
            new ColumnSpec("o_custname", ColumnType.STRING),
            new ColumnSpec("o_prodname", ColumnType.STRING),
            new ColumnSpec("o_quantity", ColumnType.LONG));
    static final List<ColumnSpec> CUSTOMERS = List.of(
            new ColumnSpec("c_name", ColumnType.STRING), new ColumnSpec("c_city", ColumnType.STRING));
    static final List<ColumnSpec> PRODUCTS = List.of(
            new ColumnSpec("p_name", ColumnType.STRING), new ColumnSpec("p_price", ColumnType.DOUBLE));
    static final List<ColumnSpec> CITIES = List.of(
            new ColumnSpec("ct_city", ColumnType.STRING),
            new ColumnSpec("ct_country", ColumnType.STRING),
            new ColumnSpec("ct_population", ColumnType.LONG));
    static final List<String> QUERIES = List.of(
            "SELECT * FROM orders, customers WHERE o_custname = c_name;",
            "SELECT * FROM orders, customers, products "
                    + "WHERE o_custname = c_name AND o_prodname = p_name;",
            "SELECT * FROM orders, customers, products, cities "
                    + "WHERE o_custname = c_name AND o_prodname = p_name AND c_city = ct_city;",
            "SELECT * FROM orders, customers, products "
                    + "WHERE o_custname = c_name AND o_prodname = p_name AND c_city = 'Copenhagen';");

    private Exercise10TestData() { }

    static List<ColumnSpec> columns(String table) {
        return switch (table) {
            case "orders" -> ORDERS;
            case "customers" -> CUSTOMERS;
            case "products" -> PRODUCTS;
            case "cities" -> CITIES;
            default -> throw new IllegalArgumentException("unknown test table: " + table);
        };
    }

    static void createTables(StorageEngine storage) {
        for (String table : TABLES) {
            storage.createTable(table, columns(table));
        }
    }

    static StorageEngine loaded(Path directory) {
        StorageEngine storage = new StorageEngine(directory.resolve("db"), 1);
        createTables(storage);
        for (String table : TABLES) {
            storage.copyFile(table, RESOURCES.resolve(table + ".csv").toString());
        }
        return storage;
    }

    static String setupSql() {
        return """
                CREATE TABLE orders (o_orderkey LONG, o_custname STRING, o_prodname STRING, o_quantity LONG);
                CREATE TABLE products (p_name STRING, p_price DOUBLE);
                CREATE TABLE customers (c_name STRING, c_city STRING);
                CREATE TABLE cities (ct_city STRING, ct_country STRING, ct_population LONG);
                """ + TABLES.stream().map(table -> "COPY " + table + " FROM '"
                        + RESOURCES.resolve(table + ".csv").toAbsolutePath() + "';\n")
                        .collect(Collectors.joining());
    }

    static List<String> golden(int query) throws IOException {
        return Files.readAllLines(RESOURCES.resolve("query-" + query + ".expected.csv"))
                .stream().sorted().toList();
    }

    static List<String> sortedRows(List<Object[]> rows) {
        return rows.stream().map(row -> Arrays.stream(row).map(String::valueOf)
                .collect(Collectors.joining(","))).sorted().toList();
    }
}
