package dk.itu.boomdb;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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
        return switch (statement) {
            case CreateTableStatement create -> {
                String columns = create.columns().stream()
                        .map(column -> column.name() + " " + column.type())
                        .collect(Collectors.joining(", "));
                yield "CREATE TABLE " + create.tableName() + " (" + columns + ");";
            }
            case CopyStatement copy ->
                "COPY " + copy.tableName() + " FROM '" + copy.csvFilePath() + "';";
            case SelectStatement select -> {
                List<String> predicates = new ArrayList<>();
                select.filters().forEach(filter -> predicates.add(predicate(filter)));
                select.joins().forEach(join -> predicates.add(
                        join.leftColumn() + " = " + join.rightColumn()));
                yield "SELECT * FROM " + String.join(", ", select.tableNames())
                        + (predicates.isEmpty() ? "" : " WHERE " + String.join(" AND ", predicates)) + ";";
            }
            case DeleteStatement delete ->
                "DELETE FROM " + delete.tableName() + where(delete.where()) + ";";
        };
    }

    private static String where(Optional<Predicate> where) {
        return where.map(predicate -> " WHERE " + predicate(predicate))
                .orElse("");
    }

    private static String predicate(Predicate predicate) {
        return predicate.columnName() + " " + symbol(predicate.comparison())
                + " " + literal(predicate.constant());
    }

    private static String symbol(Comparison comparison) {
        return switch (comparison) {
            case EQUALS -> "=";
            case LESS_THAN -> "<";
            case GREATER_THAN -> ">";
        };
    }

    private static String literal(Object constant) {
        if (constant instanceof Double value) {
            String text = Double.toString(value);
            if (!text.contains("E")) {
                return text;
            }

            String plain = BigDecimal.valueOf(value).toPlainString();
            return plain.contains(".") ? plain : plain + ".0";
        }
        return constant instanceof String text ? "'" + text + "'" : constant.toString();
    }
}
