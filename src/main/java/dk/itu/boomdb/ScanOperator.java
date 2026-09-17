package dk.itu.boomdb;

import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;

/** Reads every row from a fixed list of persistent partitions. */
public final class ScanOperator implements Operator {
    private final StorageEngine storage;
    private final TableCatalog table;
    private final List<PartitionMetadata> partitions;

    private int partitionIndex;
    private Iterator<Object[]> rows = Collections.emptyIterator();

    ScanOperator(StorageEngine storage, TableCatalog table,
            List<PartitionMetadata> partitions) {
        this.storage = Objects.requireNonNull(storage, "storage");
        this.table = Objects.requireNonNull(table, "table");
        this.partitions = List.copyOf(partitions);
    }

    @Override
    public void open() {
        partitionIndex = 0;
        rows = Collections.emptyIterator();
    }

    @Override
    public Object[] next() {
        while (!rows.hasNext()) {
            if (partitionIndex == partitions.size()) {
                return null;
            }
            rows = storage.readPartition(table, partitions.get(partitionIndex++)).iterator();
        }
        return rows.next();
    }

    @Override
    public void close() {
        rows = Collections.emptyIterator();
    }

    List<PartitionMetadata> partitions() {
        return partitions;
    }
}
