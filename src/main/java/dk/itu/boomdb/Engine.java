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
            "Usage: boomdb [-j optimized|syntax] (-c '<SQL statement>' | -f <script.sql>)";
    private static final String TEAMNAME = "Team BoomDB";

    private Engine() { }

    /**
     * Runs one BoomDB session using {@code data/} below the working directory.
     *
     * @param args optional join mode and either a SQL command or script path
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
            out.println(TEAMNAME);
            out.println(USAGE);
            return 0;
        }

        try {
            String command = null;
            String value = null;
            JoinMode mode = JoinMode.OPTIMIZED;
            boolean modeSeen = false;
            for (int i = 0; i < args.length; i++) {
                String flag = args[i];
                if (i + 1 >= args.length) {
                    throw new IllegalArgumentException(USAGE);
                }
                String argument = args[++i];
                switch (flag) {
                    case "-j" -> {
                        if (modeSeen) {
                            throw new IllegalArgumentException(USAGE);
                        }
                        modeSeen = true;
                        mode = switch (argument) {
                            case "optimized" -> JoinMode.OPTIMIZED;
                            case "syntax" -> JoinMode.SYNTAX;
                            default -> throw new IllegalArgumentException(USAGE);
                        };
                    }
                    case "-c", "-f" -> {
                        if (command != null) {
                            throw new IllegalArgumentException(USAGE);
                        }
                        command = flag;
                        value = argument;
                    }
                    default -> throw new IllegalArgumentException(USAGE);
                }
            }
            if (command == null) {
                throw new IllegalArgumentException(USAGE);
            }
            String sql = command.equals("-f")
                    ? Files.readString(Path.of(value), StandardCharsets.UTF_8) : value;
            List<Object[]> rows = new Executor(new StorageEngine(dataDirectory), mode).execute(sql);
            for (Object[] row : rows) {
                out.println(csvRow(row));
            }
            return 0;
        } catch (IOException | RuntimeException error) {
            err.println(error.getMessage());
            return 1;
        }
    }

    private static String csvRow(Object[] row) {
        StringJoiner csv = new StringJoiner(",");
        for (Object value : row) {
            csv.add(String.valueOf(value));
        }
        return csv.toString();
    }
}
