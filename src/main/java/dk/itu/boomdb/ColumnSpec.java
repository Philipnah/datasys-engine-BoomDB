package dk.itu.boomdb;

/**
 * Describes a table column.
 *
 * @param name the column name
 * @param type the column value type
 */
public record ColumnSpec(String name, ColumnType type) { }
