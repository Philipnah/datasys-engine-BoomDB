package dk.itu.boomdb;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/** Bridges absent Exercise 10 APIs without preventing the red tests from compiling. */
final class Exercise10TestApi {
    private Exercise10TestApi() { }

    static SelectStatement parse(String sql) {
        return assertInstanceOf(SelectStatement.class,
                assertDoesNotThrow(() -> new SqlParser().parse(sql),
                        "Exercise 10 SELECT syntax must parse").getFirst());
    }

    /** Constructs ASTs independently of the parser so validation tests cannot pass on syntax errors. */
    static SelectStatement select(List<String> tables, List<Predicate> filters, String... columns) {
        List<Object> joins = new ArrayList<>();
        for (int index = 0; index < columns.length; index += 2) {
            joins.add(create("JoinPredicate", new Class<?>[] {String.class, String.class},
                    columns[index], columns[index + 1]));
        }
        return assertInstanceOf(SelectStatement.class, create("SelectStatement",
                new Class<?>[] {List.class, List.class, List.class}, tables, filters, joins));
    }

    static Object create(String type, Class<?>[] signature, Object... arguments) {
        return reflect(() -> {
            var constructor = Class.forName("dk.itu.boomdb." + type).getDeclaredConstructor(signature);
            constructor.setAccessible(true);
            return constructor.newInstance(arguments);
        });
    }

    static Object read(Object target, String accessor) {
        return call(target, accessor, new Class<?>[0]);
    }

    static Object call(Object target, String method, Class<?>[] signature, Object... arguments) {
        return reflect(() -> {
            var member = target.getClass().getDeclaredMethod(method, signature);
            member.setAccessible(true);
            return member.invoke(target, arguments);
        });
    }

    static List<Object[]> drain(Operator operator) {
        List<Object[]> rows = new ArrayList<>();
        operator.open();
        try {
            for (Object[] row; (row = operator.next()) != null;) {
                // These tiny fixtures never exceed six rows; catch a cursor that never advances.
                assertTrue(rows.size() < 100, "operator did not reach the end of its input");
                rows.add(row);
            }
        } finally {
            operator.close();
        }
        return rows;
    }

    /** Preserve production exceptions so assertThrows checks the binder/resolver, not reflection. */
    private static <T> T reflect(Callable<T> action) {
        try {
            return action.call();
        } catch (InvocationTargetException error) {
            if (error.getCause() instanceof RuntimeException cause) {
                throw cause;
            }
            if (error.getCause() instanceof Error cause) {
                throw cause;
            }
            return fail("Exercise 10 API threw a checked exception", error.getCause());
        } catch (Exception error) {
            return fail("Missing Exercise 10 API: " + error, error);
        }
    }
}
