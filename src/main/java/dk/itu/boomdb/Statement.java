package dk.itu.boomdb;

/** A parsed SQL statement. */
public sealed interface Statement
        permits CreateTableStatement, CopyStatement, SelectStatement { }
