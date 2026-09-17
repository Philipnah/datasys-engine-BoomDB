package dk.itu.boomdb;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.StringJoiner;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/** Runs BoomDB through its SQL command-line front door. */
public final class Engine {
    private static final Logger LOGGER = LoggerFactory.getLogger(Engine.class);
    private static final String USAGE =
            "Usage: boomdb '<SQL statement>' | boomdb -f <script.sql>";

    private Engine() { }

    /**
     * Runs one BoomDB session using {@code data/} below the working directory.
     *
     * @param args no arguments, one SQL statement, or {@code -f <script.sql>}
     */
    public static void main(String[] args) {
        MDC.put("sessionId", UUID.randomUUID().toString());
        MDC.put("statementNumber", "0");
        LOGGER.debug("engine started");
        try {
            run(args, Path.of("data"), System.out, System.err);
        } finally {
            MDC.put("statementNumber", "0");
            LOGGER.debug("engine stopped");
        }
    }

    static int run(String[] args, Path dataDirectory, PrintStream out, PrintStream err) {
        Objects.requireNonNull(args, "args");
        Objects.requireNonNull(dataDirectory, "dataDirectory");
        Objects.requireNonNull(out, "out");
        Objects.requireNonNull(err, "err");

        if (args.length == 0) {
            out.println(teamName());
            out.println(USAGE);
            return 0;
        }

        try {
            String sql;
            if (args.length == 1) {
                sql = args[0];
            } else if (args.length == 2 && args[0].equals("-f")) {
                sql = Files.readString(Path.of(args[1]), StandardCharsets.UTF_8);
            } else {
                throw new IllegalArgumentException(USAGE);
            }

            List<Object[]> rows = new Executor(new StorageEngine(dataDirectory)).execute(sql);
            for (Object[] row : rows) {
                out.println(csvRow(row));
            }
            return 0;
        } catch (IOException | RuntimeException error) {
            err.println(error.getMessage());
            return 1;
        }
    }

    static String teamName() {
        return "Team BoomDB";
    }

    private static String csvRow(Object[] row) {
        StringJoiner csv = new StringJoiner(",");
        for (Object value : row) {
            csv.add(String.valueOf(value));
        }
        return csv.toString();
    }
}
