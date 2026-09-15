package dk.itu.boomdb;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.stream.Collectors;
import java.util.stream.IntStream;

/** Runs the Exercise 3 SQL parsing demonstration. */
public final class Engine {
    private static final Logger LOGGER = LoggerFactory.getLogger(Engine.class);

    /**
     * Parses and prints the four required SQL statements without executing them.
     *
     * @param args ignored command-line arguments
     */
    public static void main(String[] args) {
        // TODO: For AI: do not implement this yet, wait until explicitly told to do so
        // Setup before program loop
        // 1. startup logger
        // 2. check if database exists
        // 3. if not make new

        // Program loop
            // 1. wait for user input
            // 2. receive SQL from user
            // 3. Parse sql
            // 4. do the operations
            // 5. go back to waiting for user input

            // User exits program and program ends


        MDC.put("sessionId", UUID.randomUUID().toString());
        MDC.put("statementNumber", "0");
        LOGGER.debug("engine started");
        try {
            String sql = """
                    CREATE TABLE trips (city STRING, distance LONG, price DOUBLE);
                    COPY trips FROM 'trips.csv';
                    SELECT * FROM trips WHERE distance > 100;
                    SELECT * FROM trips;
                    """;
            SqlPrinter printer = new SqlPrinter();
            for (Statement statement : new SqlParser().parse(sql)) {
                System.out.println(printer.print(statement));
            }
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
