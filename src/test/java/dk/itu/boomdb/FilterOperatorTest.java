package dk.itu.boomdb;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class FilterOperatorTest {
    private static final List<ColumnSpec> COLUMNS = List.of(
            new ColumnSpec("city", ColumnType.STRING),
            new ColumnSpec("distance", ColumnType.LONG),
            new ColumnSpec("price", ColumnType.DOUBLE));

    @Test
    void emitsOnlyMatchingRowsAndClosesItsChild() {
        TestListOperator child = new TestListOperator(List.of(
                new Object[] {"Copenhagen", 12L, 23.5},
                new Object[] {"Aarhus", 187L, 301.0},
                new Object[] {"Odense", 95L, 120.75}));
        FilterOperator filter = new FilterOperator(child,
                new Predicate("distance", Comparison.GREATER_THAN, 100L), COLUMNS);

        filter.open();
        Object[] row = filter.next();
        Object[] exhausted = filter.next();
        filter.close();

        assertArrayEquals(new Object[] {"Aarhus", 187L, 301.0}, row);
        assertNull(exhausted);
        assertTrue(child.opened());
        assertTrue(child.closed());
    }

    @Test
    void returnsNoRowsWhenNothingMatches() {
        TestListOperator child = new TestListOperator(List.<Object[]>of(
                new Object[] {"Copenhagen", 12L, 23.5}));
        FilterOperator filter = new FilterOperator(child,
                new Predicate("distance", Comparison.GREATER_THAN, 500L), COLUMNS);

        filter.open();
        Object[] row = filter.next();
        filter.close();

        assertNull(row);
    }
}
