package dk.itu.boomdb;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Persists immutable catalog versions and leases them to running statements. */
final class CatalogStore {
    private static final Logger LOGGER = LoggerFactory.getLogger(CatalogStore.class);
    private static final int RETAINED_VERSIONS = 5;
    private static final Duration MAX_AGE = Duration.ofHours(1);
    private final Path catalogDirectory;
    private final Path versionDirectory;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Clock clock;
    private final Map<String, NavigableMap<Long, Version>> tables = new HashMap<>();

    CatalogStore(Path dataDirectory) {
        this(dataDirectory, Clock.systemUTC());
    }

    CatalogStore(Path dataDirectory, Clock clock) {
        this.clock = clock;
        catalogDirectory = dataDirectory.toAbsolutePath().normalize().resolve("catalogs");
        versionDirectory = catalogDirectory.resolve("versions");
        try {
            Files.createDirectories(versionDirectory);
        } catch (IOException error) {
            throw new UncheckedIOException("cannot create catalog directory", error);
        }
    }

    synchronized Optional<TableCatalog> load(String tableName) {
        NavigableMap<Long, Version> versions = versions(tableName);
        return versions.isEmpty() ? Optional.empty()
                : Optional.of(versions.lastEntry().getValue().table);
    }

    /** Pins the latest committed version before a statement binds or plans. */
    synchronized Snapshot acquire(String tableName) {
        TableCatalog table = load(tableName)
                .orElseThrow(() -> new IllegalArgumentException("unknown table: " + tableName));
        return acquire(tableName, table.version());
    }

    /** Pins a retained version, or rejects an unknown or expired version. */
    synchronized Snapshot acquire(String tableName, long version) {
        Version entry = versions(tableName).get(version);
        if (entry == null) {
            throw new IllegalArgumentException("unknown or expired version: " + tableName + " v" + version);
        }
        entry.readers++;
        return new Snapshot(entry);
    }

    /**
     * Validates a writer's pinned base and publishes its next version as one operation.
     * Partition preparation happens before acquiring this monitor; rejected files stay orphaned.
     *
     * @param base catalog pinned when the statement started
     * @param partitions prepared partition list to publish
     * @return newly committed version number
     * @throws ConflictException if another writer committed after the base was pinned
     */
    synchronized long commit(TableCatalog base, List<PartitionMetadata> partitions) {
        // ponytail: one store monitor serializes commits; per-table locks if throughput requires it.
        TableCatalog current = load(base.tableName())
                .orElseThrow(() -> new IllegalArgumentException("unknown table: " + base.tableName()));
        if (base.version() != current.version()) {
            // The SQL/API boundary logs this message once, like every other mutation failure.
            throw new ConflictException("conflict table=" + base.tableName()
                    + " base=" + base.version() + " current=" + current.version());
        }
        long version = Math.incrementExact(current.version());
        save(new TableCatalog(base.tableName(), base.columns(), version, partitions));
        return version;
    }

    /** Flushes and atomically finalizes a new version before making it visible. */
    synchronized void save(TableCatalog table) {
        NavigableMap<Long, Version> versions = versions(table.tableName());
        Path target = versionDirectory.resolve(table.tableName() + "-v" + table.version() + ".json");
        // Check both layouts, so a legacy version cannot be overwritten under a new name.
        if (discover(table.tableName()).containsKey(table.version()) || Files.exists(target)) {
            throw new IllegalArgumentException("catalog version already exists: " + target);
        }
        Path temporary = null;
        try {
            temporary = Files.createTempFile(versionDirectory, table.tableName() + "-", ".tmp");
            byte[] bytes = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(table);
            try (FileChannel file = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    file.write(buffer);
                }
                file.force(true);
            }
            // No fallback: an unsupported atomic rename must leave the old version published.
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            versions.put(table.version(), new Version(table, target, clock.instant()));
        } catch (IOException error) {
            throw new UncheckedIOException("cannot write catalog " + target, error);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException error) {
                    LOGGER.warn("cannot remove temporary catalog {}", temporary, error);
                }
            }
        }
        cleanup(versions);
    }

    private NavigableMap<Long, Version> versions(String tableName) {
        validateName(tableName);
        NavigableMap<Long, Version> versions = tables.get(tableName);
        if (versions == null) {
            versions = discover(tableName);
            cleanup(versions);
            tables.put(tableName, versions);
        }
        return versions;
    }

    private NavigableMap<Long, Version> discover(String tableName) {
        NavigableMap<Long, Version> found = new TreeMap<>();
        for (Path directory : List.of(catalogDirectory, versionDirectory)) {
            try (var files = Files.list(directory)) {
                for (Path file : files.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().endsWith(".json")).toList()) {
                    TableCatalog table = objectMapper.readValue(Files.readAllBytes(file), TableCatalog.class);
                    validateName(table.tableName());
                    String name = file.getFileName().toString();
                    boolean legacy = directory.equals(catalogDirectory)
                            && name.equals(table.tableName() + ".json");
                    boolean versioned = name.equals(table.tableName() + "-v" + table.version() + ".json");
                    if (!legacy && !versioned) {
                        throw new IllegalArgumentException("catalog identity does not match filename: " + file);
                    }
                    // Inspect document identity first: t-v1.json can be a legacy table named t-v1.
                    if (table.tableName().equals(tableName)) {
                        Version previous = found.put(table.version(), new Version(table, file,
                                Files.getLastModifiedTime(file).toInstant()));
                        if (previous != null) {
                            throw new IllegalArgumentException("duplicate catalog version: " + tableName
                                    + " v" + table.version());
                        }
                    }
                }
            } catch (IOException error) {
                throw new UncheckedIOException("cannot read catalogs in " + directory, error);
            }
        }
        return found;
    }

    private static void validateName(String tableName) {
        if (tableName == null || tableName.isBlank() || tableName.contains("/")
                || tableName.contains("\\") || tableName.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("invalid table name: " + tableName);
        }
    }

    /** Removes only catalog files; partition collection needs a separate orphan scan. */
    private void cleanup(NavigableMap<Long, Version> versions) {
        Instant cutoff = clock.instant().minus(MAX_AGE);
        int rank = 0;
        Iterator<Version> iterator = versions.descendingMap().values().iterator();
        while (iterator.hasNext()) {
            Version version = iterator.next();
            rank++;
            if (rank == 1 || version.readers > 0
                    || (rank <= RETAINED_VERSIONS && !version.created.isBefore(cutoff))) {
                continue;
            }
            try {
                // Delete the discovered path, including old layouts, to prevent resurrection.
                Files.deleteIfExists(version.path);
                iterator.remove();
            } catch (IOException error) {
                // A cleanup failure must not report a successfully published commit as aborted.
                LOGGER.warn("cannot expire catalog {}", version.path, error);
            }
        }
    }

    /** A reference-counted lease on one immutable table catalog. */
    final class Snapshot implements AutoCloseable {
        private final Version version;
        private boolean closed;

        private Snapshot(Version version) {
            this.version = version;
        }

        TableCatalog table() {
            return version.table;
        }

        @Override
        public void close() {
            // Lease release and retention share the publication/snapshot monitor.
            synchronized (CatalogStore.this) {
                if (!closed) {
                    closed = true;
                    version.readers--;
                    cleanup(versions(version.table.tableName()));
                }
            }
        }
    }

    private static final class Version {
        final TableCatalog table;
        final Path path;
        final Instant created;
        int readers;

        Version(TableCatalog table, Path path, Instant created) {
            this.table = table;
            this.path = path;
            this.created = created;
        }
    }
}

/** One immutable committed partition list; missing legacy version fields mean version zero. */
@JsonIgnoreProperties(value = "copied")
record TableCatalog(String tableName, List<ColumnSpec> columns, long version,
                    List<PartitionMetadata> partitions) {
    TableCatalog {
        if (version < 0) {
            throw new IllegalArgumentException("catalog version must not be negative");
        }
        columns = List.copyOf(columns);
        partitions = List.copyOf(partitions);
    }
}

/** A reference to an immutable PAX file, including its per-column summaries. */
record PartitionMetadata(int id, String fileName, int rowCount,
                         List<ColumnStatistics> statistics) {
    PartitionMetadata {
        statistics = List.copyOf(statistics);
    }
}

record ColumnStatistics(String columnName, String min, String max) { }
