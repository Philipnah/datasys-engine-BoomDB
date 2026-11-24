package dk.itu.boomdb;

import java.util.Objects;

/** Restores a row's columns to a requested order. */
final class ColumnOrderOperator implements Operator {
    private final Operator child;
    private final int[] sourceIndexes;

    /**
     * Creates a column permutation.
     * @param child physical input
     * @param sourceIndexes input index for each output column
     */
    ColumnOrderOperator(Operator child, int[] sourceIndexes) {
        this.child = Objects.requireNonNull(child, "child");
        this.sourceIndexes = sourceIndexes.clone();
    }

    @Override
    public void open() {
        child.open();
    }

    @Override
    public Object[] next() {
        Object[] row = child.next();
        if (row == null) {
            return null;
        }
        Object[] result = new Object[sourceIndexes.length];
        for (int i = 0; i < result.length; i++) {
            result[i] = row[sourceIndexes[i]];
        }
        return result;
    }

    @Override
    public void close() {
        child.close();
    }

    Operator child() {
        return child;
    }
}
