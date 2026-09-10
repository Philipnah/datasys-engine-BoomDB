package dk.itu.boomdb;

import java.util.stream.Collectors;

/** Renders BoomDB SQL statements in a normalized form. */
public final class SqlPrinter {
    /**
     * Renders a statement as semicolon-terminated SQL that parses to an equal statement.
     *
     * @param statement statement to render
     * @return normalized SQL text
     */
    public String print(Statement statement) {
        if (statement instanceof CreateTableStatement create) {
            String columns = create.columns().stream()
                    .map(column -> column.name() + " " + column.type())
                    .collect(Collectors.joining(", "));
            return "CREATE TABLE " + create.tableName() + " (" + columns + ");";
        }
        if (statement instanceof CopyStatement copy) {
            return "COPY " + copy.tableName() + " FROM '" + copy.csvFilePath() + "';";
        }

        SelectStatement select = (SelectStatement) statement;
        String sql = "SELECT * FROM " + select.tableName();
        if (select.where().isPresent()) {
            Predicate predicate = select.where().orElseThrow();
            sql += " WHERE " + predicate.columnName() + " "
                    + symbol(predicate.comparison()) + " " + literal(predicate.constant());
        }
        return sql + ";";
    }

    private static String symbol(Comparison comparison) {
        return switch (comparison) {
            case EQUALS -> "=";
            case LESS_THAN -> "<";
            case GREATER_THAN -> ">";
        };
    }

    private static String literal(Object constant) {
        return constant instanceof String text ? "'" + text + "'" : constant.toString();
    }
}
