package dk.itu.boomdb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Persists one JSON catalog document per table version and leases reader snapshots. */
final class CatalogStore {
    private static final Logger LOGGER = LoggerFactory.getLogger(CatalogStore.class);
    private static final int MAX_RETAINED_VERSIONS = 5;
    private static final long RETENTION_MILLIS = Duration.ofHours(1).toMillis();
    // ponytail: serialize catalog work per directory; finer locks can follow measured contention.
    private static final Map<Path, DirectoryState> DIRECTORIES = new ConcurrentHashMap<>();

    private final Path catalogDirectory;
    private final DirectoryState state;
    private final ObjectMapper objectMapper = new ObjectMapper();

    CatalogStore(Path dataDirectory) {
        try {
            Path directory = dataDirectory.toAbsolutePath().normalize().resolve("catalogs");
            Files.createDirectories(directory);
            // Real paths make leases shared even when two engines use different symlink aliases.
            catalogDirectory = directory.toRealPath();
            state = DIRECTORIES.computeIfAbsent(catalogDirectory, unused -> new DirectoryState());
        } catch (IOException error) {
            throw new UncheckedIOException("cannot create catalog directory", error);
        }
    }

    Optional<TableCatalog> load(String tableName) {
        synchronized (state) {
            try {
                var latest = catalogFiles(tableName).firstEntry();
                if (latest == null) {
                    state.latest.remove(tableName);
                } else {
                    state.latest.put(tableName,
                            readCatalog(tableName, latest.getKey(), latest.getValue()));
                }
                return Optional.ofNullable(state.latest.get(tableName));
            } catch (IOException error) {
                throw new UncheckedIOException("cannot read catalog for " + tableName, error);
            }
        }
    }

    /**
     * Pins the latest committed metadata until the returned snapshot is closed.
     *
     * @param tableName table whose latest version to pin
     * @return a lease protecting immutable metadata from retention
     * @throws IllegalArgumentException if the table is unknown
     */
    Snapshot pin(String tableName) {
        synchronized (state) {
            TableCatalog table = load(tableName)
                    .orElseThrow(() -> new IllegalArgumentException("unknown table: " + tableName));
            return lease(table);
        }
    }

    /**
     * Pins a retained version, failing explicitly once retention has removed it.
     *
     * @param tableName table owning the requested version
     * @param version retained version to pin
     * @return a lease protecting the requested metadata from retention
     * @throws IllegalArgumentException if the requested version is unavailable
     */
    Snapshot pin(String tableName, long version) {
        synchronized (state) {
            try {
                Path catalog = catalogFiles(tableName).get(version);
                if (catalog == null) {
                    throw new IllegalArgumentException(
                            "catalog version " + version + " is not retained for table " + tableName);
                }
                return lease(readCatalog(tableName, version, catalog));
            } catch (IOException error) {
                throw new UncheckedIOException("cannot read catalog for " + tableName, error);
            }
        }
    }

    /** Publishes a complete version by atomic rename; a failed publication preserves the latest. */
    void save(TableCatalog table) {
        synchronized (state) {
            catalogPath(table.tableName());
            Path catalog = catalogPath(table.tableName() + "-v" + table.version());
            Path temporary = null;
            try {
                // Never replace an immutable catalog, including one left by a previous engine.
                if (Files.exists(catalog)) {
                    throw new FileAlreadyExistsException(catalog.toString());
                }
                temporary = Files.createTempFile(catalogDirectory,
                        table.tableName() + "-v" + table.version() + "-", ".tmp");
                objectMapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), table);
                try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                    channel.force(true);
                }
                moveIntoPlace(temporary, catalog);
            } catch (IOException error) {
                if (temporary != null) {
                    try {
                        Files.deleteIfExists(temporary);
                    } catch (IOException cleanupError) {
                        error.addSuppressed(cleanupError);
                    }
                }
                throw new UncheckedIOException("cannot write catalog " + catalog, error);
            }
            // Publish version and partition references together only after the disk commit.
            state.latest.put(table.tableName(), table);
            retainVersions(table.tableName());
        }
    }

    private Snapshot lease(TableCatalog table) {
        state.readers.merge(new VersionKey(table.tableName(), table.version()), 1, Integer::sum);
        return new Snapshot(table);
    }

    /** Discovers only complete filenames; numeric ordering avoids v10 sorting before v2. */
    private NavigableMap<Long, Path> catalogFiles(String tableName) throws IOException {
        Path legacy = catalogPath(tableName);
        Pattern pattern = Pattern.compile(Pattern.quote(tableName) + "-v(0|[1-9][0-9]*)\\.json");
        NavigableMap<Long, Path> versions = new TreeMap<>(java.util.Comparator.reverseOrder());
        try (var files = Files.list(catalogDirectory)) {
            for (Path path : files.filter(Files::isRegularFile).toList()) {
                Matcher matcher = pattern.matcher(path.getFileName().toString());
                if (matcher.matches()) {
                    try {
                        versions.put(Long.parseLong(matcher.group(1)), path);
                    } catch (NumberFormatException ignored) {
                        // Out-of-range names are not versions this engine can commit.
                    }
                }
            }
        }
        if (Files.isRegularFile(legacy)) {
            TableCatalog baseline = readCatalog(tableName, null, legacy);
            versions.putIfAbsent(baseline.version(), legacy);
        }
        return versions;
    }

    private TableCatalog readCatalog(String tableName, Long expectedVersion, Path path)
            throws IOException {
        JsonNode json = objectMapper.readTree(path.toFile());
        TableCatalog table = objectMapper.treeToValue(json, TableCatalog.class);
        if (!json.has("version")) {
            table = new TableCatalog(table.tableName(), table.columns(), table.copied(),
                    table.partitions(), table.copied() ? 1 : 0,
                    Files.getLastModifiedTime(path).toMillis());
        }
        if (!tableName.equals(table.tableName())
                || expectedVersion != null && table.version() != expectedVersion) {
            throw new IOException("catalog identity does not match " + path);
        }
        return table;
    }

    /** Best-effort cleanup must never turn a successfully published commit into a failure. */
    private void retainVersions(String tableName) {
        try {
            long oldestCommit = System.currentTimeMillis() - RETENTION_MILLIS;
            int rank = 0;
            for (var entry : catalogFiles(tableName).entrySet()) {
                boolean withinCount = rank++ < MAX_RETAINED_VERSIONS;
                if (rank == 1 || state.readers.containsKey(new VersionKey(tableName, entry.getKey()))) {
                    continue;
                }
                TableCatalog version = readCatalog(tableName, entry.getKey(), entry.getValue());
                if (!withinCount || version.committedAtMillis() < oldestCommit) {
                    Files.deleteIfExists(entry.getValue());
                }
            }
        } catch (IOException error) {
            LOGGER.warn("cannot clean up catalog versions for table={}", tableName, error);
        }
    }

    private Path catalogPath(String tableName) {
        if (tableName == null || tableName.isBlank()) {
            throw new IllegalArgumentException("table name must not be blank");
        }
        Path path = catalogDirectory.resolve(tableName + ".json").normalize();
        if (!catalogDirectory.equals(path.getParent())) {
            throw new IllegalArgumentException("invalid table name: " + tableName);
        }
        return path;
    }

    private static void moveIntoPlace(Path temporary, Path target) throws IOException {
        // No non-atomic fallback: readers must only discover complete catalogs.
        Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
    }

    /** A reference-counted lease whose close is safe to call more than once. */
    final class Snapshot implements AutoCloseable {
        private final TableCatalog table;
        private boolean closed;

        private Snapshot(TableCatalog table) {
            this.table = table;
        }

        TableCatalog table() {
            return table;
        }

        /** Releases this lease and retries retention without affecting other readers. */
        @Override
        public void close() {
            synchronized (state) {
                if (!closed) {
                    closed = true;
                    VersionKey key = new VersionKey(table.tableName(), table.version());
                    state.readers.computeIfPresent(key, (unused, count) -> count == 1 ? null : count - 1);
                    retainVersions(table.tableName());
                }
            }
        }
    }

    private static final class DirectoryState {
        private final Map<String, TableCatalog> latest = new HashMap<>();
        private final Map<VersionKey, Integer> readers = new HashMap<>();
    }

    private record VersionKey(String tableName, long version) { }
}

/** Immutable schema and partition references belonging to one committed table version. */
record TableCatalog(String tableName, List<ColumnSpec> columns, boolean copied,
                    List<PartitionMetadata> partitions, long version, long committedAtMillis) {
    TableCatalog {
        if (version < 0 || committedAtMillis < 0) {
            throw new IllegalArgumentException("catalog version and commit time must not be negative");
        }
        columns = List.copyOf(columns);
        partitions = List.copyOf(partitions);
    }

    TableCatalog(String tableName, List<ColumnSpec> columns, boolean copied,
            List<PartitionMetadata> partitions) {
        this(tableName, columns, copied, partitions, copied ? 1 : 0, System.currentTimeMillis());
    }
}

/** Immutable identity and statistics for a separately stored partition file. */
record PartitionMetadata(int id, String fileName, int rowCount,
                         List<ColumnStatistics> statistics) {
    PartitionMetadata {
        statistics = List.copyOf(statistics);
    }
}

record ColumnStatistics(String columnName, String min, String max) { }
