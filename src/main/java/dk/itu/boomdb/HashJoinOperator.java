package dk.itu.boomdb;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Inner equi-join: builds a hash table from the build child, streams the probe child. */
public final class HashJoinOperator implements Operator {
    private static final Logger LOGGER = LoggerFactory.getLogger(HashJoinOperator.class);
    private final Operator build;
    private final Operator probe;
    private final int buildKey;
    private final int probeKey;
    private final Map<Object, List<Object[]>> rowsByKey = new HashMap<>();
    private Iterator<Object[]> matches = Collections.emptyIterator();
    private Object[] probeRow;
    private boolean exhausted;
    private long rowsBuild;
    private long rowsProbe;
    private long rowsOut;

    HashJoinOperator(Operator build, Operator probe, int buildKey, int probeKey) {
        this.build = Objects.requireNonNull(build, "build");
        this.probe = Objects.requireNonNull(probe, "probe");
        if (buildKey < 0 || probeKey < 0) {
            throw new IllegalArgumentException("join key indices must be nonnegative");
        }
        this.buildKey = buildKey;
        this.probeKey = probeKey;
    }

    @Override
    public void open() {
        rowsByKey.clear();
        matches = Collections.emptyIterator();
        probeRow = null;
        exhausted = false;
        rowsBuild = 0;
        rowsProbe = 0;
        rowsOut = 0;
        try {
            build.open();
            for (Object[] row; (row = build.next()) != null; ) {
                rowsByKey.computeIfAbsent(row[buildKey], key -> new ArrayList<>()).add(row);
                rowsBuild++;
            }
            probe.open();
        } catch (RuntimeException error) {
            // The caller cannot enter its drain/finally block when open itself fails.
            try {
                close();
            } catch (RuntimeException cleanup) {
                error.addSuppressed(cleanup);
            }
            throw error;
        }
    }

    @Override
    public Object[] next() {
        if (exhausted) {
            return null;
        }
        while (!matches.hasNext()) {
            probeRow = probe.next();
            if (probeRow == null) {
                exhausted = true;
                return null;
            }
            rowsProbe++;
            // Keep this cursor between calls: duplicate keys emit every build/probe pair.
            matches = rowsByKey.getOrDefault(probeRow[probeKey], List.of()).iterator();
        }
        Object[] buildRow = matches.next();
        Object[] output = Arrays.copyOf(buildRow, buildRow.length + probeRow.length);
        System.arraycopy(probeRow, 0, output, buildRow.length, probeRow.length);
        rowsOut++;
        return output;
    }

    @Override
    public void close() {
        try {
            build.close();
        } finally {
            try {
                probe.close();
            } finally {
                rowsByKey.clear();
                matches = Collections.emptyIterator();
                probeRow = null;
                exhausted = true;
                LOGGER.debug("rowsBuild={} rowsProbe={} rowsOut={}", rowsBuild, rowsProbe, rowsOut);
            }
        }
    }

    Operator build() {
        return build;
    }

    Operator probe() {
        return probe;
    }
}
