package dk.itu.boomdb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import java.util.List;
import org.junit.jupiter.api.Test;

class CardinalityEstimatorTest {
    @Test
    void estimatesUseFiniteFractionalArithmetic() {
        assertEquals(5, CardinalityEstimator.tableNdv(List.of(2L, 5L, 3L)));
        assertEquals(0, CardinalityEstimator.tableNdv(List.of()));
        assertEquals(100, CardinalityEstimator.baseRows(List.of(partition(40, 12L), partition(60, 20L))));
        assertEquals(10, CardinalityEstimator.filterRows(120, Comparison.EQUALS, 12));
        assertEquals(40, CardinalityEstimator.filterRows(120, Comparison.LESS_THAN, 12));
        assertEquals(10.0 / 3, CardinalityEstimator.filterRows(10, Comparison.GREATER_THAN, 12), 1e-9);
        assertEquals(200, CardinalityEstimator.joinRows(100, 40, 20, 10));
        assertEquals(0, CardinalityEstimator.joinRows(0, 40, 0, 10));
        assertEquals(0.5, CardinalityEstimator.joinRows(1, 1, 2, 1));
        assertEquals(Double.MAX_VALUE, CardinalityEstimator.joinRows(Double.MAX_VALUE, 2, 1, 1));
        assertEquals(1e20, CardinalityEstimator.joinRows(1e10, 1e10, 1, 1));
        assertEquals(10, CardinalityEstimator.filterRows(10, Comparison.EQUALS, 0));
        assertThrows(IllegalArgumentException.class, () -> CardinalityEstimator.joinRows(-1, 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> CardinalityEstimator.filterRows(Double.NaN, Comparison.EQUALS, 1));
        assertThrows(IllegalArgumentException.class, () -> CardinalityEstimator.filterRows(1, Comparison.EQUALS, -1));
    }

    @Test
    void effectiveNdvUsesMaximumPartitionStatistic() {
        TableCatalog table = new TableCatalog("t", List.of(new ColumnSpec("id", ColumnType.LONG)), 0,
                List.of(partition(40, 12L), partition(60, 20L)));
        assertEquals(20, CardinalityEstimator.effectiveNdv(table, 0));
        assertEquals(0, CardinalityEstimator.effectiveNdv(new TableCatalog("t", table.columns(), 0, List.of()), 0));
    }

    @Test
    void effectiveNdvFallsBackToMaximumRowsWhenAllStatisticsAreMissing() {
        TableCatalog table = new TableCatalog("t", List.of(new ColumnSpec("id", ColumnType.LONG)), 0,
                List.of(partition(15, null), partition(20, null)));
        assertEquals(20, CardinalityEstimator.effectiveNdv(table, 0));
        assertEquals(35, CardinalityEstimator.baseRows(table.partitions()));
    }

    @Test
    void effectiveNdvFallbackIncludesPartitionsWithKnownStatistics() {
        TableCatalog table = new TableCatalog("t", List.of(new ColumnSpec("id", ColumnType.LONG)), 0,
                List.of(partition(20, 3L), partition(15, null)));
        assertEquals(20, CardinalityEstimator.effectiveNdv(table, 0));
    }

    @Test
    void effectiveNdvIgnoresEmptyPartitionsWithoutStatistics() {
        TableCatalog table = new TableCatalog("t", List.of(new ColumnSpec("id", ColumnType.LONG)), 0,
                List.of(partition(0, null), partition(20, 3L)));
        assertEquals(3, CardinalityEstimator.effectiveNdv(table, 0));
    }

    private static PartitionMetadata partition(int rows, Long ndv) {
        return new PartitionMetadata(0, "p", rows, List.of(new ColumnStatistics("id", "1", "2", ndv)));
    }
}
