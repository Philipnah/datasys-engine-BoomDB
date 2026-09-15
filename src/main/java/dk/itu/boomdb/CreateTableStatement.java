package dk.itu.boomdb;

import java.util.List;

/**
 * A parsed {@code CREATE TABLE} statement.
 *
 * @param tableName table to create
 * @param columns ordered column definitions
 */
public record CreateTableStatement(String tableName, List<ColumnSpec> columns)
        implements Statement { }
