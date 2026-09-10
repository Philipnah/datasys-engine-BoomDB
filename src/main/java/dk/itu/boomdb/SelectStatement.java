package dk.itu.boomdb;

import java.util.Optional;

/**
 * A parsed {@code SELECT *} statement.
 *
 * @param tableName source table
 * @param where optional row predicate
 */
public record SelectStatement(String tableName, Optional<Predicate> where)
        implements Statement { }
