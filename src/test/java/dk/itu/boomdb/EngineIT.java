package dk.itu.boomdb;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EngineIT {
    @Test
    void printsTheFourRequiredSqlStatements() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PrintStream originalOutput = System.out;
        try {
            System.setOut(new PrintStream(output, true, StandardCharsets.UTF_8));

            Engine.main(new String[0]);
        } finally {
            System.setOut(originalOutput);
        }

        assertEquals("""
                CREATE TABLE trips (city STRING, distance LONG, price DOUBLE);
                COPY trips FROM 'trips.csv';
                SELECT * FROM trips WHERE distance > 100;
                SELECT * FROM trips;
                """, output.toString(StandardCharsets.UTF_8).replace("\r\n", "\n"));
    }

    @Test
    void selectsTheThreeGoldenQueryResults(@TempDir Path directory) {
        // Keep the golden example independent of the demo's console output.
        StorageEngine storage = new StorageEngine(directory);
        storage.createTable("trips", List.of(
                new ColumnSpec("city", ColumnType.STRING),
                new ColumnSpec("distance", ColumnType.LONG),
                new ColumnSpec("price", ColumnType.DOUBLE)));
        storage.copyFile("trips", "src/test/resources/trips.csv");

        List<Object[]> longTrips = storage.select(
                "trips", "distance", Comparison.GREATER_THAN, 100L);
        assertEquals(4, longTrips.size());
        assertArrayEquals(new Object[] {"Aarhus", 187L, 301.0}, longTrips.get(0));
        assertArrayEquals(new Object[] {"Copenhagen", 140L, 210.0}, longTrips.get(1));
        assertArrayEquals(new Object[] {"Aalborg", 210L, 340.5}, longTrips.get(2));
        assertArrayEquals(new Object[] {"Esbjerg", 299L, 450.25}, longTrips.get(3));

        List<Object[]> copenhagenTrips = storage.select(
                "trips", "city", Comparison.EQUALS, "Copenhagen");
        assertEquals(3, copenhagenTrips.size());
        assertArrayEquals(new Object[] {"Copenhagen", 12L, 23.5}, copenhagenTrips.get(0));
        assertArrayEquals(new Object[] {"Copenhagen", 140L, 210.0}, copenhagenTrips.get(1));
        assertArrayEquals(new Object[] {"Copenhagen", 88L, 99.99}, copenhagenTrips.get(2));

        List<Object[]> cheapTrips = storage.select(
                "trips", "price", Comparison.LESS_THAN, 50.0);
        assertEquals(2, cheapTrips.size());
        assertArrayEquals(new Object[] {"Copenhagen", 12L, 23.5}, cheapTrips.get(0));
        assertArrayEquals(new Object[] {"Roskilde", 31L, 45.0}, cheapTrips.get(1));
    }
}
