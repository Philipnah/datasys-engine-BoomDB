package dk.itu.boomdb;

import static dk.itu.boomdb.Exercise10TestApi.create;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.List;
import org.junit.jupiter.api.Test;

class ColumnOrderOperatorTest {
    @Test
    void permutesCopiesAndClosesChild() {
        TestListOperator child = new TestListOperator(List.of(new Object[]{1, 2, 3}, new Object[]{4, 5, 6}));
        int[] indexes = {2, 0, 1};
        Operator op = (Operator) create("ColumnOrderOperator", new Class<?>[]{Operator.class, int[].class}, child, indexes);
        indexes[0] = 0;
        op.open();
        Object[] first = op.next();
        Object[] second = op.next();
        assertArrayEquals(new Object[]{3, 1, 2}, first);
        assertArrayEquals(new Object[]{6, 4, 5}, second);
        assertNotSame(first, second);
        assertNull(op.next());
        op.close();
        assertTrue(child.closed());
    }
}
