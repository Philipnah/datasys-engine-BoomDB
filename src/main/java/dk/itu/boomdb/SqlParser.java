package dk.itu.boomdb;

import java.util.List;
import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Parses SQL text into BoomDB statements. */
public final class SqlParser {
    private static final Logger LOGGER = LoggerFactory.getLogger(SqlParser.class);
    private static final BaseErrorListener ERROR_LISTENER = new ThrowingErrorListener();

    /**
     * Parses a whole script of semicolon-terminated statements.
     *
     * @param sqlText SQL script to parse
     * @return parsed statements in source order
     * @throws SqlParseException if the first lexical or syntax error is encountered
     */
    public List<Statement> parse(String sqlText) {
        long started = System.nanoTime();
        try {
            dk.itu.datasys.sql.SqlLexer lexer =
                    new dk.itu.datasys.sql.SqlLexer(CharStreams.fromString(sqlText));
            lexer.removeErrorListeners();
            lexer.addErrorListener(ERROR_LISTENER);

            dk.itu.datasys.sql.SqlParser parser =
                    new dk.itu.datasys.sql.SqlParser(new CommonTokenStream(lexer));
            parser.removeErrorListeners();
            parser.addErrorListener(ERROR_LISTENER);

            List<Statement> statements = List.copyOf(
                    new SqlAstBuilder().visitScript(parser.script()));
            LOGGER.debug("statements={} durationMs={}", statements.size(), elapsedMillis(started));
            return statements;
        } catch (SqlParseException error) {
            LOGGER.error("failed line={} col={} durationMs={}",
                    error.line(), error.column(), elapsedMillis(started));
            throw error;
        }
    }

    private static long elapsedMillis(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }

    private static final class ThrowingErrorListener extends BaseErrorListener {
        @Override
        public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol,
                int line, int charPositionInLine, String message,
                RecognitionException error) {
            throw new SqlParseException(message, line, charPositionInLine);
        }
    }
}
