package dk.itu.boomdb;

import java.util.Optional;

/**
 * A parsed copy-on-write {@code DELETE} statement.
 *
 * @param tableName table whose matching rows are removed
 * @param where optional predicate; absent means delete every row
 */
public record DeleteStatement(String tableName, Optional<Predicate> where)
        implements Statement { }
