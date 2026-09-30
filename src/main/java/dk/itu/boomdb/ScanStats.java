package dk.itu.boomdb;

/**
 * Counts partition decisions made for the most recently planned selection.
 *
 * @param partitionsTotal total partitions considered
 * @param partitionsRead partitions whose rows were read
 * @param partitionsPruned partitions skipped using min/max statistics
 */
public record ScanStats(int partitionsTotal, int partitionsRead, int partitionsPruned) { }
