package dk.itu.boomdb;

import dk.itu.datasys.sql.SqlBaseVisitor;
import dk.itu.datasys.sql.SqlParser.ColumnDefContext;
import dk.itu.datasys.sql.SqlParser.CopyContext;
import dk.itu.datasys.sql.SqlParser.CreateTableContext;
import dk.itu.datasys.sql.SqlParser.LiteralContext;
import dk.itu.datasys.sql.SqlParser.PredicateContext;
import dk.itu.datasys.sql.SqlParser.ScriptContext;
import dk.itu.datasys.sql.SqlParser.SelectContext;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.antlr.v4.runtime.Token;

/** Converts generated ANTLR parse trees into BoomDB's typed SQL AST. */
final class SqlAstBuilder extends SqlBaseVisitor<Object> {
    @Override
    public List<Statement> visitScript(ScriptContext context) {
        return context.statement().stream()
                .map(statement -> (Statement) visit(statement))
                .toList();
    }

    @Override
    public CreateTableStatement visitCreateTable(CreateTableContext context) {
        List<ColumnSpec> columns = context.columnDef().stream()
                .map(column -> (ColumnSpec) visit(column))
                .toList();
        return new CreateTableStatement(context.IDENTIFIER().getText(), columns);
    }

    @Override
    public ColumnSpec visitColumnDef(ColumnDefContext context) {
        ColumnType type = ColumnType.valueOf(
                context.columnType().getText().toUpperCase(Locale.ROOT));
        return new ColumnSpec(context.IDENTIFIER().getText(), type);
    }

    @Override
    public CopyStatement visitCopy(CopyContext context) {
        return new CopyStatement(
                context.IDENTIFIER().getText(), unquote(context.STRING_LITERAL().getText()));
    }

    @Override
    public SelectStatement visitSelect(SelectContext context) {
        Optional<Predicate> predicate = context.predicate() == null
                ? Optional.empty()
                : Optional.of((Predicate) visit(context.predicate()));
        return new SelectStatement(context.IDENTIFIER().getText(), predicate);
    }

    @Override
    public Predicate visitPredicate(PredicateContext context) {
        Comparison comparison = switch (context.comparison.getText()) {
            case "=" -> Comparison.EQUALS;
            case "<" -> Comparison.LESS_THAN;
            case ">" -> Comparison.GREATER_THAN;
            default -> throw new IllegalStateException("unsupported comparison");
        };
        return new Predicate(context.IDENTIFIER().getText(), comparison,
                visit(context.literal()));
    }

    @Override
    public Object visitLiteral(LiteralContext context) {
        Token token = context.getStart();
        try {
            if (context.STRING_LITERAL() != null) {
                return unquote(token.getText());
            }
            if (context.LONG_LITERAL() != null) {
                return Long.valueOf(token.getText());
            }
            return Double.valueOf(token.getText());
        } catch (NumberFormatException error) {
            throw new SqlParseException("numeric literal is out of range",
                    token.getLine(), token.getCharPositionInLine());
        }
    }

    private static String unquote(String text) {
        return text.substring(1, text.length() - 1);
    }
}
