package dk.itu.boomdb;

import static dk.itu.boomdb.Exercise10TestApi.drain;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OptimizerLoggingTest {
    @Test
    void javaApiIdentifiersKeepOptimizerMessagesInOneCsvField(@TempDir Path directory) throws Exception {
        StorageEngine storage = new StorageEngine(directory.resolve("db"));
        String column = "left,\"\nkey";
        storage.createTable("left,table", List.of(new ColumnSpec(column, ColumnType.LONG)));
        storage.createTable("right_table", List.of(new ColumnSpec("right_key", ColumnType.LONG)));
        Path csv = directory.resolve("one.csv");
        Files.writeString(csv, "1\n");
        storage.copyFile("left,table", csv.toString());
        storage.copyFile("right_table", csv.toString());
        SelectStatement statement = new SelectStatement(List.of("left,table", "right_table"),
                List.of(), List.of(new JoinPredicate(column, "right_key")));
        new Binder(storage).bind(statement);

        List<String> messages = new ArrayList<>();
        var root = ((LoggerContext) LogManager.getContext(false)).getConfiguration().getRootLogger();
        var appender = new AbstractAppender("optimizer-identifier-test", null, null, false,
                Property.EMPTY_ARRAY) {
            @Override
            public void append(LogEvent event) {
                String message = event.getMessage().getFormattedMessage();
                if (message.contains("joinOrder=") || message.contains("event=JOIN_CANDIDATE")
                        || event.getLoggerName().equals(HashJoinOperator.class.getName())) {
                    messages.add(message);
                }
            }
        };
        appender.start();
        root.addAppender(appender, null, null);
        try {
            assertEquals(List.of("1,1"), Exercise10TestData.sortedRows(drain(new Planner(storage).plan(statement))));
        } finally {
            root.removeAppender(appender.getName());
            appender.stop();
        }

        assertEquals(3, messages.size());
        for (String message : messages) {
            assertFalse(message.contains(","), message);
            assertFalse(message.contains("\""), message);
            assertFalse(message.contains("\n") || message.contains("\r"), message);
        }
    }
}
