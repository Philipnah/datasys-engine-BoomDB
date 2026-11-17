package dk.itu.boomdb;

/**
 * An equality between columns of two different tables.
 *
 * @param leftColumn unqualified column on the left
 * @param rightColumn unqualified column on the right
 */
public record JoinPredicate(String leftColumn, String rightColumn) { }
