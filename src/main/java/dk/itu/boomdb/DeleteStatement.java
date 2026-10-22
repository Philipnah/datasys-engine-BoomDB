package dk.itu.boomdb;

import java.util.Optional;

/**
 * A parsed {@code DELETE FROM} statement.
 *
 * @param tableName table whose matching rows to remove
 * @param where optional row predicate; empty removes every row
 */
public record DeleteStatement(String tableName, Optional<Predicate> where)
        implements Statement { }
