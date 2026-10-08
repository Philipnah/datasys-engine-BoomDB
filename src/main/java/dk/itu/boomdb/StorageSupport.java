package dk.itu.boomdb;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import static java.nio.ByteOrder.LITTLE_ENDIAN;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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

    static Object decodeStatistic(ColumnType type, String value) {
        return switch (type) {
            case STRING -> value;
            case LONG -> Long.valueOf(value);
            case DOUBLE -> Double.valueOf(value);
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
        validateHeader(file, columns.size(), source);
        int rowCount = file.getInt(2 * Integer.BYTES);
        int headerSize = Math.addExact(FIXED_HEADER_BYTES,
                Math.multiplyExact(columns.size(), COLUMN_DIRECTORY_BYTES));
        Object[][] rows = new Object[rowCount][columns.size()];

        file.position(FIXED_HEADER_BYTES);
        for (int columnIndex = 0; columnIndex < columns.size(); columnIndex++) {
            int offset = checkedInt(file.getLong(), source);
            int length = checkedInt(file.getLong(), source);
            if (offset < headerSize || length < 0 || offset > bytes.length - length) {
                throw new IllegalArgumentException("invalid column chunk in " + source);
            }
            ByteBuffer chunk = ByteBuffer.wrap(bytes, offset, length).slice().order(LITTLE_ENDIAN);
            try {
                for (int rowIndex = 0; rowIndex < rowCount; rowIndex++) {
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

        List<Object[]> result = new ArrayList<>(rowCount);
        result.addAll(Arrays.asList(rows));
        return result;
    }

    private static void validateHeader(ByteBuffer file, int expectedColumns, Path source) {
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
                || requiredHeader > file.capacity()) {
            throw new IllegalArgumentException("invalid partition header in " + source);
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
        int length = source.getInt();
        if (length < 0 || length > source.remaining()) {
            throw new IllegalArgumentException("invalid STRING length: " + length);
        }
        byte[] bytes = new byte[length];
        source.get(bytes);
        return new String(bytes, StandardCharsets.US_ASCII);
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
}

record MinMax(Object min, Object max) { }
