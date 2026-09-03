package dk.itu.boomdb;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Optional;

/** Persists one JSON catalog document per table. */
final class CatalogStore {
    private final Path catalogDirectory;
    private final ObjectMapper objectMapper = new ObjectMapper();

    CatalogStore(Path dataDirectory) {
        catalogDirectory = dataDirectory.toAbsolutePath().normalize().resolve("catalogs");
        try {
            Files.createDirectories(catalogDirectory);
        } catch (IOException error) {
            throw new UncheckedIOException("cannot create catalog directory", error);
        }
    }

    Optional<TableCatalog> load(String tableName) {
        Path catalog = catalogPath(tableName);
        if (!Files.exists(catalog)) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(catalog.toFile(), TableCatalog.class));
        } catch (IOException error) {
            throw new UncheckedIOException("cannot read catalog " + catalog, error);
        }
    }

    void save(TableCatalog table) {
        Path catalog = catalogPath(table.tableName());
        Path temporary = catalog.resolveSibling(catalog.getFileName() + ".tmp");
        try {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), table);
            moveIntoPlace(temporary, catalog);
        } catch (IOException error) {
            throw new UncheckedIOException("cannot write catalog " + catalog, error);
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
        try {
            Files.move(temporary, target,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException error) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}

record TableCatalog(String tableName, List<ColumnSpec> columns, boolean copied,
                    List<PartitionMetadata> partitions) { }

record PartitionMetadata(int id, String fileName, int rowCount,
                         List<ColumnStatistics> statistics) { }

record ColumnStatistics(String columnName, String min, String max) { }
