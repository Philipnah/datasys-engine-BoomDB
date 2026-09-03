package dk.itu.boomdb;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/** Runs the Exercise 2 golden-data demonstration. */
public final class Engine {
    private static final Logger LOGGER = LoggerFactory.getLogger(Engine.class);

    /**
     * Copies the golden trip data and prints the three required filtered results.
     *
     * @param args ignored command-line arguments
     */
    public static void main(String[] args) {
        MDC.put("sessionId", UUID.randomUUID().toString());
        MDC.put("statementNumber", "0");
        LOGGER.debug("engine started");
        try {
            Path dataDirectory = Files.createTempDirectory("boomdb-demo-");
            StorageEngine storage = new StorageEngine(dataDirectory);
            storage.createTable("trips", List.of(
                    new ColumnSpec("city", ColumnType.STRING),
                    new ColumnSpec("distance", ColumnType.LONG),
                    new ColumnSpec("price", ColumnType.DOUBLE)));
            storage.copyFile("trips", "src/test/resources/trips.csv");

            printRows("distance GREATER_THAN 100",
                    storage.select("trips", "distance", Comparison.GREATER_THAN, 100L));
            printRows("city EQUALS Copenhagen",
                    storage.select("trips", "city", Comparison.EQUALS, "Copenhagen"));
            printRows("price LESS_THAN 50.0",
                    storage.select("trips", "price", Comparison.LESS_THAN, 50.0));
        } catch (IOException error) {
            throw new UncheckedIOException("cannot create demo data directory", error);
        } finally {
            LOGGER.debug("engine stopped");
        }
    }

    private static void printRows(String label, List<Object[]> rows) {
        System.out.println(label + " (" + rows.size() + " rows)");
        rows.forEach(row -> System.out.println(Arrays.toString(row)));
    }
}
