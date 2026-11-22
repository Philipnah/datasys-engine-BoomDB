package dk.itu.boomdb;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Chooses a connected left-deep join order using estimated output cardinalities. */
final class GreedyJoinOrder {
    private static final Logger LOGGER = LoggerFactory.getLogger(GreedyJoinOrder.class);
    private GreedyJoinOrder() { }

    /** Each step joins the preceding prefix with the next table. */
    record Order(List<String> tableNames, List<Step> steps) {
        Order {
            tableNames = List.copyOf(tableNames);
            steps = List.copyOf(steps);
        }
    }
    record Step(JoinPredicate predicate, double estimatedRows) { }

    static Order choose(SelectStatement statement, List<TableCatalog> tables, Map<String, Double> filteredRows) {
        List<String> names = statement.tableNames();
        ColumnResolver resolver = new ColumnResolver(tables);
        List<Edge> edges = new ArrayList<>();
        for (JoinPredicate predicate : statement.joins()) {
            var left = resolver.resolve(predicate.leftColumn());
            var right = resolver.resolve(predicate.rightColumn());
            int a = names.indexOf(left.tableName());
            int b = names.indexOf(right.tableName());
            if (a < 0 || b < 0 || a == b) {
                throw new IllegalArgumentException("join predicate must connect two FROM tables");
            }
            edges.add(new Edge(predicate, a, b,
                    CardinalityEstimator.effectiveNdv(tables.get(a), left.columnIndex()),
                    CardinalityEstimator.effectiveNdv(tables.get(b), right.columnIndex())));
        }
        validateTree(names.size(), edges);
        if (names.size() == 1) return new Order(names, List.of());

        Candidate best = null;
        for (Edge edge : edges) {
            int first = Math.min(edge.left(), edge.right());
            int second = Math.max(edge.left(), edge.right());
            double firstRows = filteredRows.get(names.get(first));
            double secondRows = filteredRows.get(names.get(second));
            double estimate = CardinalityEstimator.joinRows(firstRows, secondRows, edge.leftNdv(), edge.rightNdv());
            logCandidate(1, names.get(first), names.get(second), edge, firstRows, secondRows, estimate);
            Candidate candidate = new Candidate(edge, first, second, estimate);
            if (better(candidate, best)) best = candidate;
        }
        List<String> ordered = new ArrayList<>();
        List<Step> steps = new ArrayList<>();
        Set<Integer> joined = new HashSet<>();
        joined.add(best.first());
        joined.add(best.second());
        ordered.add(names.get(best.first()));
        ordered.add(names.get(best.second()));
        steps.add(new Step(best.edge().predicate(), best.rows()));
        double rows = best.rows();
        while (joined.size() < names.size()) {
            best = null;
            for (Edge edge : edges) {
                boolean leftJoined = joined.contains(edge.left());
                boolean rightJoined = joined.contains(edge.right());
                if (leftJoined == rightJoined) continue;
                int added = leftJoined ? edge.right() : edge.left();
                double addedRows = filteredRows.get(names.get(added));
                double estimate = CardinalityEstimator.joinRows(rows, addedRows, edge.leftNdv(), edge.rightNdv());
                logCandidate(steps.size() + 1, String.join(";", ordered), names.get(added), edge, rows, addedRows, estimate);
                Candidate candidate = new Candidate(edge, added, added, estimate);
                if (better(candidate, best)) best = candidate;
            }
            joined.add(best.first());
            ordered.add(names.get(best.first()));
            rows = best.rows();
            steps.add(new Step(best.edge().predicate(), rows));
        }
        return new Order(ordered, steps);
    }

    private static boolean better(Candidate candidate, Candidate best) {
        if (best == null) return true;
        int comparison = Double.compare(candidate.rows(), best.rows());
        if (comparison != 0) return comparison < 0;
        if (candidate.first() != best.first()) return candidate.first() < best.first();
        return candidate.second() < best.second();
    }

    private static void validateTree(int count, List<Edge> edges) {
        int[] parent = new int[count];
        for (int i = 0; i < count; i++) parent[i] = i;
        for (Edge edge : edges) {
            int a = edge.left();
            int b = edge.right();
            while (parent[a] != a) a = parent[a];
            while (parent[b] != b) b = parent[b];
            if (a == b) throw new IllegalArgumentException("multiple connecting join predicates or cycle are unsupported");
            parent[a] = b;
        }
        if (edges.size() != count - 1) {
            throw new IllegalArgumentException("disconnected join graph requires a cross product");
        }
    }

    private static void logCandidate(int step, String joined, String added, Edge edge,
            double leftRows, double rightRows, double estimate) {
        LOGGER.debug("event=JOIN_CANDIDATE step={} joined={} added={} predicate={}={} leftRows={} rightRows={} estRows={}",
                step, clean(joined), clean(added), clean(edge.predicate().leftColumn()),
                clean(edge.predicate().rightColumn()), leftRows, rightRows, estimate);
    }

    private static String clean(String value) {
        return value.replace(',', ';').replace('"', '\'').replace('\n', ' ').replace('\r', ' ');
    }

    private record Edge(JoinPredicate predicate, int left, int right, long leftNdv, long rightNdv) { }
    private record Candidate(Edge edge, int first, int second, double rows) { }
}
