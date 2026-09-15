package dk.itu.boomdb;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import static java.nio.ByteOrder.LITTLE_ENDIAN;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Package-private binary and predicate operations used by the storage engine. */
final class StorageSupport {
    // The MAGIC bytes are the signature of a BoomDB partition file
    private static final byte[] MAGIC = {'B', 'D', 'B', 'P'};
    private static final int FORMAT_VERSION = 1;
    private static final int FIXED_HEADER_BYTES = MAGIC.length + 3 * Integer.BYTES;
    private static final int COLUMN_DIRECTORY_BYTES = 2 * Long.BYTES;

    private StorageSupport() { }

    /** Encodes a value of the specified type into a byte array. */
    static byte[] encodeValue(ColumnType type, Object value) {
        requireValueType(type, value);
        return switch (type) {
            case STRING -> encodeString((String) value);
            case LONG -> ByteBuffer.allocate(Long.BYTES).order(LITTLE_ENDIAN)
                    .putLong((Long) value).array();
            case DOUBLE -> ByteBuffer.allocate(Double.BYTES).order(LITTLE_ENDIAN)
                    .putDouble((Double) value).array();
        };
    }

    static Object decodeValue(ColumnType type, ByteBuffer source) {
        return switch (type) {
            case STRING -> decodeString(source);
            case LONG -> source.getLong();
            case DOUBLE -> source.getDouble();
        };
    }

    static MinMax minMax(ColumnType type, List<?> values) {
        if (values.isEmpty()) {
            throw new IllegalArgumentException("cannot compute min/max of no values");
        }
        Object min = values.getFirst();
        Object max = min;
        requireValueType(type, min);
        for (Object value : values) {
            requireValueType(type, value);
            if (compare(type, value, min) < 0) {
                min = value;
            }
            if (compare(type, value, max) > 0) {
                max = value;
            }
        }
        return new MinMax(min, max);
    }

    static boolean shouldPrune(ColumnType type, Comparison comparison,
            Object constant, Object min, Object max) {
        return switch (comparison) {
            case EQUALS -> compare(type, constant, min) < 0
                    || compare(type, constant, max) > 0;
            case LESS_THAN -> compare(type, min, constant) >= 0;
            case GREATER_THAN -> compare(type, max, constant) <= 0;
        };
    }

    static boolean matches(ColumnType type, Comparison comparison,
            Object value, Object constant) {
        int result = compare(type, value, constant);
        return switch (comparison) {
            case EQUALS -> result == 0;
            case LESS_THAN -> result < 0;
            case GREATER_THAN -> result > 0;
        };
    }

    static Object[] parseCsvLine(Path source, int lineNumber, String line,
            List<ColumnSpec> columns) {
        String[] fields = line.split(",", -1);
        if (fields.length != columns.size()) {
            throw csvError(source, lineNumber,
                    "expected " + columns.size() + " fields but found " + fields.length, null);
        }
        Object[] row = new Object[columns.size()];
        try {
            for (int index = 0; index < columns.size(); index++) {
                row[index] = switch (columns.get(index).type()) {
                    case STRING -> fields[index];
                    case LONG -> Long.valueOf(fields[index]);
                    case DOUBLE -> Double.valueOf(fields[index]);

                };
            }
            return row;
        } catch (NumberFormatException error) {
            throw csvError(source, lineNumber, "malformed value", error);
        }
    }

    // This way of storing data, means that we cannot efficiently update a string to a larger string, 
    // because the exact amount of space is allocated for each string.
    // In this course we deal with immutable data, so this is not a problem.
    // INFO: One way of solving this is to store updates separately for a certain number of strings, 
    // until a threshold is reached, then update all the string in a partition that have been updated.
    static void writePartition(Path target, List<ColumnSpec> columns,
            List<Object[]> rows) throws IOException {
        List<byte[]> chunks = new ArrayList<>(columns.size());
        for (int columnIndex = 0; columnIndex < columns.size(); columnIndex++) {
            ByteArrayOutputStream chunk = new ByteArrayOutputStream();
            for (Object[] row : rows) {
                if (row.length != columns.size()) {
                    throw new IllegalArgumentException("row does not match partition schema");
                }
                chunk.writeBytes(encodeValue(columns.get(columnIndex).type(), row[columnIndex]));
            }
            chunks.add(chunk.toByteArray());
        }

        int headerSize = Math.addExact(FIXED_HEADER_BYTES,
                Math.multiplyExact(columns.size(), COLUMN_DIRECTORY_BYTES));
        ByteBuffer header = ByteBuffer.allocate(headerSize).order(LITTLE_ENDIAN);
        header.put(MAGIC).putInt(FORMAT_VERSION).putInt(rows.size()).putInt(columns.size());

        long offset = headerSize;
        for (byte[] chunk : chunks) {
            header.putLong(offset).putLong(chunk.length);
            offset = Math.addExact(offset, chunk.length);
        }

        ByteArrayOutputStream partition = new ByteArrayOutputStream(Math.toIntExact(offset));
        partition.writeBytes(header.array());
        chunks.forEach(partition::writeBytes);
        Files.write(target, partition.toByteArray());
    }

    static List<Object[]> readPartition(Path source, List<ColumnSpec> columns)
            throws IOException {
        byte[] bytes = Files.readAllBytes(source);
        ByteBuffer file = ByteBuffer.wrap(bytes).order(LITTLE_ENDIAN);
        // The full decoder uses the same layout validation as the selective reader so both
        // paths reject malformed partitions according to identical format rules.
        PartitionLayout layout = readLayout(file, columns.size(), bytes.length, source);
        Object[][] rows = new Object[layout.rowCount()][columns.size()];

        for (int columnIndex = 0; columnIndex < columns.size(); columnIndex++) {
            ByteBuffer chunk = ByteBuffer.wrap(bytes, layout.offsets()[columnIndex],
                    layout.lengths()[columnIndex]).slice().order(LITTLE_ENDIAN);
            try {
                for (int rowIndex = 0; rowIndex < layout.rowCount(); rowIndex++) {
                    rows[rowIndex][columnIndex] =
                            decodeValue(columns.get(columnIndex).type(), chunk);
                }
            } catch (RuntimeException error) {
                throw new IllegalArgumentException("invalid column data in " + source, error);
            }
            if (chunk.hasRemaining()) {
                throw new IllegalArgumentException("unexpected bytes in column chunk in " + source);
            }
        }

        List<Object[]> result = new ArrayList<>(layout.rowCount());
        result.addAll(Arrays.asList(rows));
        return result;
    }

    /**
     * Reads only the predicate chunk first, materializing complete rows only when they match.
     *
     * <p>The PAX directory lets this reader locate that chunk without loading the whole
     * partition. This avoids decoding and allocating values for rows rejected by the predicate.
     *
     * @param source partition file to scan
     * @param columns schema in partition order
     * @param predicateColumnIndex predicate column index in the schema
     * @param comparison comparison to apply
     * @param constant typed predicate constant
     * @return matching rows in schema and disk order
     * @throws IOException if the partition cannot be read
     */
    static List<Object[]> readMatchingRows(Path source, List<ColumnSpec> columns,
            int predicateColumnIndex, Comparison comparison, Object constant) throws IOException {
        if (predicateColumnIndex < 0 || predicateColumnIndex >= columns.size()) {
            throw new IllegalArgumentException("invalid predicate column index");
        }
        if (comparison == null) {
            throw new IllegalArgumentException("comparison is required");
        }
        ColumnType predicateType = columns.get(predicateColumnIndex).type();
        requireValueType(predicateType, constant);

        try (FileChannel channel = FileChannel.open(source, StandardOpenOption.READ)) {
            PartitionLayout layout = readLayout(channel, columns.size(), source);
            // Read the predicate chunk first. PAX keeps one column together, so this avoids
            // loading every column before knowing whether any row survives the filter.
            ByteBuffer predicateChunk = readChunk(channel, layout.offsets()[predicateColumnIndex],
                    layout.lengths()[predicateColumnIndex], source);
            List<Integer> matchingIndexes = new ArrayList<>();
            List<Object> matchingValues = new ArrayList<>();
            try {
                for (int rowIndex = 0; rowIndex < layout.rowCount(); rowIndex++) {
                    Object value = decodeValue(predicateType, predicateChunk);
                    if (matches(predicateType, comparison, value, constant)) {
                        matchingIndexes.add(rowIndex);
                        matchingValues.add(value);
                    }
                }
            } catch (RuntimeException error) {
                throw new IllegalArgumentException("invalid column data in " + source, error);
            }
            if (predicateChunk.hasRemaining()) {
                throw new IllegalArgumentException("unexpected bytes in column chunk in " + source);
            }
            if (matchingIndexes.isEmpty()) {
                // No output rows means the other PAX chunks are irrelevant; leaving them unread
                // saves I/O and avoids decoding values that cannot be returned.
                return List.of();
            }

            // Allocate output rows only after matching indexes are known, rather than one row
            // per on-disk row as the complete decoder does.
            List<Object[]> result = new ArrayList<>(matchingIndexes.size());
            for (int index = 0; index < matchingIndexes.size(); index++) {
                Object[] row = new Object[columns.size()];
                row[predicateColumnIndex] = matchingValues.get(index);
                result.add(row);
            }
            for (int columnIndex = 0; columnIndex < columns.size(); columnIndex++) {
                if (columnIndex == predicateColumnIndex) {
                    continue;
                }
                // Decode remaining chunks only for output rows. Unmatched values are skipped in
                // their encoded form, which preserves row order without creating unused objects.
                ByteBuffer chunk = readChunk(channel, layout.offsets()[columnIndex],
                        layout.lengths()[columnIndex], source);
                int matchingIndex = 0;
                try {
                    for (int rowIndex = 0; rowIndex < layout.rowCount(); rowIndex++) {
                        if (matchingIndex < matchingIndexes.size()
                                && matchingIndexes.get(matchingIndex) == rowIndex) {
                            result.get(matchingIndex)[columnIndex] =
                                    decodeValue(columns.get(columnIndex).type(), chunk);
                            matchingIndex++;
                        } else {
                            skipValue(columns.get(columnIndex).type(), chunk);
                        }
                    }
                } catch (RuntimeException error) {
                    throw new IllegalArgumentException("invalid column data in " + source, error);
                }
                if (chunk.hasRemaining()) {
                    throw new IllegalArgumentException("unexpected bytes in column chunk in " + source);
                }
            }
            return result;
        }
    }

    private static PartitionLayout readLayout(FileChannel channel, int expectedColumns, Path source)
            throws IOException {
        int headerSize = Math.addExact(FIXED_HEADER_BYTES,
                Math.multiplyExact(expectedColumns, COLUMN_DIRECTORY_BYTES));
        long fileSize = channel.size();
        if (fileSize < headerSize) {
            throw new IllegalArgumentException("partition header is truncated in " + source);
        }
        return readLayout(readChunk(channel, 0, headerSize, source), expectedColumns,
                fileSize, source);
    }

    private static PartitionLayout readLayout(ByteBuffer file, int expectedColumns,
            long fileSize, Path source) {
        if (file.remaining() < FIXED_HEADER_BYTES) {
            throw new IllegalArgumentException("partition header is truncated in " + source);
        }
        for (byte expected : MAGIC) {
            if (file.get() != expected) {
                throw new IllegalArgumentException("invalid partition magic in " + source);
            }
        }
        int version = file.getInt();
        int rows = file.getInt();
        int columns = file.getInt();
        int requiredHeader = Math.addExact(FIXED_HEADER_BYTES,
                Math.multiplyExact(columns, COLUMN_DIRECTORY_BYTES));
        if (version != FORMAT_VERSION || rows < 0 || columns != expectedColumns
                || requiredHeader > fileSize || file.remaining() < columns * COLUMN_DIRECTORY_BYTES) {
            throw new IllegalArgumentException("invalid partition header in " + source);
        }
        int[] offsets = new int[columns];
        int[] lengths = new int[columns];
        for (int columnIndex = 0; columnIndex < columns; columnIndex++) {
            int offset = checkedInt(file.getLong(), source);
            int length = checkedInt(file.getLong(), source);
            if (offset < requiredHeader || length < 0 || offset > fileSize - length) {
                throw new IllegalArgumentException("invalid column chunk in " + source);
            }
            offsets[columnIndex] = offset;
            lengths[columnIndex] = length;
        }
        return new PartitionLayout(rows, offsets, lengths);
    }

    private static ByteBuffer readChunk(FileChannel channel, long offset, int length, Path source)
            throws IOException {
        ByteBuffer chunk = ByteBuffer.allocate(length);
        long position = offset;
        while (chunk.hasRemaining()) {
            int read = channel.read(chunk, position);
            if (read < 0) {
                throw new IllegalArgumentException("partition is truncated in " + source);
            }
            position += read;
        }
        chunk.flip();
        return chunk.order(LITTLE_ENDIAN);
    }

    private static void skipValue(ColumnType type, ByteBuffer source) {
        switch (type) {
            case STRING -> {
                int length = stringLength(source);
                source.position(source.position() + length);
            }
            case LONG -> source.position(source.position() + Long.BYTES);
            case DOUBLE -> source.position(source.position() + Double.BYTES);
        }
    }

    private static int checkedInt(long value, Path source) {
        try {
            return Math.toIntExact(value);
        } catch (ArithmeticException error) {
            throw new IllegalArgumentException("partition offset is too large in " + source, error);
        }
    }

    private static IllegalArgumentException csvError(Path source, int lineNumber,
            String detail, Exception cause) {
        return new IllegalArgumentException(
                detail + " in " + source + " at line " + lineNumber, cause);
    }

    private static byte[] encodeString(String value) {
        if (!StandardCharsets.US_ASCII.newEncoder().canEncode(value)) {
            throw new IllegalArgumentException("STRING values must contain ASCII characters only");
        }
        byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
        return ByteBuffer.allocate(Integer.BYTES + bytes.length).order(LITTLE_ENDIAN)
                .putInt(bytes.length).put(bytes).array();
    }

    private static String decodeString(ByteBuffer source) {
        int length = stringLength(source);
        int position = source.position();
        if (source.hasArray()) {
            // Partition chunks are heap buffers, so construct the String from the backing bytes
            // and advance the cursor directly instead of allocating a temporary byte array.
            String value = new String(source.array(), source.arrayOffset() + position, length,
                    StandardCharsets.US_ASCII);
            source.position(position + length);
            return value;
        }
        byte[] bytes = new byte[length];
        source.get(bytes);
        return new String(bytes, StandardCharsets.US_ASCII);
    }

    private static int stringLength(ByteBuffer source) {
        int length = source.getInt();
        if (length < 0 || length > source.remaining()) {
            throw new IllegalArgumentException("invalid STRING length: " + length);
        }
        return length;
    }

    private static void requireValueType(ColumnType type, Object value) {
        Class<?> expected = switch (type) {
            case STRING -> String.class;
            case LONG -> Long.class;
            case DOUBLE -> Double.class;
        };
        if (value == null || value.getClass() != expected) {
            throw new IllegalArgumentException(
                    "expected " + expected.getSimpleName() + " for " + type);
        }
    }

    @SuppressWarnings("unchecked")
    private static int compare(ColumnType type, Object left, Object right) {
        requireValueType(type, left);
        requireValueType(type, right);
        return ((Comparable<Object>) left).compareTo(right);
    }

    private record PartitionLayout(int rowCount, int[] offsets, int[] lengths) { }
}

record MinMax(Object min, Object max) { }
