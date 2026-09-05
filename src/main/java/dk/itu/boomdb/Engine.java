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

import java.util.stream.Collectors;
import java.util.stream.IntStream;

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
        System.out.println("\n" + label + " (" + rows.size() + " rows)");

        if (rows.isEmpty()) {
            System.out.println("┌──────────┐\n│ (empty)  │\n└──────────┘");
            return;
        }

        // Determine the maximum number of columns across all rows
        int colCount = rows.stream().mapToInt(r -> r.length).max().orElse(0);

        // Calculate maximum string width for each column
        int[] colWidths = new int[colCount];
        for (Object[] row : rows) {
            for (int i = 0; i < row.length; i++) {
                String val = row[i] == null ? "NULL" : row[i].toString();
                colWidths[i] = Math.max(colWidths[i], val.length());
            }
        }

        // Build separators
        String topBorder = IntStream.range(0, colCount)
                .mapToObj(i -> "─".repeat(colWidths[i] + 2))
                .collect(Collectors.joining("┬", "┌", "┐"));

        String bottomBorder = IntStream.range(0, colCount)
                .mapToObj(i -> "─".repeat(colWidths[i] + 2))
                .collect(Collectors.joining("┴", "└", "┘"));

        // Print top frame
        System.out.println(topBorder);

        // Print data rows
        for (Object[] row : rows) {
            StringBuilder sb = new StringBuilder("│");
            for (int i = 0; i < colCount; i++) {
                String val = (i < row.length && row[i] != null) ? row[i].toString() : (i < row.length ? "NULL" : "");
                sb.append(String.format(" %-" + colWidths[i] + "s │", val));
            }
            System.out.println(sb);
        }

        // Print bottom frame
        System.out.println(bottomBorder);
    }
}
