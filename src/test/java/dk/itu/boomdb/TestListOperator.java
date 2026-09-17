package dk.itu.boomdb;

import java.util.List;

final class TestListOperator implements Operator {
    private final List<Object[]> rows;
    private int index;
    private boolean opened;
    private boolean closed;

    TestListOperator(List<Object[]> rows) {
        this.rows = List.copyOf(rows);
    }

    @Override
    public void open() {
        index = 0;
        opened = true;
        closed = false;
    }

    @Override
    public Object[] next() {
        return index < rows.size() ? rows.get(index++) : null;
    }

    @Override
    public void close() {
        closed = true;
    }

    boolean opened() {
        return opened;
    }

    boolean closed() {
        return closed;
    }
}
