package dk.itu.boomdb;

/** Selects the join planning policy. */
public enum JoinMode {
    /** Choose a greedy order and build the smaller estimated input. */
    OPTIMIZED,
    /** Fold tables in FROM order, building the left input. */
    SYNTAX
}
