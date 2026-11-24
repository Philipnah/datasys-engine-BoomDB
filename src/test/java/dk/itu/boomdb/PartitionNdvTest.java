package dk.itu.boomdb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PartitionNdvTest {
    @Test
    void copyCountsTypedValuesPerPartitionAndPersistsThem(@TempDir Path dir) throws Exception {
        Path csv = dir.resolve("rows.csv");
        String table = "ndv_" + UUID.randomUUID().toString().replace("-", "");
        Files.writeString(csv, "1,1.5,a\n1,1.5,a\n2,2.5,b\n2,2.5,c\n1,1.5,a\n");
        StorageEngine engine = new StorageEngine(dir.resolve("db"), 4);
        engine.createTable(table, List.of(new ColumnSpec("i", ColumnType.LONG),
                new ColumnSpec("d", ColumnType.DOUBLE), new ColumnSpec("s", ColumnType.STRING)));
        engine.copyFile(table, csv.toString());
        TableCatalog reloaded = new StorageEngine(dir.resolve("db"), 4).table(table);
        assertEquals(List.of(2L, 2L, 3L), reloaded.partitions().getFirst().statistics().stream().map(ColumnStatistics::ndv).toList());
        assertEquals(List.of(1L, 1L, 1L), reloaded.partitions().getLast().statistics().stream().map(ColumnStatistics::ndv).toList());
        String prefix = "creating statistics for table=" + table + " ";
        List<String> records = Files.readAllLines(Path.of("logs/engine.log")).stream()
                .filter(line -> line.contains(prefix))
                .map(line -> line.substring(line.indexOf(prefix) + prefix.length()))
                .toList();
        assertEquals(List.of(
                "partition=0 column=i min=1 max=2", "partition=0 column=i rows=4 ndv=2",
                "partition=0 column=d min=1.5 max=2.5", "partition=0 column=d rows=4 ndv=2",
                "partition=0 column=s min=a max=c", "partition=0 column=s rows=4 ndv=3",
                "partition=1 column=i min=1 max=1", "partition=1 column=i rows=1 ndv=1",
                "partition=1 column=d min=1.5 max=1.5", "partition=1 column=d rows=1 ndv=1",
                "partition=1 column=s min=a max=a", "partition=1 column=s rows=1 ndv=1"), records);
    }

    @Test
    void rejectsInvalidPartitionNdv() {
        for (long invalid : new long[] {-1, 0, 3}) {
            assertThrows(IllegalArgumentException.class, () -> new PartitionMetadata(0, "p", 2,
                    List.of(new ColumnStatistics("i", "1", "2", invalid))));
        }
    }
}
