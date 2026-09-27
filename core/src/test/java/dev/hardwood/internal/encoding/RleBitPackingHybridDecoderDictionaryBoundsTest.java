/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.internal.encoding;

import java.io.ByteArrayOutputStream;

import org.junit.jupiter.api.Test;

import dev.hardwood.reader.ParquetReadException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/// Out-of-range dictionary indices fail fast with a controlled exception
/// instead of surfacing as [ArrayIndexOutOfBoundsException] from inside the
/// SIMD dispatch.
class RleBitPackingHybridDecoderDictionaryBoundsTest {

    @Test
    void rejectsOutOfRangeIndexWithoutDefinitionLevels() {
        byte[] encoded = encodeRleRun(3, 4, 2);
        long[] dictionary = { 10, 20 };
        long[] output = new long[4];

        Throwable thrown = catchThrowable(() ->
                decoder(encoded, 2).readDictionaryLongs(output, dictionary, null, 0));

        assertThat(thrown).isInstanceOf(ParquetReadException.class)
                .hasMessage("Invalid dictionary index 3 at position 0: dictionary has 2 entries");
    }

    @Test
    void rejectsOutOfRangeIndexWithDefinitionLevels() {
        byte[] encoded = encodeRleRun(5, 4, 3);
        int[] dictionary = { 0, 1 };
        int[] output = new int[4];
        int[] defLevels = { 1, 1, 1, 1 };

        Throwable thrown = catchThrowable(() ->
                decoder(encoded, 3).readDictionaryInts(output, dictionary, defLevels, 1));

        assertThat(thrown).isInstanceOf(ParquetReadException.class)
                .hasMessage("Invalid dictionary index 5 at position 0: dictionary has 2 entries");
    }

    @Test
    void rejectsIndexEqualToDictionaryLength() {
        byte[] encoded = encodeRleRun(2, 2, 2);
        double[] dictionary = { 0.5, 1.5 };
        double[] output = new double[2];

        Throwable thrown = catchThrowable(() ->
                decoder(encoded, 2).readDictionaryDoubles(output, dictionary, null, 0));

        assertThat(thrown).isInstanceOf(ParquetReadException.class)
                .hasMessage("Invalid dictionary index 2 at position 0: dictionary has 2 entries");
    }

    @Test
    void rejectsOutOfRangeIndexForByteArrays() {
        byte[] encoded = encodeRleRun(7, 3, 3);
        byte[][] dictionary = { { 1 }, { 2 } };
        byte[][] output = new byte[3][];
        int[] outDictIndices = new int[3];

        Throwable thrown = catchThrowable(() ->
                decoder(encoded, 3).readDictionaryByteArrays(output, outDictIndices, dictionary, null, 0));

        assertThat(thrown).isInstanceOf(ParquetReadException.class)
                .hasMessage("Invalid dictionary index 7 at position 0: dictionary has 2 entries");
    }

    @Test
    void reportsFirstFailingPositionAfterNulls() {
        // Position 0 is null (def level 0 < maxDef), so the first non-null
        // position - where the dictionary is consulted - is 1.
        byte[] encoded = encodeRleRun(9, 2, 4);
        float[] dictionary = { 0.25f, 0.75f };
        float[] output = new float[2];
        int[] defLevels = { 0, 1 };

        Throwable thrown = catchThrowable(() ->
                decoder(encoded, 4).readDictionaryFloats(output, dictionary, defLevels, 1));

        assertThat(thrown).isInstanceOf(ParquetReadException.class)
                .hasMessage("Invalid dictionary index 9 at position 1: dictionary has 2 entries");
    }

    @Test
    void acceptsHighestValidIndex() {
        byte[] encoded = encodeRleRun(1, 4, 1);
        int[] dictionary = { 100, 200 };
        int[] output = new int[4];

        decoder(encoded, 1).readDictionaryInts(output, dictionary, null, 0);

        assertThat(output).containsExactly(200, 200, 200, 200);
    }

    private static RleBitPackingHybridDecoder decoder(byte[] data, int bitWidth) {
        return new RleBitPackingHybridDecoder(data, 0, data.length, bitWidth);
    }

    private static byte[] encodeRleRun(int value, int count, int bitWidth) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeUnsignedVarInt(out, (long) count << 1);
        int bytesNeeded = (bitWidth + 7) / 8;
        for (int i = 0; i < bytesNeeded; i++) {
            out.write((value >> (i * 8)) & 0xFF);
        }
        return out.toByteArray();
    }

    private static void writeUnsignedVarInt(ByteArrayOutputStream out, long value) {
        while ((value & ~0x7FL) != 0) {
            out.write((int) ((value & 0x7F) | 0x80));
            value >>>= 7;
        }
        out.write((int) value);
    }
}
