/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.internal.encoding;

import java.util.Arrays;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/// Generated round trips across every RLE/bit-packing bit width and variable stream length.
class RleBitPackingHybridPropertyTest {

    @Property(tries = 200)
    void encoderAndDecoderAgreeAcrossBitWidths(
            @ForAll @IntRange(min = 0, max = 32) int bitWidth,
            @ForAll int[] generatedValues) {
        int[] expected = Arrays.copyOf(generatedValues, generatedValues.length);
        int mask = bitWidth == 32 ? -1 : bitWidth == 0 ? 0 : (1 << bitWidth) - 1;
        for (int i = 0; i < expected.length; i++) {
            expected[i] &= mask;
        }

        RleBitPackingHybridEncoder encoder = new RleBitPackingHybridEncoder(bitWidth);
        encoder.writeInts(expected, 0, expected.length);
        byte[] encoded = encoder.toByteArray();

        int[] decoded = new int[expected.length];
        new RleBitPackingHybridDecoder(encoded, bitWidth).readInts(decoded, 0, decoded.length);

        assertArrayEquals(expected, decoded);
    }
}
