package dk.itu.boomdb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LauncherIT {
    @Test
    void scriptRunsCopyCopyDeleteAndSelectThroughPackagedEngine(@TempDir Path directory)
            throws Exception {
        Path csv = directory.resolve("rows.csv");
        Files.writeString(csv, "A,1\nB,2\nC,3\n");
        Path script = directory.resolve("exercise7.sql");
        Files.writeString(script, """
                CREATE TABLE t (city STRING, id LONG);
                COPY t FROM '%s';
                COPY t FROM '%s';
                DELETE FROM t WHERE id = 2;
                SELECT * FROM t;
                """.formatted(csv, csv));
        Path stdout = directory.resolve("stdout");
        Path stderr = directory.resolve("stderr");
        // Exercise the existing launcher directly, including from another working directory.
        Process process = new ProcessBuilder(Path.of("engine.sh").toAbsolutePath().toString(),
                "-f", script.toString()).directory(directory.toFile())
                .redirectOutput(stdout.toFile()).redirectError(stderr.toFile()).start();
        assertTrue(process.waitFor(30, TimeUnit.SECONDS), "engine did not finish within 30 seconds");
        assertEquals(0, process.exitValue(), () -> read(stderr));
        assertEquals("A,1\nC,3\nA,1\nC,3\n", Files.readString(stdout).replace("\r\n", "\n"));
        StorageEngine restarted = new StorageEngine(directory.resolve("data"));
        assertEquals(3, restarted.table("t").version());
        assertEquals(4, new Executor(restarted).execute("SELECT * FROM t;").size());
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (java.io.IOException error) {
            throw new java.io.UncheckedIOException(error);
        }
    }
}
