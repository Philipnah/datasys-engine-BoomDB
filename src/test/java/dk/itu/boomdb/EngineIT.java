package dk.itu.boomdb;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class EngineIT {
    @Test
    void mainPrintsTheThreeGoldenQueryResults() {
        PrintStream original = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
            Engine.main(new String[0]);
        } finally {
            System.setOut(original);
        }

        String output = captured.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("distance GREATER_THAN 100 (4 rows)"));
        assertTrue(output.contains("city EQUALS Copenhagen (3 rows)"));
        assertTrue(output.contains("price LESS_THAN 50.0 (2 rows)"));
    }
}
