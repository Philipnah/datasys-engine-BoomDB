package dk.itu.boomdb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.Test;

class GreedyJoinOrderTest {
    private static final JoinPredicate AB = new JoinPredicate("a_b", "b_a");
    private static final JoinPredicate AC = new JoinPredicate("a_c", "c_a");
    private static final JoinPredicate BD = new JoinPredicate("b_d", "d_b");
    private static final Map<String, Double> ROWS = Map.of("A", 10000.0, "B", 100.0, "C", 10.0, "D", 1.0);

    @Test
    void selectsSmallestPairAndConnectedExtensions() {
        var order = choose(List.of("A", "B", "C"), List.of(AB, AC));
        assertEquals(List.of("A", "C", "B"), order.tableNames());
        assertEquals(List.of(1000.0, 1000.0), order.steps().stream().map(GreedyJoinOrder.Step::estimatedRows).toList());
        assertEquals(List.of(AC, AB), order.steps().stream().map(GreedyJoinOrder.Step::predicate).toList());
        assertThrows(UnsupportedOperationException.class, () -> order.tableNames().add("D"));
        assertThrows(UnsupportedOperationException.class, () -> order.steps().clear());
    }

    @Test
    void logsEveryCandidateAndNeverAddsDisconnectedTable() {
        var root = ((LoggerContext) LogManager.getContext(false)).getConfiguration().getRootLogger();
        var messages = new ArrayList<String>();
        var appender = new AbstractAppender("greedy-test", null, null, false, Property.EMPTY_ARRAY) {
            @Override
            public void append(LogEvent event) {
                if (event.getLoggerName().equals(GreedyJoinOrder.class.getName())) {
                    messages.add(event.getMessage().getFormattedMessage());
                }
            }
        };
        appender.start();
        root.addAppender(appender, null, null);
        try {
            var order = choose(List.of("A", "B", "C", "D"), List.of(AB, AC, BD));
            assertEquals(List.of("B", "D", "A", "C"), order.tableNames());
            assertEquals(List.of(1.0, 100.0, 10.0), order.steps().stream().map(GreedyJoinOrder.Step::estimatedRows).toList());
            var candidates = messages.stream().filter(message -> message.contains("event=JOIN_CANDIDATE")).toList();
            assertEquals(5, candidates.size());
            assertEquals(3, candidates.stream().filter(message -> message.contains("step=1 ")).count());
            assertEquals(1, candidates.stream().filter(message -> message.contains("step=2 ")).count());
            assertEquals(1, candidates.stream().filter(message -> message.contains("step=3 ")).count());
            assertTrue(candidates.stream().allMatch(message -> message.contains("joined=") && message.contains("added=")
                    && message.contains("predicate=") && message.contains("leftRows=") && message.contains("rightRows=")
                    && message.contains("estRows=") && !message.contains(",")));
            assertTrue(candidates.stream().anyMatch(message -> message.contains("joined=B;D") && message.contains("added=A")));
        } finally {
            root.removeAppender(appender.getName());
            appender.stop();
        }
    }

    @Test
    void resolvesReversedOperandsAndFromOrderTies() {
        var order = choose(List.of("A", "C", "B"), List.of(new JoinPredicate("b_d", "c_a"), new JoinPredicate("b_a", "a_b")),
                Map.of("A", 100.0, "B", 100.0, "C", 100.0));
        assertEquals(List.of("A", "B", "C"), order.tableNames());
        assertEquals(2, order.steps().size());
    }

    @Test
    void singleTableAndUnsupportedGraphs() {
        assertEquals(List.of("A"), choose(List.of("A"), List.of()).tableNames());
        assertTrue(choose(List.of("A"), List.of()).steps().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> choose(List.of("A", "B", "C"), List.of(AB)));
        assertThrows(IllegalArgumentException.class, () -> choose(List.of("A", "B", "C"), List.of(AB, AC, new JoinPredicate("b_d", "c_a"))));
        assertThrows(IllegalArgumentException.class, () -> choose(List.of("A", "B"), List.of(AB, new JoinPredicate("a_c", "b_d"))));
        assertThrows(IllegalArgumentException.class, () -> choose(List.of("A"), List.of(new JoinPredicate("a_b", "a_c"))));
    }

    private static GreedyJoinOrder.Order choose(List<String> names, List<JoinPredicate> joins) {
        return choose(names, joins, ROWS);
    }

    private static GreedyJoinOrder.Order choose(List<String> names, List<JoinPredicate> joins, Map<String, Double> rows) {
        return GreedyJoinOrder.choose(new SelectStatement(names, List.of(), joins), names.stream().map(GreedyJoinOrderTest::table).toList(), rows);
    }

    private static TableCatalog table(String name) {
        var columns = switch (name) {
            case "A" -> List.of("a_b", "a_c");
            case "B" -> List.of("b_a", "b_d");
            case "C" -> List.of("c_a");
            case "D" -> List.of("d_b");
            default -> throw new IllegalArgumentException(name);
        };
        int count = ROWS.get(name).intValue();
        var specs = columns.stream().map(c -> new ColumnSpec(c, ColumnType.LONG)).toList();
        var stats = columns.stream().map(c -> new ColumnStatistics(c, "0", "1", name.equals("C") ? 10 : name.equals("D") ? 1 : 100)).toList();
        return new TableCatalog(name, specs, 0, List.of(new PartitionMetadata(0, "p", count, stats)));
    }
}
