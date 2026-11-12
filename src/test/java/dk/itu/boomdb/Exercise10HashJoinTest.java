package dk.itu.boomdb;

import static dk.itu.boomdb.Exercise10TestApi.create;
import static dk.itu.boomdb.Exercise10TestApi.drain;
import static dk.itu.boomdb.Exercise10TestData.sortedRows;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class Exercise10HashJoinTest {
    @Test
    void duplicateKeysOnBothSidesEmitEveryPairAcrossSuccessiveNextCalls() {
        Operator join = join(new TestListOperator(List.of(
                new Object[] {1L, "build-a"}, new Object[] {1L, "build-b"})),
                new TestListOperator(List.of(new Object[] {1L, "probe-x"},
                        new Object[] {1L, "probe-y"}, new Object[] {1L, "probe-z"})), 0, 0);

        // Literal pairs catch skipped matches, repeated pairs, and reuse of one mutable output array.
        assertEquals(List.of("1,build-a,1,probe-x", "1,build-a,1,probe-y", "1,build-a,1,probe-z",
                "1,build-b,1,probe-x", "1,build-b,1,probe-y", "1,build-b,1,probe-z"),
                sortedRows(drain(join)));
    }

    @Test
    void dropsUnmatchedRowsFromBothSidesAndKeepsBuildColumnsBeforeProbeColumns() {
        Operator join = join(new TestListOperator(List.of(
                new Object[] {"build-a", 1L}, new Object[] {"build-b", 2L})),
                new TestListOperator(List.of(new Object[] {3L, "probe-miss", 30.0},
                        new Object[] {2L, "probe-match", 20.0})), 1, 0);

        assertEquals(List.of("build-b,2,2,probe-match,20.0"), sortedRows(drain(join)));
    }

    @Test
    void resumesWithTheNextProbeAfterAnUnmatchedKeyBetweenMatches() {
        Operator join = join(new TestListOperator(List.<Object[]>of(new Object[] {"key", "build"})),
                new TestListOperator(List.of(new Object[] {"key", "first"},
                        new Object[] {"missing", "skip"}, new Object[] {"key", "last"})), 0, 0);

        assertEquals(List.of("key,build,key,first", "key,build,key,last"), sortedRows(drain(join)));
    }

    @Test
    void looksUpDoubleKeysAtANonzeroProbeIndex() {
        Operator join = join(new TestListOperator(List.<Object[]>of(new Object[] {"build", 2.5})),
                new TestListOperator(List.<Object[]>of(new Object[] {"probe", "payload", 2.5})), 1, 2);

        assertEquals(List.of("build,2.5,probe,payload,2.5"), sortedRows(drain(join)));
    }

    @Test
    void returnsNoRowsWhenNonemptyInputsHaveNoCommonKey() {
        Operator join = join(new TestListOperator(List.<Object[]>of(new Object[] {1L})),
                new TestListOperator(List.<Object[]>of(new Object[] {2L})), 0, 0);

        assertEquals(List.of(), drain(join));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void emptyBuildOrProbeProducesNoRowsAndBothChildrenAreClosed(boolean emptyBuild) {
        List<Object[]> oneRow = List.<Object[]>of(new Object[] {1L});
        TestListOperator build = new TestListOperator(emptyBuild ? List.of() : oneRow);
        TestListOperator probe = new TestListOperator(emptyBuild ? oneRow : List.of());

        assertEquals(List.of(), drain(join(build, probe, 0, 0)));
        assertTrue(build.opened());
        assertTrue(probe.opened());
        assertTrue(build.closed());
        assertTrue(probe.closed());
    }

    @Test
    void opensAndClosesBothChildrenAndStaysExhausted() {
        TestListOperator build = new TestListOperator(List.<Object[]>of(new Object[] {"same"}));
        TestListOperator probe = new TestListOperator(List.<Object[]>of(new Object[] {"same"}));
        Operator join = join(build, probe, 0, 0);

        join.open();
        try {
            assertArrayEquals(new Object[] {"same", "same"}, join.next());
            assertNull(join.next());
            assertNull(join.next());
        } finally {
            join.close();
        }
        assertTrue(build.opened());
        assertTrue(probe.opened());
        assertTrue(build.closed());
        assertTrue(probe.closed());
    }

    @Test
    void closeLogsBuildProbeAndOutputRowCounts() throws Exception {
        Operator join = join(new TestListOperator(List.of(
                new Object[] {"a", 1L}, new Object[] {"a", 2L},
                new Object[] {"b", 3L}, new Object[] {"unmatched", 4L})),
                new TestListOperator(List.of(new Object[] {"a", "x"},
                        new Object[] {"a", "y"}, new Object[] {"missing", "z"})), 0, 0);
        Path log = Path.of("logs/engine.log");
        long before = Files.readAllLines(log).size();

        assertEquals(4, drain(join).size());

        List<String> summaries = Files.readAllLines(log).stream().skip(before)
                .filter(line -> line.contains(",HashJoinOperator,"))
                .filter(line -> line.contains("rowsBuild=") && line.contains("rowsProbe=")
                        && line.contains("rowsOut=")).toList();
        assertEquals(1, summaries.size());
        String summary = summaries.getFirst();
        assertTrue(summary.matches(".*\\browsBuild=4\\b.*"), summaries::toString);
        assertTrue(summary.matches(".*\\browsProbe=3\\b.*"), summaries::toString);
        assertTrue(summary.matches(".*\\browsOut=4\\b.*"), summaries::toString);
    }

    private static Operator join(Operator build, Operator probe, int buildKey, int probeKey) {
        return assertInstanceOf(Operator.class, create("HashJoinOperator",
                new Class<?>[] {Operator.class, Operator.class, int.class, int.class},
                build, probe, buildKey, probeKey));
    }
}
