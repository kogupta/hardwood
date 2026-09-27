/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.internal.encoding;

import org.junit.jupiter.api.Test;

import dev.hardwood.reader.ParquetReadException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/// Malformed DELTA_LENGTH_BYTE_ARRAY and DELTA_BYTE_ARRAY streams fail fast
/// with controlled exceptions. The streams are composed from the real
/// encoders: the encoders can never produce these values, which is exactly
/// why the decoders must reject them when a file does.
class DeltaByteArrayDecoderBoundsTest {

    @Test
    void rejectsNegativeLengthInDeltaLengthStream() {
        byte[] data = concat(DeltaBinaryPackedEncoder.encodeInts(new int[] { -3 }, 0, 1), "abc".getBytes());
        DeltaLengthByteArrayDecoder decoder = new DeltaLengthByteArrayDecoder(data, 0);
        decoder.initialize(1);

        Throwable thrown = catchThrowable(decoder::readValue);

        assertThat(thrown).isInstanceOf(ParquetReadException.class)
                .hasMessage("Negative byte array length: -3");
    }

    @Test
    void rejectsOverLongPrefixAtSecondValue() {
        // The second value claims a 5-byte prefix, but the first value is 2
        // bytes long.
        byte[] suffixes = DeltaLengthByteArrayEncoder.encode("xyz".getBytes(), new int[] { 0, 2, 3 }, 0, 2);
        byte[] data = concat(DeltaBinaryPackedEncoder.encodeInts(new int[] { 0, 5 }, 0, 2), suffixes);
        DeltaByteArrayDecoder decoder = new DeltaByteArrayDecoder(data, 0);
        decoder.initialize(2);

        assertThat(decoder.readValue()).isEqualTo("xy".getBytes());

        Throwable thrown = catchThrowable(decoder::readValue);

        assertThat(thrown).isInstanceOf(ParquetReadException.class)
                .hasMessage("Invalid prefix length 5 at value index 1: previous value has 2 bytes");
    }

    @Test
    void rejectsNegativePrefix() {
        byte[] suffixes = DeltaLengthByteArrayEncoder.encode("xyz".getBytes(), new int[] { 0, 2, 3 }, 0, 2);
        byte[] data = concat(DeltaBinaryPackedEncoder.encodeInts(new int[] { -1, 0 }, 0, 2), suffixes);
        DeltaByteArrayDecoder decoder = new DeltaByteArrayDecoder(data, 0);
        decoder.initialize(2);

        Throwable thrown = catchThrowable(decoder::readValue);

        assertThat(thrown).isInstanceOf(ParquetReadException.class)
                .hasMessage("Invalid prefix length -1 at value index 0: previous value has 0 bytes");
    }

    @Test
    void rejectsPrefixLongerThanTheEmptyFirstValue() {
        // The first value of a page shares nothing, so any positive prefix
        // against the empty previous value is malformed.
        byte[] suffixes = DeltaLengthByteArrayEncoder.encode("abcd".getBytes(), new int[] { 0, 1, 3 }, 0, 2);
        byte[] data = concat(DeltaBinaryPackedEncoder.encodeInts(new int[] { 3, 0 }, 0, 2), suffixes);
        DeltaByteArrayDecoder decoder = new DeltaByteArrayDecoder(data, 0);
        decoder.initialize(2);

        Throwable thrown = catchThrowable(decoder::readValue);

        assertThat(thrown).isInstanceOf(ParquetReadException.class)
                .hasMessage("Invalid prefix length 3 at value index 0: previous value has 0 bytes");
    }

    @Test
    void acceptsWellFormedStream() {
        // Values "ab" and "abcd": the second shares its 2-byte prefix with
        // the first.
        byte[] suffixes = DeltaLengthByteArrayEncoder.encode("abcd".getBytes(), new int[] { 0, 2, 4 }, 0, 2);
        byte[] data = concat(DeltaBinaryPackedEncoder.encodeInts(new int[] { 0, 2 }, 0, 2), suffixes);
        DeltaByteArrayDecoder decoder = new DeltaByteArrayDecoder(data, 0);
        decoder.initialize(2);

        assertThat(decoder.readValue()).isEqualTo("ab".getBytes());
        assertThat(decoder.readValue()).isEqualTo("abcd".getBytes());
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] out = new byte[first.length + second.length];
        System.arraycopy(first, 0, out, 0, first.length);
        System.arraycopy(second, 0, out, first.length, second.length);
        return out;
    }
}
