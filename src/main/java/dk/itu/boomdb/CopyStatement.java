package dk.itu.boomdb;

/**
 * A parsed {@code COPY} statement.
 *
 * @param tableName destination table
 * @param csvFilePath source CSV path
 */
public record CopyStatement(String tableName, String csvFilePath) implements Statement { }
