package dk.itu.boomdb;

import java.util.List;

/** Pure cardinality estimates based on partition statistics. */
final class CardinalityEstimator {
    private CardinalityEstimator() { }

    /** The maximum is a lower bound: distinct values may overlap across partitions. */
    static long tableNdv(List<Long> partitionNdvs) {
        long maximum = 0;
        for (long ndv : partitionNdvs) {
            if (ndv < 0) {
                throw new IllegalArgumentException("NDV must not be negative");
            }
            maximum = Math.max(maximum, ndv);
        }
        return maximum;
    }

    static long baseRows(List<PartitionMetadata> partitions) {
        long total = 0;
        for (PartitionMetadata partition : partitions) {
            total = Math.addExact(total, partition.rowCount());
        }
        return total;
    }

    static long effectiveNdv(TableCatalog table, int columnIndex) {
        return tableNdv(table.partitions().stream()
                .map(partition -> partition.statistics().get(columnIndex).ndv()).toList());
    }

    static double filterRows(double inputRows, Comparison comparison, long ndv) {
        validate(inputRows, ndv);
        return inputRows / (comparison == Comparison.EQUALS ? Math.max(1, ndv) : 3.0);
    }

    static double joinRows(double leftRows, double rightRows, long leftNdv, long rightNdv) {
        validate(leftRows, leftNdv);
        validate(rightRows, rightNdv);
        // Divide in double precision first to avoid overflowing an intermediate product.
        double estimate = (leftRows / Math.max(1, Math.max(leftNdv, rightNdv))) * rightRows;
        return Double.isInfinite(estimate) ? Double.MAX_VALUE : estimate;
    }

    private static void validate(double rows, long ndv) {
        if (!Double.isFinite(rows) || rows < 0 || ndv < 0) {
            throw new IllegalArgumentException("cardinality and NDV must be finite and nonnegative");
        }
    }
}
