package dk.itu.boomdb;

import java.math.BigDecimal;
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
            case SelectStatement select ->
                "SELECT * FROM " + select.tableName() + where(select.where()) + ";";
            case DeleteStatement delete ->
                "DELETE FROM " + delete.tableName() + where(delete.where()) + ";";
        };
    }

    private static String where(Optional<Predicate> where) {
        return where.map(predicate -> " WHERE " + predicate.columnName() + " "
                + symbol(predicate.comparison()) + " " + literal(predicate.constant()))
                .orElse("");
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
