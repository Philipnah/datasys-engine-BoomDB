package dk.itu.boomdb;

import static java.nio.ByteOrder.LITTLE_ENDIAN;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.io.TempDir;

class StorageSupportTest {
    private static final List<ColumnSpec> TRIP_COLUMNS = List.of(
            new ColumnSpec("city", ColumnType.STRING),
            new ColumnSpec("distance", ColumnType.LONG),
            new ColumnSpec("price", ColumnType.DOUBLE));

    @Test
    void stringValueEncodingRoundTrips() {
        assertEquals("Copenhagen", roundTrip(ColumnType.STRING, "Copenhagen"));
    }

    @Test
    void longValueEncodingRoundTrips() {
        assertEquals(9_223_372_036_854_775_000L,
                roundTrip(ColumnType.LONG, 9_223_372_036_854_775_000L));
    }

    @Test
    void doubleValueEncodingRoundTrips() {
        assertEquals(-123.75, roundTrip(ColumnType.DOUBLE, -123.75));
    }

    @Test
    void minMaxFindsLexicographicStringBounds() {
        assertEquals(new MinMax("Aalborg", "Odense"),
                StorageSupport.minMax(ColumnType.STRING,
                        List.of("Copenhagen", "Odense", "Aalborg")));
    }

    @Test
    void minMaxReturnsTheSingleValueAsBothBounds() {
        assertEquals(new MinMax(23.5, 23.5),
                StorageSupport.minMax(ColumnType.DOUBLE, List.of(23.5)));
    }

    @Test
    void minMaxOrdersNegativeLongs() {
        assertEquals(new MinMax(-20L, -2L),
                StorageSupport.minMax(ColumnType.LONG, List.of(-2L, -20L, -7L)));
    }

    @ParameterizedTest
    @MethodSource("pruningCases")
    void pruningUsesComparisonBoundaries(Comparison comparison, long constant,
            long min, long max, boolean expected) {
        assertEquals(expected, StorageSupport.shouldPrune(
                ColumnType.LONG, comparison, constant, min, max));
    }

    private static Stream<Arguments> pruningCases() {
        return Stream.of(
                Arguments.of(Comparison.EQUALS, 10L, 20L, 30L, true),
                Arguments.of(Comparison.EQUALS, 25L, 20L, 30L, false),
                Arguments.of(Comparison.LESS_THAN, 10L, 10L, 20L, true),
                Arguments.of(Comparison.LESS_THAN, 15L, 10L, 20L, false),
                Arguments.of(Comparison.GREATER_THAN, 30L, 10L, 30L, true),
                Arguments.of(Comparison.GREATER_THAN, 15L, 10L, 20L, false));
    }

    @ParameterizedTest
    @MethodSource("matchingCases")
    void rowMatchingUsesStrictComparisonSemantics(ColumnType type,
            Comparison comparison, Object value, Object constant, boolean expected) {
        assertEquals(expected, StorageSupport.matches(type, comparison, value, constant));
    }

    private static Stream<Arguments> matchingCases() {
        return Stream.of(
                Arguments.of(ColumnType.STRING, Comparison.EQUALS,
                        "Copenhagen", "Copenhagen", true),
                Arguments.of(ColumnType.STRING, Comparison.EQUALS,
                        "Aarhus", "Copenhagen", false),
                Arguments.of(ColumnType.LONG, Comparison.LESS_THAN, 9L, 10L, true),
                Arguments.of(ColumnType.LONG, Comparison.LESS_THAN, 10L, 10L, false),
                Arguments.of(ColumnType.DOUBLE, Comparison.GREATER_THAN, 10.5, 10.0, true),
                Arguments.of(ColumnType.DOUBLE, Comparison.GREATER_THAN, 10.0, 10.0, false));
    }

    @Test
    void csvLineParsesValuesBySchemaPosition() {
        assertArrayEquals(new Object[] {"Copenhagen", 12L, 23.5},
                StorageSupport.parseCsvLine(
                        Path.of("trips.csv"), 1, "Copenhagen,12,23.5", TRIP_COLUMNS));
    }

    @Test
    void malformedCsvValueReportsFileAndLine() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> StorageSupport.parseCsvLine(
                        Path.of("bad.csv"), 4, "Copenhagen,nope,23.5", TRIP_COLUMNS));

        assertTrue(error.getMessage().contains("bad.csv"));
        assertTrue(error.getMessage().contains("line 4"));
    }

    @Test
    void wrongCsvFieldCountReportsFileAndLine() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> StorageSupport.parseCsvLine(
                        Path.of("short.csv"), 7, "Copenhagen,12", TRIP_COLUMNS));

        assertTrue(error.getMessage().contains("short.csv"));
        assertTrue(error.getMessage().contains("line 7"));
    }

    @Test
    void binaryPartitionRoundTripsRowsInSchemaOrder(@TempDir Path directory) throws Exception {
        Path partition = directory.resolve("trips-0.bin");
        List<Object[]> rows = List.of(
                new Object[] {"Copenhagen", 12L, 23.5},
                new Object[] {"Aarhus", 187L, 301.0});

        StorageSupport.writePartition(partition, TRIP_COLUMNS, rows);

        byte[] bytes = Files.readAllBytes(partition);
        assertArrayEquals(new byte[] {'B', 'D', 'B', 'P'},
                new byte[] {bytes[0], bytes[1], bytes[2], bytes[3]});
        assertEquals(1, ByteBuffer.wrap(bytes, 4, Integer.BYTES).order(LITTLE_ENDIAN).getInt());
        List<Object[]> restored = StorageSupport.readPartition(partition, TRIP_COLUMNS);
        assertEquals(2, restored.size());
        assertArrayEquals(rows.get(0), restored.get(0));
        assertArrayEquals(rows.get(1), restored.get(1));
    }

    private static Object roundTrip(ColumnType type, Object value) {
        byte[] encoded = StorageSupport.encodeValue(type, value);
        return StorageSupport.decodeValue(type, ByteBuffer.wrap(encoded).order(LITTLE_ENDIAN));
    }
}
