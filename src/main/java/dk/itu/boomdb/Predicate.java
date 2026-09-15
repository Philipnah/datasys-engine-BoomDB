package dk.itu.boomdb;

/**
 * A typed comparison predicate.
 *
 * @param columnName column to compare
 * @param comparison comparison operation
 * @param constant exactly typed comparison value
 */
public record Predicate(String columnName, Comparison comparison, Object constant) { }
